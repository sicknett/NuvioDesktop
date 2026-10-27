package com.nuvio.app.features.pmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val PublicMetaDbJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * PublicMetaDB timestamps are not uniformly ISO-8601: they can use a space instead of `T`
 * (`2026-10-20 12:00:00.123Z`), omit the zone, or arrive as epoch seconds/milliseconds.
 * Zone-less values are read as UTC. Returns null when nothing usable is there.
 */
internal fun parsePmdbTimestamp(raw: String?): Long? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.all(Char::isDigit)) {
        val number = text.toLongOrNull() ?: return null
        return if (text.length >= 13) number else number * 1_000L
    }
    var iso = text.replace(' ', 'T')
    if (iso.length == 10) iso += "T00:00:00Z"
    if (iso.length < 12 || iso[10] != 'T') return null
    val timePart = iso.substring(11)
    val hasZone = timePart.endsWith('Z', ignoreCase = true) || timePart.contains('+') || timePart.contains('-')
    if (!hasZone) iso += "Z"
    return com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs(iso.replace('z', 'Z'))
}

internal const val PMDB_MEDIA_MOVIE = "movie"
internal const val PMDB_MEDIA_TV = "tv"

/** Identifies one PublicMetaDB title or episode. Movies never carry a season/episode. */
internal data class PmdbTarget(
    val tmdbId: Long,
    val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isMovie: Boolean get() = mediaType == PMDB_MEDIA_MOVIE
    val key: String
        get() = if (isMovie) "movie:$tmdbId" else "tv:$tmdbId:${season ?: 0}:${episode ?: 0}"
}

@Serializable
internal data class PmdbPage<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val perPage: Int = 0,
    val totalPages: Int = 1,
)

@Serializable
internal data class PmdbResumePoint(
    val id: String,
    @SerialName("tmdb_id") val tmdbId: Long,
    @SerialName("media_type") val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("runtime_ms") val runtimeMs: Long = 0L,
    val progress: Double = 0.0,
    val created: String? = null,
    val updated: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val year: Int? = null,
)

@Serializable
internal data class PmdbSaveResumeResponse(
    val action: String? = null,
    val reason: String? = null,
    val progress: Double? = null,
)

@Serializable
internal data class PmdbWatchedItem(
    val id: String? = null,
    @SerialName("tmdb_id") val tmdbId: Long,
    @SerialName("media_type") val mediaType: String,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
)

internal class PublicMetaDbApiException(
    val status: Int,
    val body: String = "",
    val retryAtEpochMs: Long? = null,
) : Exception("PublicMetaDB request failed (HTTP $status)") {
    val isUnauthorized: Boolean get() = status == 401 || status == 403
    val isConflict: Boolean get() = status == 409
}
