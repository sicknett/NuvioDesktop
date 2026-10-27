package com.nuvio.app.features.pmdb

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.contentLength
import io.ktor.http.encodedPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class PublicMetaDbHttpResponse(val status: Int, val body: String, val retryAfterSeconds: Long?)

/**
 * Thin client for the PublicMetaDB external API (paths under `/api/external`, Bearer API key).
 * PublicMetaDB meters requests per hour, so callers batch where they can and every request is
 * spaced by [MIN_GAP_MS]; 429/5xx are retried with Retry-After or exponential backoff.
 */
internal fun interface PublicMetaDbTransport {
    suspend fun send(method: String, url: String, apiKey: String, jsonBody: String?): PublicMetaDbHttpResponse
}

private const val CONNECT_TIMEOUT_MS = 7_000L
private const val REQUEST_TIMEOUT_MS = 30_000L

internal class KtorPublicMetaDbTransport(
    private val client: HttpClient = createApiHttpClient(),
) : PublicMetaDbTransport {
    override suspend fun send(method: String, url: String, apiKey: String, jsonBody: String?): PublicMetaDbHttpResponse =
        client.prepareRequest(url) {
            // A stalled connect on a flaky route (VPN, Cloudflare edge) is better retried quickly than waited out.
            timeout {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            this.method = HttpMethod.parse(method)
            header("Accept", "application/json")
            header("Authorization", "Bearer $apiKey")
            header("User-Agent", "Nuvio/${AppVersionConfig.VERSION_NAME}")
            if (jsonBody != null) {
                header("Content-Type", "application/json")
                setBody(jsonBody)
            }
        }.execute { response ->
            PublicMetaDbHttpResponse(
                status = response.status.value,
                body = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength()),
                retryAfterSeconds = response.headers["Retry-After"]?.trim()?.toLongOrNull()?.takeIf { it >= 0L },
            )
        }
}

