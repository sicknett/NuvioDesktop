package com.nuvio.app.features.pmdb

import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Call(val method: String, val url: String, val apiKey: String, val body: JsonObject?) {
    val path: String get() = url.substringAfter("/api/external").substringBefore('?')
    val query: String get() = url.substringAfter('?', "")
}

private class MemoryPersistence : PublicMetaDbPersistence {
    private val values = mutableMapOf<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

private class FakeTmdb(
    private val imdbByTmdb: Map<Long, String> = emptyMap(),
    private val tmdbByImdb: Map<String, Long> = emptyMap(),
) : TmdbIdResolver {
    var requests = 0
    override suspend fun imdbForTmdb(tmdbId: Long, mediaType: String): String? { requests++; return imdbByTmdb[tmdbId] }
    override suspend fun tmdbForImdb(imdbId: String, mediaType: String): Long? { requests++; return tmdbByImdb[imdbId] }
}

private class Harness(
    tmdb: TmdbIdResolver = NoTmdbIdResolver,
    private val handler: (Call) -> PublicMetaDbHttpResponse,
) {
    val calls = mutableListOf<Call>()
    val api = PublicMetaDbApi(
        transport = { method, url, apiKey, body ->
            val call = Call(method, url, apiKey, body?.let { PublicMetaDbJson.parseToJsonElement(it).jsonObject })
            calls += call
            handler(call)
        },
        nowEpochMs = { 1_000_000L },
    )
    private val storage = MemoryPersistence()
    val ids = PublicMetaDbIds(api, tmdb) { storage }
    val watched = PublicMetaDbWatchedProvider(api, ids, apiKey = { KEY }, nowEpochMs = { 1_000_000L })
    val progress = PublicMetaDbProgressProvider(api, ids, apiKey = { KEY }, ensureAccountLoaded = {}, nowEpochMs = { 1_000_000L })
    val writes = PublicMetaDbTrackingWrites(api, ids, progress, apiKey = { KEY }, nowEpochMs = { 1_000_000L })

    fun calls(method: String, path: String) = calls.filter { it.method == method && it.path == path }

    companion object { const val KEY = "test-key" }
}

private fun ok(body: String = "{}") = PublicMetaDbHttpResponse(200, body, null)
private fun status(code: Int, retryAfter: Long? = null) = PublicMetaDbHttpResponse(code, "", retryAfter)

private val movie = TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(tmdb = 603))
private fun episode(season: Int, number: Int, ids: TrackingExternalIds) = TrackingMediaReference(
    TrackingMediaKind.SHOW,
    ids = ids,
    episode = TrackingEpisode(season = season, number = number),
)

class PublicMetaDbTest {
    @Test
    fun validateKeyAcceptsOkAndRejectsUnauthorized() = runTest {
        assertTrue(Harness { ok("""{"items":[]}""") }.api.validateKey("k"))
        assertFalse(Harness { status(401) }.api.validateKey("k"))
    }

    @Test
    fun requestsCarryBearerKeyAndExternalApiPath() = runTest {
        val h = Harness { ok("""{"items":[]}""") }
        h.api.resumePage("abc", 2)
        val call = h.calls.single()
        assertEquals("abc", call.apiKey)
        assertEquals("/resume", call.path)
        assertTrue("page=2" in call.query && "perPage=100" in call.query)
        assertTrue(call.url.startsWith("https://publicmetadb.com/api/external/"))
    }

    @Test
    fun pauseBelowCompletionSavesAbsoluteResumePoint() = runTest {
        val h = Harness { ok("""{"action":"saved"}""") }
        h.writes.scrobble(1, TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(movie, 40.0, durationMs = 7_200_000L))
        val body = h.calls("POST", "/resume").single().body!!
        assertEquals(603L, body["tmdb_id"]!!.jsonPrimitive.long)
        assertEquals("movie", body["media_type"]!!.jsonPrimitive.content)
        assertEquals(2_880_000L, body["position_ms"]!!.jsonPrimitive.long)
        assertEquals(7_200_000L, body["runtime_ms"]!!.jsonPrimitive.long)
        assertEquals(0, body["season"]!!.jsonPrimitive.int)
    }

