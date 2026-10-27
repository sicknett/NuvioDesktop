package com.nuvio.app.features.pmdb

import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Durable key/value storage for PublicMetaDB state. Platforms install an implementation at startup. */
interface PublicMetaDbPersistence {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

internal object InMemoryPublicMetaDbPersistence : PublicMetaDbPersistence {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = synchronized(values) { values[key] }
    override fun write(key: String, value: String?) = synchronized(values) {
        if (value == null) values.remove(key) else values[key] = value
        Unit
    }
}

/**
 * Caps the network lookups one sync may spend. TMDB (cheap, generous limits) and PublicMetaDB's own
 * mapping endpoint (metered per hour) have separate allowances, so a big import can't burn the quota.
 */
internal class LookupBudget(tmdb: Int, pmdb: Int) {
    private val lock = SynchronizedObject()
    private var tmdbLeft = tmdb
    private var pmdbLeft = pmdb

    fun takeTmdb(): Boolean = synchronized(lock) { if (tmdbLeft > 0) { tmdbLeft--; true } else false }
    fun takePmdb(): Boolean = synchronized(lock) { if (pmdbLeft > 0) { pmdbLeft--; true } else false }
    fun exhaustPmdb() = synchronized(lock) { pmdbLeft = 0 }
    fun exhaustTmdb() = synchronized(lock) { tmdbLeft = 0 }
}

/** TMDB-backed id conversion, preferred over PublicMetaDB's metered mapping endpoint. */
internal interface TmdbIdResolver {
    suspend fun imdbForTmdb(tmdbId: Long, mediaType: String): String?
    suspend fun tmdbForImdb(imdbId: String, mediaType: String): Long?
}

internal object NuvioTmdbIdResolver : TmdbIdResolver {
    override suspend fun imdbForTmdb(tmdbId: Long, mediaType: String): String? =
        tmdbId.takeIf { it in 1..Int.MAX_VALUE }?.let { TmdbService.tmdbToImdb(it.toInt(), mediaType) }