internal class PublicMetaDbApi(
    private val transport: PublicMetaDbTransport = KtorPublicMetaDbTransport(),
    private val baseUrl: String = BASE_URL,
    private val nowEpochMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val gate = Mutex()
    private var lastRequestAt = 0L

    suspend fun validateKey(apiKey: String): Boolean = try {
        request(HttpMethod.Get, "/lists", apiKey, mapOf("page" to "1", "perPage" to "1"))
        true
    } catch (error: PublicMetaDbApiException) {
        if (error.isUnauthorized) false else throw error
    }

    suspend fun resumePage(apiKey: String, page: Int, perPage: Int = PAGE_SIZE): PmdbPage<PmdbResumePoint> =
        PublicMetaDbJson.decodeFromString(
            request(HttpMethod.Get, "/resume", apiKey, mapOf("page" to "$page", "perPage" to "$perPage")),
        )

    suspend fun saveResume(
        apiKey: String,
        target: PmdbTarget,
        positionMs: Long,
        runtimeMs: Long,
    ): PmdbSaveResumeResponse {
        val body = buildJsonObject {
            put("tmdb_id", target.tmdbId)
            put("media_type", target.mediaType)
            put("season", if (target.isMovie) 0 else target.season ?: 0)
            put("episode", if (target.isMovie) 0 else target.episode ?: 0)
            put("position_ms", positionMs)
            put("runtime_ms", runtimeMs)
        }
        val text = request(HttpMethod.Post, "/resume", apiKey, body = body)
        return if (text.isBlank()) PmdbSaveResumeResponse() else PublicMetaDbJson.decodeFromString(text)
    }

    suspend fun deleteResume(apiKey: String, resumeId: String) {
        request(HttpMethod.Delete, "/resume/${encodeSegment(resumeId)}", apiKey)
    }

    suspend fun watchedPage(apiKey: String, page: Int, perPage: Int = PAGE_SIZE): PmdbPage<PmdbWatchedItem> =
        PublicMetaDbJson.decodeFromString(
            request(HttpMethod.Get, "/watched", apiKey, mapOf("page" to "$page", "perPage" to "$perPage")),
        )

    suspend fun markWatched(apiKey: String, target: PmdbTarget, watchedAtIso: String) {
        val body = buildJsonObject {
            put("tmdb_id", target.tmdbId)
            put("media_type", target.mediaType)
            if (!target.isMovie) {
                put("season", target.season ?: 0)
                put("episode", target.episode ?: 0)
            }
            put("watched_at", watchedAtIso)
        }
        request(HttpMethod.Post, "/watched", apiKey, body = body)
    }

    suspend fun unmarkWatched(apiKey: String, target: PmdbTarget) {
        val query = buildMap {
            put("tmdb_id", "${target.tmdbId}")
            put("media_type", target.mediaType)
            if (!target.isMovie) {
                target.season?.let { put("season", "$it") }
                target.episode?.let { put("episode", "$it") }
            }
        }
        request(HttpMethod.Delete, "/watched", apiKey, query)
    }

    /** Community ID mapping: external id (imdb/tvdb/mal/anilist/...) to TMDB id. Null when unknown. */
    suspend fun lookupTmdb(apiKey: String, idType: String, idValue: String): Pair<Long, String?>? {
        val text = try {
            request(HttpMethod.Get, "/mappings/lookup", apiKey, mapOf("id_type" to idType, "id_value" to idValue))
        } catch (error: PublicMetaDbApiException) {
            if (error.status == 404) return null else throw error
        }
        return findTmdbId(runCatching { PublicMetaDbJson.parseToJsonElement(text) }.getOrNull())
    }

    /** Reverse mapping used to show PublicMetaDB items under the IMDb ids addons use. */
    suspend fun imdbForTmdb(apiKey: String, tmdbId: Long, mediaType: String): String? {
        val text = try {
            request(HttpMethod.Get, "/mappings", apiKey, mapOf("tmdb_id" to "$tmdbId", "media_type" to mediaType))
        } catch (error: PublicMetaDbApiException) {
            if (error.status == 404) return null else throw error
        }
        return findImdbId(runCatching { PublicMetaDbJson.parseToJsonElement(text) }.getOrNull())
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
    ): String {
        require(apiKey.isNotBlank()) { "PublicMetaDB API key is required" }
        var attempt = 0
        var networkFailures = 0
        while (true) {
            val response = try {
                gate.withLock {
                    val wait = MIN_GAP_MS - (nowEpochMs() - lastRequestAt)
                    if (wait > 0) delay(wait)
                    lastRequestAt = nowEpochMs()
                    execute(method, path, apiKey, query, body)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Connect timeouts and resets (e.g. over a VPN) usually clear on the next attempt.
                if (networkFailures++ >= MAX_NETWORK_RETRIES) throw error
                delay(NETWORK_RETRY_BASE_MS shl (networkFailures - 1))
                continue
            }
            if (response.status in RETRY_STATUSES && attempt < MAX_RETRIES) {
                val backoff = (response.retryAfterSeconds?.times(1_000L)) ?: (1_000L shl attempt)
                if (backoff > MAX_BACKOFF_MS) {
                    throw PublicMetaDbApiException(response.status, response.body, nowEpochMs() + backoff)
                }
                delay(backoff)
                attempt++
                continue
            }
            if (response.status !in 200..299) {
                throw PublicMetaDbApiException(response.status, response.body.take(300))
            }
            return response.body
        }
    }

    private suspend fun execute(
        method: HttpMethod,
        path: String,
        apiKey: String,
        query: Map<String, String>,
        body: JsonObject?,
    ): PublicMetaDbHttpResponse {
        val url = URLBuilder(baseUrl).apply {
            encodedPath = "/api/external$path"
            query.forEach { (key, value) -> parameters.append(key, value) }
        }.buildString()
        return transport.send(method.value, url, apiKey, body?.toString())
    }

    companion object {
        const val BASE_URL = "https://publicmetadb.com"
        const val PAGE_SIZE = 100
        private const val MIN_GAP_MS = 50L
        private const val MAX_RETRIES = 3
        private const val MAX_NETWORK_RETRIES = 3
        private const val NETWORK_RETRY_BASE_MS = 500L
        private const val MAX_BACKOFF_MS = 15_000L
        private val RETRY_STATUSES = setOf(429, 500, 502, 503)

        private fun encodeSegment(value: String): String =
            value.map { char -> if (char.isLetterOrDigit() || char in "-._~") "$char" else "%" + char.code.toString(16).padStart(2, '0').uppercase() }
                .joinToString("")
    }
}

/** Finds a TMDB id (and media type if present) anywhere in a mapping payload of loose shape. */
internal fun findTmdbId(element: JsonElement?, depth: Int = 0): Pair<Long, String?>? {
    if (element == null || element is JsonNull || depth > 4) return null
    return when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findTmdbId(it, depth + 1) }
        is JsonObject -> {
            element.entries.firstNotNullOfOrNull { (key, value) ->
                if (key.equals("tmdb_id", true) || key.equals("themoviedb_id", true)) {
                    (value as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let { id ->
                        val type = (element["media_type"] ?: element["mediaType"]).let { (it as? JsonPrimitive)?.contentOrNull }
                        id to type?.takeIf { it == PMDB_MEDIA_MOVIE || it == PMDB_MEDIA_TV }
                    }
                } else {
                    null
                }
            } ?: element.values.firstNotNullOfOrNull { findTmdbId(it, depth + 1) }
        }
        else -> null
    }
}

/** Finds an IMDb id (`tt1234567`) anywhere in a mapping payload of loose shape. */
internal fun findImdbId(element: JsonElement?, depth: Int = 0): String? {
    if (element == null || element is JsonNull || depth > 4) return null
    return when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findImdbId(it, depth + 1) }
        is JsonObject -> {
            val idType = (element["id_type"] as? JsonPrimitive)?.contentOrNull
            val paired = if (idType?.contains("imdb", ignoreCase = true) == true) {
                (element["id_value"] as? JsonPrimitive)?.contentOrNull?.takeIf(::isImdbId)
            } else {
                null
            }
            paired
                ?: element.entries.firstNotNullOfOrNull { (key, value) ->
                    if (key.contains("imdb", ignoreCase = true)) (value as? JsonPrimitive)?.contentOrNull?.takeIf(::isImdbId) else null
                }
                ?: element.values.firstNotNullOfOrNull { findImdbId(it, depth + 1) }
        }
        else -> null
    }
}

internal fun isImdbId(value: String): Boolean = value.length > 2 && value.startsWith("tt") && value.drop(2).all(Char::isDigit)