    @Test
    fun startAndTinyProgressAndUnknownDurationAreNotSaved() = runTest {
        val h = Harness { ok() }
        h.writes.scrobble(1, TrackingScrobbleAction.START, TrackingScrobbleEvent(movie, 40.0, durationMs = 7_200_000L))
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 1.0, durationMs = 7_200_000L))
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 40.0, durationMs = null))
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun repeatedPausesInsideTheCooldownSaveOnce() = runTest {
        val h = Harness { ok("""{"action":"saved"}""") }
        val event = TrackingScrobbleEvent(movie, 30.0, durationMs = 6_000_000L)
        h.writes.scrobble(1, TrackingScrobbleAction.PAUSE, event)
        h.writes.scrobble(1, TrackingScrobbleAction.PAUSE, event)
        assertEquals(1, h.calls("POST", "/resume").size)
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, event)
        assertEquals(2, h.calls("POST", "/resume").size)
    }

    @Test
    fun stopAtCompletionMarksWatchedAndClearsTheResumePoint() = runTest {
        val h = Harness { call ->
            when {
                call.method == "GET" && call.path == "/resume" -> ok(
                    """{"items":[{"id":"r1","tmdb_id":603,"media_type":"movie","position_ms":10,"runtime_ms":100}],"totalPages":1}""",
                )
                else -> ok()
            }
        }
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 95.0, durationMs = 7_200_000L))
        val watched = h.calls("POST", "/watched").single().body!!
        assertEquals(603L, watched["tmdb_id"]!!.jsonPrimitive.long)
        assertFalse("season" in watched)
        assertEquals(1, h.calls("DELETE", "/resume/r1").size)
        assertTrue(h.calls("POST", "/resume").isEmpty())
    }

    @Test
    fun completedResponseFromTheServerAlsoMarksWatched() = runTest {
        val h = Harness { call ->
            when {
                call.method == "POST" && call.path == "/resume" -> ok("""{"action":"completed"}""")
                call.method == "GET" -> ok("""{"items":[],"totalPages":1}""")
                else -> ok()
            }
        }
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 60.0, durationMs = 1_000_000L))
        assertEquals(1, h.calls("POST", "/watched").size)
    }

    @Test
    fun conflictReplacesTheExistingResumePointOnce() = runTest {
        var posts = 0
        val h = Harness { call ->
            when {
                call.method == "POST" && call.path == "/resume" -> if (posts++ == 0) status(409) else ok("""{"action":"saved"}""")
                call.method == "GET" && call.path == "/resume" -> ok(
                    """{"items":[{"id":"old","tmdb_id":603,"media_type":"movie","position_ms":5,"runtime_ms":10}],"totalPages":1}""",
                )
                else -> ok()
            }
        }
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(movie, 50.0, durationMs = 1_000_000L))
        assertEquals(2, h.calls("POST", "/resume").size)
        assertEquals(1, h.calls("DELETE", "/resume/old").size)
    }

    @Test
    fun episodeScrobbleResolvesImdbThroughTheMappingLookup() = runTest {
        val h = Harness { call ->
            when (call.path) {
                "/mappings/lookup" -> ok("""{"tmdb_id":1399,"media_type":"tv"}""")
                else -> ok("""{"action":"saved"}""")
            }
        }
        val media = episode(1, 2, TrackingExternalIds(imdb = "tt0944947"))
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(media, 25.0, durationMs = 3_000_000L))
        val lookup = h.calls("GET", "/mappings/lookup").single()
        assertTrue("id_type=imdb" in lookup.query && "id_value=tt0944947" in lookup.query)
        val body = h.calls("POST", "/resume").single().body!!
        assertEquals(1399L, body["tmdb_id"]!!.jsonPrimitive.long)
        assertEquals("tv", body["media_type"]!!.jsonPrimitive.content)
        assertEquals(1, body["season"]!!.jsonPrimitive.int)
        assertEquals(2, body["episode"]!!.jsonPrimitive.int)
        // second scrobble for the same title is served from the mapping cache
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(media, 26.0, durationMs = 3_000_000L))
        assertEquals(1, h.calls("GET", "/mappings/lookup").size)
    }

    @Test
    fun showWithoutEpisodeIsNotResolvable() = runTest {
        val h = Harness { ok() }
        val show = TrackingMediaReference(TrackingMediaKind.SHOW, ids = TrackingExternalIds(tmdb = 1399))
        assertNull(h.ids.resolve(Harness.KEY, show))
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun historyAddAndRemoveUseTheWatchedEndpoint() = runTest {
        val h = Harness { ok() }
        val result = h.writes.addToHistory(1, listOf(TrackingHistoryItem(episode(2, 5, TrackingExternalIds(tmdb = 1399)), watchedAtEpochMs = 0L)))
        assertEquals(0, result.notFoundCount)
        val added = h.calls("POST", "/watched").single().body!!
        assertEquals(2, added["season"]!!.jsonPrimitive.int)
        assertEquals(5, added["episode"]!!.jsonPrimitive.int)
        assertEquals("1970-01-01T00:00:00Z", added["watched_at"]!!.jsonPrimitive.content)

        h.writes.removeFromHistory(1, listOf(episode(2, 5, TrackingExternalIds(tmdb = 1399))))
        val removal = h.calls("DELETE", "/watched").single()
        assertTrue("tmdb_id=1399" in removal.query && "season=2" in removal.query && "episode=5" in removal.query)
    }

    @Test
    fun unresolvableHistoryItemsAreReportedNotThrown() = runTest {
        val h = Harness { status(404) }
        val unknown = TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt9999999"))
        val result = h.writes.addToHistory(1, listOf(TrackingHistoryItem(unknown)))
        assertEquals(1, result.notFoundCount)
    }

    @Test
    fun rateLimitedRequestsRetryAfterTheServerDelay() = runTest {
        var attempts = 0
        val h = Harness { if (attempts++ == 0) status(429, retryAfter = 1) else ok("""{"items":[]}""") }
        h.api.resumePage("k", 1)
        assertEquals(2, h.calls.size)
    }

    @Test
    fun unauthorizedResponsesSurfaceAsExceptions() = runTest {
        val h = Harness { status(401) }
        val failure = runCatching { h.api.resumePage("k", 1) }.exceptionOrNull() as PublicMetaDbApiException
        assertTrue(failure.isUnauthorized)
    }

    @Test
    fun resumePointsBecomeContinueWatchingEntries() = runTest {
        val h = Harness { call ->
            when {
                call.path == "/resume" -> ok(
                    """{"items":[
                        {"id":"r9","tmdb_id":603,"media_type":"movie","position_ms":3600000,"runtime_ms":7200000,"title":"The Matrix","updated":"2026-10-20T12:00:00Z"},
                        {"id":"r10","tmdb_id":1399,"media_type":"tv","season":1,"episode":3,"position_ms":600000,"runtime_ms":3000000,"title":"Game of Thrones"},
                        {"id":"done","tmdb_id":1,"media_type":"movie","position_ms":95,"runtime_ms":100}
                       ],"totalPages":1}""",
                )
                call.path == "/mappings" && "tmdb_id=603" in call.query -> ok("""{"imdb_id":"tt0133093"}""")
                else -> status(404)
            }
        }
        h.progress.refresh(force = true, sourceChanged = false)
        val entries = h.progress.snapshot().entries
        assertEquals(2, entries.size)
        val matrix = entries.first { it.trackingProviderItemId == "r9" }
        assertEquals("tt0133093", matrix.parentMetaId)
        assertEquals("movie", matrix.contentType)
        assertEquals(3_600_000L, matrix.lastPositionMs)
        assertEquals(7_200_000L, matrix.durationMs)
        assertEquals(50f, matrix.progressPercent)
        assertEquals("The Matrix", matrix.title)
        val show = entries.first { it.trackingProviderItemId == "r10" }
        assertEquals("tmdb:1399", show.parentMetaId)
        assertEquals("tmdb:1399:1:3", show.videoId)
        assertEquals(1, show.seasonNumber)
        assertEquals(3, show.episodeNumber)
        assertTrue(h.progress.snapshot().hasLoadedRemoteProgress)
    }

    @Test
    fun removingProgressDeletesTheRemoteResumePoint() = runTest {
        val h = Harness { call ->
            if (call.path == "/resume" && call.method == "GET") {
                ok("""{"items":[{"id":"r9","tmdb_id":603,"media_type":"movie","position_ms":10,"runtime_ms":100}],"totalPages":1}""")
            } else {
                status(404)
            }
        }
        h.progress.refresh(force = true, sourceChanged = false)
        val entry = h.progress.snapshot().entries.single()
        h.progress.removeProgress(listOf(entry))
        assertEquals(1, h.calls("DELETE", "/resume/r9").size)
        assertTrue(h.progress.snapshot().entries.isEmpty())
    }

    @Test
    fun syncErrorsAreReportedWithoutDroppingLoadedProgress() = runTest {
        var fail = false
        val h = Harness {
            if (fail) status(500) else ok("""{"items":[{"id":"r1","tmdb_id":603,"media_type":"movie","position_ms":10,"runtime_ms":100}],"totalPages":1}""")
        }
        h.progress.refresh(force = true, sourceChanged = false)
        fail = true
        h.progress.refresh(force = true, sourceChanged = false)
        val snapshot = h.progress.snapshot()
        assertEquals(1, snapshot.entries.size)
        assertTrue(snapshot.errorMessage != null)
    }

    @Test
    fun tmdbResolvesImdbBeforePublicMetaDbIsAsked() = runTest {
        val tmdb = FakeTmdb(tmdbByImdb = mapOf("tt0944947" to 1399L))
        val h = Harness(tmdb) { ok("""{"action":"saved"}""") }
        val media = episode(1, 2, TrackingExternalIds(imdb = "tt0944947"))
        h.writes.scrobble(1, TrackingScrobbleAction.STOP, TrackingScrobbleEvent(media, 25.0, durationMs = 3_000_000L))
        assertEquals(1399L, h.calls("POST", "/resume").single().body!!["tmdb_id"]!!.jsonPrimitive.long)
        assertTrue(h.calls("GET", "/mappings/lookup").isEmpty())
        assertEquals(1, tmdb.requests)
    }

    @Test
    fun resumeEntriesGetImdbIdsFromTmdbWithoutSpendingPublicMetaDbRequests() = runTest {
        val tmdb = FakeTmdb(imdbByTmdb = mapOf(603L to "tt0133093", 1399L to "tt0944947"))
        val h = Harness(tmdb) { call ->
            if (call.path == "/resume") {
                ok(
                    """{"items":[
                        {"id":"a","tmdb_id":603,"media_type":"movie","position_ms":10,"runtime_ms":100},
                        {"id":"b","tmdb_id":1399,"media_type":"tv","season":2,"episode":4,"position_ms":10,"runtime_ms":100}
                       ],"totalPages":1}""",
                )
            } else {
                status(404)
            }
        }
        h.progress.refresh(force = true, sourceChanged = false)
        val entries = h.progress.snapshot().entries
        assertEquals(setOf("tt0133093", "tt0944947"), entries.map { it.parentMetaId }.toSet())
        assertEquals("tt0944947:2:4", entries.first { it.trackingProviderItemId == "b" }.videoId)
        assertTrue(h.calls("GET", "/mappings").isEmpty())
    }

    @Test
    fun watchedHistoryImportsAllPagesWithImdbIdsAndKeepsSpecials() = runTest {
        val tmdb = FakeTmdb(imdbByTmdb = mapOf(603L to "tt0133093", 1399L to "tt0944947"))
        val h = Harness(tmdb) { call ->
            when {
                call.path == "/watched" && "page=1&" in call.query -> ok(
                    """{"items":[
                        {"id":"w1","tmdb_id":603,"media_type":"movie","watched_at":"2026-10-01T10:00:00Z"},
                        {"id":"w2","tmdb_id":1399,"media_type":"tv","season":1,"episode":1,"watched_at":"2026-10-02T10:00:00Z"}
                       ],"page":1,"totalPages":2}""",
                )
                call.path == "/watched" && "page=2&" in call.query -> ok(
                    """{"items":[
                        {"id":"w3","tmdb_id":1399,"media_type":"tv","season":0,"episode":1},
                        {"id":"w4","tmdb_id":777,"media_type":"movie"}
                       ],"page":2,"totalPages":2}""",
                )
                else -> status(404)
            }
        }
        val items = h.watched.pull(profileId = 1, pageSize = 100)
        assertEquals(4, items.size)
        assertEquals(2, h.calls("GET", "/watched").size)
        val movie = items.first { it.trackingProviderItemId == "w1" }
        assertEquals("tt0133093", movie.id)
        assertEquals("movie", movie.type)
        assertNull(movie.season)
        val special = items.first { it.trackingProviderItemId == "w3" }
        assertEquals("tt0944947", special.id)
        assertEquals(0, special.season)
        assertEquals("tt0944947:0:1", special.videoId)
        assertEquals("series", items.first { it.trackingProviderItemId == "w2" }.type)
        // 777 has no known IMDb id yet: it keeps a TMDB-based id instead of being dropped
        assertEquals("tmdb:777", items.first { it.trackingProviderItemId == "w4" }.id)
    }

    @Test
    fun publicMetaDbMappingLookupsStopAfterARateLimit() = runTest {
        val h = Harness { call ->
            when (call.path) {
                "/watched" -> ok(
                    "{\"items\":[" + (1..40).joinToString(",") { """{"id":"w$it","tmdb_id":$it,"media_type":"movie"}""" } + "],\"totalPages\":1}",
                )
                "/mappings" -> status(429, retryAfter = 3600)
                else -> status(404)
            }
        }
        val items = h.watched.pull(profileId = 1, pageSize = 100)
        assertEquals(40, items.size)
        assertTrue(h.calls("GET", "/mappings").size <= 20)
    }

    @Test
    fun watchedPushMarksEachItemWatchedAndDeleteUnmarks() = runTest {
        val h = Harness { ok() }
        val item = com.nuvio.app.features.watched.WatchedItem(
            id = "tmdb:603",
            type = "movie",
            name = "The Matrix",
            markedAtEpochMs = 0L,
        )
        h.watched.push(1, listOf(item))
        assertEquals(603L, h.calls("POST", "/watched").single().body!!["tmdb_id"]!!.jsonPrimitive.long)
        h.watched.delete(1, listOf(item))
        assertTrue("tmdb_id=603" in h.calls("DELETE", "/watched").single().query)
    }

    @Test
    fun timestampsParseInEveryFormatPublicMetaDbMaySend() {
        val expected = kotlin.time.Instant.parse("2026-10-20T12:00:00Z").toEpochMilliseconds()
        assertEquals(expected, parsePmdbTimestamp("2026-10-20T12:00:00Z"))
        assertEquals(expected, parsePmdbTimestamp("2026-10-20 12:00:00Z"))
        assertEquals(expected, parsePmdbTimestamp("2026-10-20 12:00:00"))
        assertEquals(expected, parsePmdbTimestamp("2026-10-20T12:00:00"))
        assertEquals(expected + 123, parsePmdbTimestamp("2026-10-20 12:00:00.123Z"))
        assertEquals(expected - 2 * 3_600_000L, parsePmdbTimestamp("2026-10-20T12:00:00+02:00"))
        assertEquals(expected, parsePmdbTimestamp((expected / 1000).toString()))
        assertEquals(expected, parsePmdbTimestamp(expected.toString()))
        assertEquals(kotlin.time.Instant.parse("2026-10-20T00:00:00Z").toEpochMilliseconds(), parsePmdbTimestamp("2026-10-20"))
        assertNull(parsePmdbTimestamp(null))
        assertNull(parsePmdbTimestamp(""))
        assertNull(parsePmdbTimestamp("yesterday"))
    }

    @Test
    fun continueWatchingOrderFollowsTheTimestampsEvenWithSpaceSeparatedDates() = runTest {
        val h = Harness { call ->
            if (call.path == "/resume") {
                ok(
                    """{"items":[
                        {"id":"old","tmdb_id":1,"media_type":"movie","position_ms":10,"runtime_ms":100,"updated":"2026-10-01 08:00:00.000Z"},
                        {"id":"new","tmdb_id":2,"media_type":"movie","position_ms":10,"runtime_ms":100,"updated":"2026-10-20 08:00:00.000Z"},
                        {"id":"mid","tmdb_id":3,"media_type":"movie","position_ms":10,"runtime_ms":100,"updated":"2026-10-10 08:00:00.000Z"}
                       ],"totalPages":1}""",
                )
            } else {
                status(404)
            }
        }
        h.progress.refresh(force = true, sourceChanged = false)
        val ordered = h.progress.snapshot().entries.sortedByDescending { it.lastUpdatedEpochMs }.map { it.trackingProviderItemId }
        assertEquals(listOf("new", "mid", "old"), ordered)
    }

    @Test
    fun missingTimestampsKeepTheServerListOrder() = runTest {
        val h = Harness { call ->
            when (call.path) {
                "/resume" -> ok(
                    """{"items":[
                        {"id":"first","tmdb_id":1,"media_type":"movie","position_ms":10,"runtime_ms":100},
                        {"id":"second","tmdb_id":2,"media_type":"movie","position_ms":10,"runtime_ms":100}
                       ],"totalPages":1}""",
                )
                "/watched" -> ok(
                    """{"items":[
                        {"id":"w1","tmdb_id":5,"media_type":"movie"},
                        {"id":"w2","tmdb_id":6,"media_type":"movie"}
                       ],"totalPages":1}""",
                )
                else -> status(404)
            }
        }
        h.progress.refresh(force = true, sourceChanged = false)
        val progress = h.progress.snapshot().entries.associate { it.trackingProviderItemId to it.lastUpdatedEpochMs }
        assertTrue(progress.getValue("first") > progress.getValue("second"))
        val watched = h.watched.pull(1, 100).associate { it.trackingProviderItemId to it.markedAtEpochMs }
        assertTrue(watched.getValue("w1") > watched.getValue("w2"))
    }

    @Test
    fun mappingPayloadParsersFindNestedIds() {
        val json = PublicMetaDbJson.parseToJsonElement("""{"data":[{"x":1},{"mapping":{"tmdb_id":550,"media_type":"movie"}}]}""")
        assertEquals(550L to "movie", findTmdbId(json))
        val imdb = PublicMetaDbJson.parseToJsonElement("""{"mappings":[{"id_type":"tvdb","id_value":"1"},{"id_type":"imdb","id_value":"tt0137523"}]}""")
        assertEquals("tt0137523", findImdbId(imdb))
        assertNull(findImdbId(PublicMetaDbJson.parseToJsonElement("""{"imdb_id":"nope"}""")))
        assertTrue(isImdbId("tt0137523") && !isImdbId("tt") && !isImdbId("12345"))
    }
}