    override suspend fun tmdbForImdb(imdbId: String, mediaType: String): Long? =
        TmdbService.ensureTmdbId(videoId = imdbId, mediaType = mediaType)?.toLongOrNull()?.takeIf { it > 0L }
}

internal object NoTmdbIdResolver : TmdbIdResolver {
    override suspend fun imdbForTmdb(tmdbId: Long, mediaType: String): String? = null
    override suspend fun tmdbForImdb(imdbId: String, mediaType: String): Long? = null
}

object PublicMetaDbPlatform {
    /** Desktop installs file-backed storage at startup; other targets fall back to memory only. */
    var persistence: PublicMetaDbPersistence = InMemoryPublicMetaDbPersistence
}

/**
 * Resolves Nuvio's ids (IMDb, TMDB, TVDB, MAL, AniList) to PublicMetaDB targets and back.
 * Mappings rarely change, so both directions are cached and persisted. TMDB is asked first;
 * PublicMetaDB's community mapping is the fallback (and the only source for TVDB/MAL/AniList ids).
 */
internal class PublicMetaDbIds(
    private val api: PublicMetaDbApi,
    private val tmdb: TmdbIdResolver = NuvioTmdbIdResolver,
    private val persistence: () -> PublicMetaDbPersistence = { PublicMetaDbPlatform.persistence },
) {
    private val mutex = Mutex()
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private var loaded = false
    private var dirty = 0

    /** "imdb:tt0133093" -> "603|movie"; "tmdb:movie:603" -> "tt0133093". */
    private val cache = mutableMapOf<String, String>()
    private val misses = mutableSetOf<String>()

    suspend fun resolve(apiKey: String, media: TrackingMediaReference, requireEpisode: Boolean = true): PmdbTarget? {
        val isMovie = media.kind == TrackingMediaKind.MOVIE
        val episode = media.episode
        if (!isMovie && requireEpisode && episode == null) return null
        val base = resolveTitle(apiKey, media, isMovie) ?: return null
        val type = base.second ?: if (isMovie) PMDB_MEDIA_MOVIE else PMDB_MEDIA_TV
        return if (type == PMDB_MEDIA_MOVIE) {
            PmdbTarget(base.first, PMDB_MEDIA_MOVIE)
        } else {
            PmdbTarget(base.first, PMDB_MEDIA_TV, season = episode?.season ?: 1.takeIf { episode != null }, episode = episode?.number)
        }
    }

    private suspend fun resolveTitle(apiKey: String, media: TrackingMediaReference, isMovie: Boolean): Pair<Long, String?>? {
        val ids = media.ids
        ids.tmdb?.takeIf { it > 0 }?.let { return it to null }
        val typeHint = if (isMovie) PMDB_MEDIA_MOVIE else PMDB_MEDIA_TV
        ids.imdb?.takeIf(::isImdbId)?.let { imdb ->
            lookupImdb(apiKey, imdb, typeHint)?.let { return it }
        }
        val others = buildList {
            ids.tvdb?.takeIf { it.isNotBlank() }?.let { add("tvdb" to it) }
            ids.mal?.let { add("mal" to "$it") }
            ids.anilist?.let { add("anilist" to "$it") }
        }
        for ((idType, idValue) in others) {
            lookupViaPublicMetaDb(apiKey, idType, idValue)?.let { return it }
        }
        return null
    }

    /** IMDb to TMDB: our cache, then TMDB, then PublicMetaDB's mapping. */
    private suspend fun lookupImdb(apiKey: String, imdb: String, typeHint: String): Pair<Long, String?>? {
        decode(cached("imdb:$imdb"))?.let { return it }
        tmdb.tmdbForImdb(imdb, typeHint)?.let { id ->
            remember("imdb:$imdb", "$id|$typeHint")
            remember("tmdb:$typeHint:$id", imdb)
            return id to typeHint
        }
        return lookupViaPublicMetaDb(apiKey, "imdb", imdb)
    }

    private suspend fun lookupViaPublicMetaDb(apiKey: String, idType: String, idValue: String): Pair<Long, String?>? {
        val key = "$idType:$idValue"
        decode(cached(key))?.let { return it }
        if (isMiss(key)) return null
        val found = api.lookupTmdb(apiKey, idType, idValue)
        if (found == null) {
            markMiss(key)
            return null
        }
        remember(key, "${found.first}|${found.second.orEmpty()}")
        if (idType == "imdb") found.second?.let { remember("tmdb:$it:${found.first}", idValue) }
        return found
    }

    /**
     * IMDb id for a TMDB title. Served from cache; otherwise TMDB is asked while [budget] allows,
     * then PublicMetaDB's mapping. With a null [budget] nothing hits the network.
     */
    suspend fun imdbFor(apiKey: String, tmdbId: Long, mediaType: String, budget: LookupBudget?): String? {
        val key = "tmdb:$mediaType:$tmdbId"
        cached(key)?.takeIf(::isImdbId)?.let { return it }
        if (budget == null || isMiss(key)) return null
        var imdb: String? = null
        var answered = false
        if (budget.takeTmdb()) {
            imdb = runCatching { tmdb.imdbForTmdb(tmdbId, mediaType) }.getOrNull()?.takeIf(::isImdbId)
        }
        if (imdb == null && budget.takePmdb()) {
            imdb = try {
                api.imdbForTmdb(apiKey, tmdbId, mediaType).also { answered = true }
            } catch (error: PublicMetaDbApiException) {
                if (error.status == 429 || error.isUnauthorized) budget.exhaustPmdb()
                return null
            }
        }
        if (imdb == null) {
            if (answered) markMiss(key)  // PublicMetaDB answered and has no mapping: don't ask again this run
            return null
        }
        remember(key, imdb)
        remember("imdb:$imdb", "$tmdbId|$mediaType")
        return imdb
    }

    /** Resolves many titles up front (bounded concurrency) so mapping them afterwards is cache-only. */
    suspend fun prefetchImdb(apiKey: String, titles: Collection<Pair<Long, String>>, budget: LookupBudget) {
        val gate = Semaphore(PREFETCH_PARALLELISM)
        coroutineScope {
            titles.distinct().map { (tmdbId, type) ->
                async { gate.withPermit { imdbFor(apiKey, tmdbId, type, budget) } }
            }.awaitAll()
        }
        flush()
    }

    suspend fun flush() = mutex.withLock { persistLocked() }

    suspend fun clear() = mutex.withLock {
        cache.clear()
        misses.clear()
        loaded = true
        dirty = 0
        persistence().write(STORAGE_KEY, null)
    }

    private fun decode(entry: String?): Pair<Long, String?>? {
        entry ?: return null
        val parts = entry.split('|')
        val id = parts[0].toLongOrNull() ?: return null
        return id to parts.getOrNull(1)?.takeIf { it == PMDB_MEDIA_MOVIE || it == PMDB_MEDIA_TV }
    }

    private suspend fun cached(key: String): String? = mutex.withLock {
        loadLocked()
        cache[key]
    }

    private suspend fun isMiss(key: String): Boolean = mutex.withLock { key in misses }

    private suspend fun markMiss(key: String) = mutex.withLock { misses += key; Unit }

    private suspend fun remember(key: String, value: String) = mutex.withLock {
        loadLocked()
        cache[key] = value
        dirty++
        if (dirty >= PERSIST_EVERY) persistLocked()
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        persistence().read(STORAGE_KEY)?.let { raw ->
            runCatching { PublicMetaDbJson.decodeFromString(mapSerializer, raw) }.getOrNull()?.let(cache::putAll)
        }
    }

    private fun persistLocked() {
        if (!loaded || dirty == 0) return
        if (cache.size > MAX_ENTRIES) {
            cache.keys.take(cache.size - MAX_ENTRIES).forEach(cache::remove)
        }
        persistence().write(STORAGE_KEY, PublicMetaDbJson.encodeToString(mapSerializer, cache))
        dirty = 0
    }

    private companion object {
        const val STORAGE_KEY = "pmdb_id_mappings_v1"
        const val PERSIST_EVERY = 50
        const val MAX_ENTRIES = 12_000
        const val PREFETCH_PARALLELISM = 8
    }
}
