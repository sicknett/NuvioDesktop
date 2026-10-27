package com.nuvio.app.features.pmdb

import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import com.nuvio.app.features.tracking.TrackingSeekScrobblePolicy
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/**
 * Writes watch history and playback progress to PublicMetaDB.
 *
 * PublicMetaDB stores absolute resume points instead of start/pause/stop sessions and meters
 * requests per hour, so START is ignored and progress is saved on pause/stop only (seeks are
 * ignored too). A stop at or past [COMPLETE_PERCENT] marks the title watched and drops its resume
 * point; anything below [MIN_SAVE_PERCENT] is treated as an accidental start and not saved.
 */
internal class PublicMetaDbTrackingWrites(
    private val api: PublicMetaDbApi,
    private val ids: PublicMetaDbIds,
    private val progress: PublicMetaDbProgressProvider,
    private val apiKey: (profileId: Int) -> String?,
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : TrackingHistoryWriter, TrackingScrobbler {
    override val providerId = TrackingProviderId.PMDB
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.NONE

    private val lastSavedAt = mutableMapOf<String, Long>()

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val key = apiKey(profileId) ?: return TrackingMutationResult(items.size, items.size)
        var failed = 0
        for (item in items) {
            val target = ids.resolve(key, item.media)
            if (target == null) {
                failed++
                continue
            }
            try {
                api.markWatched(key, target, isoAt(item.watchedAtEpochMs ?: nowEpochMs()))
                progress.forgetLocally(target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PublicMetaDbApiException) {
                if (error.isUnauthorized) throw error
                failed++
            }
        }
        ids.flush()
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = failed)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val key = apiKey(profileId) ?: return TrackingMutationResult(items.size, items.size)
        var failed = 0
        for (media in items) {
            val target = ids.resolve(key, media)
            if (target == null) {
                failed++
                continue
            }
            try {
                api.unmarkWatched(key, target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PublicMetaDbApiException) {
                if (error.isUnauthorized) throw error
                if (error.status != 404) failed++
            }
        }
        ids.flush()
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = failed)
    }

    override suspend fun scrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (action == TrackingScrobbleAction.START) return
        val percent = event.progressPercent
        if (!percent.isFinite()) return
        val key = apiKey(profileId) ?: return
        val target = ids.resolve(key, event.media) ?: return

        if (percent >= COMPLETE_PERCENT) {
            api.markWatched(key, target, isoAt(nowEpochMs()))
            progress.clearResume(key, target)
            lastSavedAt.remove(target.key)
            return
        }

        val duration = event.durationMs?.takeIf { it > 0L } ?: return
        if (percent < MIN_SAVE_PERCENT) return
        val now = nowEpochMs()
        val previous = lastSavedAt[target.key]
        if (action == TrackingScrobbleAction.PAUSE && previous != null && now - previous < PAUSE_SAVE_COOLDOWN_MS) return
        lastSavedAt[target.key] = now

        val position = (duration * percent / 100.0).toLong().coerceIn(0L, duration)
        val response = try {
            api.saveResume(key, target, position, duration)
        } catch (error: PublicMetaDbApiException) {
            if (!error.isConflict) throw error
            // A resume point already exists for this title under another id: replace it once.
            progress.findResumeId(key, target)?.let { api.deleteResume(key, it) }
            api.saveResume(key, target, position, duration)
        }
        if (response.action == "completed") {
            api.markWatched(key, target, isoAt(now))
            progress.clearResume(key, target)
        }
    }

    private fun isoAt(epochMs: Long): String = Instant.fromEpochMilliseconds(epochMs).toString()

    private companion object {
        const val COMPLETE_PERCENT = 90.0
        const val MIN_SAVE_PERCENT = 2.0
        const val PAUSE_SAVE_COOLDOWN_MS = 15_000L
    }
}
