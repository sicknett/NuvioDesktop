package com.nuvio.app.features.pmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.TrackingProgressProvider
import com.nuvio.app.features.tracking.TrackingProgressSnapshot
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val log = Logger.withTag("PublicMetaDb")

internal const val PMDB_PROGRESS_SOURCE = "pmdb_playback"
internal const val PMDB_PROVIDER_STORAGE_ID = "pmdb"

/** Continue-watching rows backed by PublicMetaDB resume points (the same ones other PMDB apps write). */
internal class PublicMetaDbProgressProvider(
    private val api: PublicMetaDbApi,
    private val ids: PublicMetaDbIds,
    private val apiKey: () -> String?,
    private val ensureAccountLoaded: () -> Unit,
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.PMDB

    private data class State(
        val points: List<PmdbResumePoint> = emptyList(),
        val entries: List<WatchProgressEntry> = emptyList(),
        val hasLoaded: Boolean = false,
        val error: String? = null,
        val refreshedAtEpochMs: Long = 0L,
    )

    private val state = MutableStateFlow(State())
    private val refreshMutex = Mutex()

    override val changes: Flow<Unit> = state.map { }
    val lastRefreshedAtEpochMs: Long get() = state.value.refreshedAtEpochMs
    val resumePointCount: Int get() = state.value.points.size
    val lastError: String? get() = state.value.error

    override fun ensureLoaded() = ensureAccountLoaded()

    override fun onProfileChanged() = clearLocalState()

    override fun clearLocalState() {
        state.value = State()
    }

    override suspend fun refresh(force: Boolean, sourceChanged: Boolean) {
        ensureAccountLoaded()
        val key = apiKey() ?: return
        val current = state.value
        if (!force && !sourceChanged && current.hasLoaded &&
            nowEpochMs() - current.refreshedAtEpochMs < MIN_AUTOMATIC_REFRESH_MS
        ) {
            return
        }
        refreshMutex.withLock {
            try {
                val points = loadResumePoints(key)
                val budget = LookupBudget(tmdb = TMDB_LOOKUPS_PER_REFRESH, pmdb = PMDB_LOOKUPS_PER_REFRESH)
                ids.prefetchImdb(key, points.map { it.tmdbId to it.mediaType }, budget)
                val entries = points.mapIndexedNotNull { index, point -> toEntry(key, point, null, index) }
                state.value = State(points, entries, hasLoaded = true, error = null, refreshedAtEpochMs = nowEpochMs())
                val unparsed = points.count { parsePmdbTimestamp(it.updated ?: it.created) == null }
                log.i {
                    "Synced ${points.size} resume points (${entries.size} shown); $unparsed without a usable timestamp; " +
                        "sample updated='${points.firstOrNull()?.updated}' created='${points.firstOrNull()?.created}'"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: PublicMetaDbApiException) {
                log.w(error) { "Resume sync failed (HTTP ${error.status}): ${error.body.take(120)}" }
                state.update { it.copy(error = describe(error), hasLoaded = it.hasLoaded) }
            } catch (error: Exception) {
                log.w(error) { "Resume sync failed" }
                state.update { it.copy(error = "PublicMetaDB sync failed") }
            }
        }
    }

    override fun snapshot(): TrackingProgressSnapshot {
        val current = state.value
        return TrackingProgressSnapshot(
            entries = current.entries,
            hiddenContentIds = emptySet(),
            hasLoadedRemoteProgress = current.hasLoaded,
            errorMessage = current.error,
        )
    }

    override suspend fun removeProgress(entries: Collection<WatchProgressEntry>) {
        val key = apiKey() ?: return
        entries.mapNotNull { it.trackingProviderItemId }.distinct().forEach { resumeId ->
            try {
                api.deleteResume(key, resumeId)
            } catch (error: PublicMetaDbApiException) {
                if (error.status != 404) throw error
            }
        }
        applyOptimisticRemoval(entries)
    }

    override fun applyOptimisticRemoval(entries: Collection<WatchProgressEntry>) {
        val removed = entries.mapNotNull { it.trackingProviderItemId }.toSet()
        if (removed.isEmpty()) return
        state.update { current ->
            current.copy(
                points = current.points.filterNot { it.id in removed },
                entries = current.entries.filterNot { it.trackingProviderItemId in removed },
            )
        }
    }

    /** Drops any local resume row for a title that was just completed or otherwise superseded. */
    fun forgetLocally(target: PmdbTarget) {
        state.update { current ->
            val gone = current.points.filter { it.matches(target) }.map { it.id }.toSet()
            if (gone.isEmpty()) current else current.copy(
                points = current.points.filterNot { it.id in gone },
                entries = current.entries.filterNot { it.trackingProviderItemId in gone },
            )
        }
    }

    suspend fun findResumeId(apiKey: String, target: PmdbTarget): String? {
        state.value.points.firstOrNull { it.matches(target) }?.let { return it.id }
        var page = 1
        while (page <= LOOKUP_MAX_PAGES) {
            val result = api.resumePage(apiKey, page)
            result.items.firstOrNull { it.matches(target) }?.let { return it.id }
            if (page >= result.totalPages) break
            page++
        }
        return null
    }

    suspend fun clearResume(apiKey: String, target: PmdbTarget) {
        val id = findResumeId(apiKey, target)
        if (id != null) {
            try {
                api.deleteResume(apiKey, id)
            } catch (error: PublicMetaDbApiException) {
                if (error.status != 404) throw error
            }
        }
        forgetLocally(target)
    }

    private suspend fun loadResumePoints(apiKey: String): List<PmdbResumePoint> {
        val all = mutableListOf<PmdbResumePoint>()
        var page = 1
        while (page <= MAX_PAGES) {
            val result = api.resumePage(apiKey, page)
            all += result.items
            if (page >= result.totalPages || result.items.isEmpty()) break
            page++
        }
        return all
    }

    private suspend fun toEntry(apiKey: String, point: PmdbResumePoint, budget: LookupBudget?, index: Int): WatchProgressEntry? {
        val isMovie = point.mediaType == PMDB_MEDIA_MOVIE
        val season = if (isMovie) null else point.season ?: 1
        val episode = if (isMovie) null else point.episode?.takeIf { it > 0 } ?: return null
        val percent = percentOf(point)
        if (percent >= COMPLETED_PERCENT) return null
        val contentId = ids.imdbFor(apiKey, point.tmdbId, point.mediaType, budget)
            ?: "tmdb:${point.tmdbId}"
        val type = if (isMovie) "movie" else "series"
        val duration = point.runtimeMs.coerceAtLeast(0L)
        return WatchProgressEntry(
            contentType = type,
            parentMetaId = contentId,
            parentMetaType = type,
            videoId = buildPlaybackVideoId(contentId, season, episode),
            title = point.title?.takeIf { it.isNotBlank() } ?: contentId,
            poster = point.poster?.takeIf { it.isNotBlank() },
            seasonNumber = season,
            episodeNumber = episode,
            lastPositionMs = point.positionMs.coerceAtLeast(0L),
            durationMs = duration,
            // Without a usable timestamp, keep PublicMetaDB's list order (newest first) instead of tying at "now".
            lastUpdatedEpochMs = parsePmdbTimestamp(point.updated ?: point.created) ?: (nowEpochMs() - index * 1_000L),
            progressPercent = percent,
            isCompleted = false,
            source = PMDB_PROGRESS_SOURCE,
            trackingProviderId = PMDB_PROVIDER_STORAGE_ID,
            trackingProviderItemId = point.id,
        )
    }

    private fun percentOf(point: PmdbResumePoint): Float = when {
        point.runtimeMs > 0L -> (point.positionMs.toDouble() / point.runtimeMs.toDouble() * 100.0).toFloat()
        point.progress <= 1.0 -> (point.progress * 100.0).toFloat()
        else -> point.progress.toFloat()
    }.coerceIn(0f, 100f)

    private fun describe(error: PublicMetaDbApiException): String = when {
        error.isUnauthorized -> "PublicMetaDB rejected the API key"
        error.status == 429 -> "PublicMetaDB rate limit reached; try again later"
        else -> "PublicMetaDB sync failed (HTTP ${error.status})"
    }

    private companion object {
        const val MIN_AUTOMATIC_REFRESH_MS = 60_000L
        const val MAX_PAGES = 5
        const val LOOKUP_MAX_PAGES = 3
        const val TMDB_LOOKUPS_PER_REFRESH = 300
        const val PMDB_LOOKUPS_PER_REFRESH = 20
        const val COMPLETED_PERCENT = 90f
    }
}

internal fun PmdbResumePoint.matches(target: PmdbTarget): Boolean {
    if (tmdbId != target.tmdbId || mediaType != target.mediaType) return false
    return target.isMovie || (season ?: 0) == (target.season ?: 0) && (episode ?: 0) == (target.episode ?: 0)
}
