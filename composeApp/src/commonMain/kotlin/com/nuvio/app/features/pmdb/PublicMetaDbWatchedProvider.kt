package com.nuvio.app.features.pmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingWatchedProvider
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException

private val log = Logger.withTag("PublicMetaDb")

/** Watched state backed by the PublicMetaDB watched list. */
internal class PublicMetaDbWatchedProvider(
    private val api: PublicMetaDbApi,
    private val ids: PublicMetaDbIds,
    private val apiKey: (profileId: Int) -> String?,
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : TrackingWatchedProvider {
    override val providerId = TrackingProviderId.PMDB

    override suspend fun pull(profileId: Int, pageSize: Int): List<WatchedItem> {
        val key = apiKey(profileId) ?: return emptyList()
        val items = mutableListOf<PmdbWatchedItem>()
        var page = 1
        val perPage = pageSize.coerceIn(1, PublicMetaDbApi.PAGE_SIZE)
        while (page <= MAX_PAGES) {
            val result = api.watchedPage(key, page, perPage)
            items += result.items
            if (page >= result.totalPages || result.items.isEmpty()) break
            page++
        }
        // Resolve each distinct title once, in parallel, so the mapping below is cache-only.
        val budget = LookupBudget(tmdb = TMDB_LOOKUPS_PER_PULL, pmdb = PMDB_LOOKUPS_PER_PULL)
        ids.prefetchImdb(key, items.map { it.tmdbId to it.mediaType }, budget)
        val pulled = items.mapIndexedNotNull { index, item ->
            val isMovie = item.mediaType == PMDB_MEDIA_MOVIE
            val season = if (isMovie) null else item.season ?: 1
            val episode = if (isMovie) null else item.episode?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            val contentId = ids.imdbFor(key, item.tmdbId, item.mediaType, budget = null)
                ?: "tmdb:${item.tmdbId}"
            WatchedItem(
                id = contentId,
                type = if (isMovie) "movie" else "series",
                name = "",
                season = season,
                episode = episode,
                videoId = buildPlaybackVideoId(contentId, season, episode),
                trackingProviderId = PMDB_PROVIDER_STORAGE_ID,
                trackingProviderItemId = item.id,
                // Without a usable timestamp, keep PublicMetaDB's list order (newest first) instead of tying at "now".
                markedAtEpochMs = parsePmdbTimestamp(item.watchedAt) ?: (nowEpochMs() - index * 1_000L),
            )
        }
        ids.flush()
        val unresolved = pulled.count { it.id.startsWith("tmdb:") }
        val unparsed = items.count { parsePmdbTimestamp(it.watchedAt) == null }
        log.i {
            "Pulled ${pulled.size} watched items (${items.size} from PublicMetaDB, $unresolved without an IMDb id yet); " +
                "$unparsed without a usable timestamp; sample watched_at='${items.firstOrNull()?.watchedAt}'"
        }
        return pulled
    }

    override suspend fun push(profileId: Int, items: Collection<WatchedItem>) {
        val key = apiKey(profileId) ?: return
        for (item in items) {
            val target = ids.resolve(key, item.toMediaReference()) ?: continue
            try {
                api.markWatched(key, target, kotlin.time.Instant.fromEpochMilliseconds(item.markedAtEpochMs).toString())
            } catch (error: CancellationException) {
                throw error
            } catch (error: PublicMetaDbApiException) {
                if (error.isUnauthorized) throw error
            }
        }
        ids.flush()
    }

    override suspend fun delete(profileId: Int, items: Collection<WatchedItem>) {
        val key = apiKey(profileId) ?: return
        for (item in items) {
            val target = ids.resolve(key, item.toMediaReference()) ?: continue
            try {
                api.unmarkWatched(key, target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PublicMetaDbApiException) {
                if (error.isUnauthorized) throw error
            }
        }
        ids.flush()
    }

    private fun WatchedItem.toMediaReference() = buildTrackingMediaReference(
        contentType = type,
        parentMetaId = id,
        videoId = videoId,
        title = name,
        releaseInfo = releaseInfo,
        seasonNumber = season,
        episodeNumber = episode,
    )

    private companion object {
        const val MAX_PAGES = 100
        const val TMDB_LOOKUPS_PER_PULL = 1_500
        const val PMDB_LOOKUPS_PER_PULL = 20
    }
}
