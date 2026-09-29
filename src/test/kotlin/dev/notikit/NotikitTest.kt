package dev.notikit

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeTransport(private val status: Int, private val body: String) : HttpTransport {
    var lastUrl: String? = null
    var lastHeaders: Map<String, String>? = null
    var lastBody: String? = null
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        lastUrl = url; lastHeaders = headers; lastBody = body
        return HttpResponse(status, this.body)
    }
}

/** 경로별로 응답을 정하고 보낸 요청을 순서대로 기록한다 */
class RoutingTransport(private val route: (path: String) -> HttpResponse) : HttpTransport {
    val calls = mutableListOf<Pair<String, JSONObject>>()
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val path = url.substringAfter("https://push.test")
        calls.add(path to JSONObject(body))
        return route(path)
    }
    fun paths() = calls.map { it.first }
}

private fun okJson(data: String = "{}") = HttpResponse(200, """{"success":true,"data":$data,"error":null}""")
private fun failJson(status: Int) = HttpResponse(status, """{"success":false,"data":null,"error":"x"}""")

class NotikitTest {
    @Test
    fun registerDeviceSendsApiKeyAndPayload() = runTest {
        val fake = FakeTransport(201, """{"success":true,"data":{"device":{}},"error":null}""")
        val notikit = Notikit("https://push.test/", "nk_test", transport = fake)

        notikit.registerDevice(token = "t1", platform = "android", userId = "u1", identityHash = "h")

        assertEquals("https://push.test/api/v1/devices", fake.lastUrl)
        assertEquals("nk_test", fake.lastHeaders?.get("api-key"))
        val body = JSONObject(fake.lastBody!!)
        assertEquals("u1", body.getString("user_id"))
        assertFalse(body.has("external_id"))
        assertEquals("h", body.getString("identity_hash"))
    }

    @Test
    fun omitsApiSecretWhenNotProvided() = runTest {
        val fake = FakeTransport(200, """{"success":true,"data":{},"error":null}""")
        val notikit = Notikit("https://push.test", "nk", transport = fake)
        notikit.subscribe("news", "t1")
        assertFalse(fake.lastHeaders?.containsKey("api-secret") ?: false)
    }

    @Test
    fun throwsOnFailure() = runTest {
        val fake = FakeTransport(401, """{"success":false,"data":null,"error":"Unauthorized"}""")
        val notikit = Notikit("https://push.test", "nk", transport = fake)
        assertFailsWith<NotikitException> { notikit.identify("u1") }
    }

    @Test
    fun customDataSkipsNotikitAndFcmKeys() {
        val payload = mapOf(
            "notikit_log_id" to "log1",
            "deep_link" to "myapp://orders",
            "google.message_id" to "x",
            "gcm.n.e" to "1",
            "from" to "123",
            "actions" to """[{"id":"a","title":"A"}]""",
            "order_id" to "A-1",
            "screen" to "order",
        )
        assertEquals(mapOf("order_id" to "A-1", "screen" to "order"), Notikit.customDataFromPayload(payload))
        assertEquals("myapp://orders", Notikit.deepLinkFromPayload(payload))
        assertEquals(emptyMap(), Notikit.customDataFromPayload(null))
    }

    @Test
    fun identifySendsName() = runTest {
        val fake = FakeTransport(200, """{"success":true,"data":{"user":{}},"error":null}""")
        Notikit("https://push.test", "nk", transport = fake).identify("u1", name = "김민지")
        assertEquals("김민지", JSONObject(fake.lastBody!!).getString("name"))
    }

    @Test
    fun registerDevicePositionalUserIdSendsUserId() = runTest {
        val fake = ok()
        Notikit("https://push.test", "nk", transport = fake).registerDevice("t1", "android", "u1", "h")
        val body = JSONObject(fake.lastBody!!)
        assertEquals("u1", body.getString("user_id"))
        assertEquals("h", body.getString("identity_hash"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyExternalIdNamedArgStillSendsUserId() = runTest {
        val fake = ok()
        val notikit = Notikit("https://push.test", "nk", transport = fake)

        notikit.registerDevice(token = "t1", platform = "android", externalId = "u1", identityHash = "h")
        val reg = JSONObject(fake.lastBody!!)
        assertEquals("u1", reg.getString("user_id"))
        assertEquals("h", reg.getString("identity_hash"))
        assertFalse(reg.has("external_id"))

        notikit.identify(externalId = "u2", name = "민지")
        val id = JSONObject(fake.lastBody!!)
        assertEquals("u2", id.getString("user_id"))
        assertEquals("민지", id.getString("name"))
        assertFalse(id.has("external_id"))
    }

    @Test
    fun identifySendsUserId() = runTest {
        val fake = ok()
        Notikit("https://push.test", "nk", transport = fake).identify(userId = "u1")
        val body = JSONObject(fake.lastBody!!)
        assertEquals("u1", body.getString("user_id"))
        assertFalse(body.has("external_id"))
    }

    @Test
    fun unbindSendsNullUserId() = runTest {
        val fake = ok()
        Notikit("https://push.test", "nk", transport = fake).unbindDevice("t1", "android", "h")
        val body = JSONObject(fake.lastBody!!)
        assertTrue(body.has("user_id"))
        assertTrue(body.isNull("user_id"))
        assertFalse(body.has("external_id"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun blockingLegacyExternalIdSendsUserId() {
        val fake = ok()
        val blocking = NotikitBlocking(Notikit("https://push.test", "nk", transport = fake))
        blocking.registerDevice(token = "t1", platform = "android", externalId = "u1")
        assertEquals("u1", JSONObject(fake.lastBody!!).getString("user_id"))
        blocking.identify(userId = "u2")
        assertEquals("u2", JSONObject(fake.lastBody!!).getString("user_id"))
    }

    @Test
    fun sessionLoginStoresUserIdAndSendsUserId() = runTest {
        val fake = ok()
        val storage = MemoryStorage()
        val session = NotikitSession(Notikit("https://push.test", "nk", transport = fake), storage)

        session.login(StoredUser(userId = "u1", identityHash = "h"), "t1")

        assertEquals(StoredUser("u1", "h"), session.getUser())
        assertEquals("u1", JSONObject(storage.get("notikit.user")!!).getString("userId"))
        assertEquals("u1", JSONObject(fake.lastBody!!).getString("user_id"))

        session.logout("t1")
        val unbind = JSONObject(fake.lastBody!!)
        assertTrue(unbind.isNull("user_id"))
        assertFalse(unbind.has("external_id"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun legacyStoredUserAndConstructorStillWork() = runTest {
        val storage = MemoryStorage()
        storage.set("notikit.user", """{"externalId":"u1","identityHash":"h"}""")
        val session = NotikitSession(Notikit("https://push.test", "nk", transport = ok()), storage)

        val user = session.getUser()
        assertEquals("u1", user?.userId)
        assertEquals("u1", user?.externalId)
        assertEquals("h", user?.identityHash)
        assertEquals(StoredUser("u1", "h"), StoredUser(externalId = "u1", identityHash = "h"))
    }

    @Test
    fun legacyQueuedClickOwnerStillMatches() = runTest {
        val fake = ok()
        val storage = MemoryStorage()
        storage.set("notikit.user", """{"externalId":"u1"}""")
        val now = System.currentTimeMillis()
        storage.set(
            "notikit.clickQueue",
            """[{"logId":"l1","token":"t1","at":$now,"externalId":"u1"},{"logId":"l2","token":"t1","at":$now,"externalId":"other"}]""",
        )
        val session = NotikitSession(Notikit("https://push.test", "nk", transport = fake), storage)

        assertEquals(1, session.flush())
        assertEquals("l1", JSONObject(fake.lastBody!!).getString("log_id"))
    }

    @Test
    fun rotateNotRotatedFallsBackToRegisterAndMovesQueue() = runTest {
        val t = RoutingTransport { p -> if (p == "/api/v1/devices/rotate") okJson("""{"rotated":false}""") else okJson() }
        val storage = MemoryStorage()
        storage.set("notikit.user", """{"userId":"u1","identityHash":"h"}""")
        val now = System.currentTimeMillis()
        storage.set("notikit.clickQueue", """[{"logId":"l1","token":"old","at":$now,"userId":"u1"}]""")
        val session = NotikitSession(Notikit("https://push.test", "nk", transport = t), storage)

        session.rotateToken("old", "new")

        assertEquals(listOf("/api/v1/devices/rotate", "/api/v1/devices"), t.paths())
        val reg = t.calls[1].second
        assertEquals("new", reg.getString("token"))
        assertEquals("u1", reg.getString("user_id"))
        assertEquals("h", reg.getString("identity_hash"))
        assertEquals("new", org.json.JSONArray(storage.get("notikit.clickQueue")!!).getJSONObject(0).getString("token"))
    }

    @Test
    fun rotateFallbackFailureKeepsQueueAndThrows() = runTest {
        val t = RoutingTransport { p -> if (p == "/api/v1/devices/rotate") okJson("""{"rotated":false}""") else failJson(403) }
        val storage = MemoryStorage()
        val now = System.currentTimeMillis()
        storage.set("notikit.clickQueue", """[{"logId":"l1","token":"old","at":$now}]""")
        val session = NotikitSession(Notikit("https://push.test", "nk", transport = t), storage)

        assertFailsWith<NotikitException> { session.rotateToken("old", "new") }
        assertEquals("old", org.json.JSONArray(storage.get("notikit.clickQueue")!!).getJSONObject(0).getString("token"))
    }

    @Test
    fun rotateSameTokenSendsNothing() = runTest {
        val t = RoutingTransport { okJson() }
        NotikitSession(Notikit("https://push.test", "nk", transport = t), MemoryStorage()).rotateToken("a", "a")
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun rotatedMovesPendingUnbindToken() = runTest {
        val t = RoutingTransport { okJson("""{"rotated":true}""") }
        val storage = MemoryStorage()
        storage.set("notikit.pendingUnbind", """{"token":"old","identityHash":"h","at":1}""")
        NotikitSession(Notikit("https://push.test", "nk", transport = t), storage).rotateToken("old", "new")

        val pending = JSONObject(storage.get("notikit.pendingUnbind")!!)
        assertEquals("new", pending.getString("token"))
        assertEquals("h", pending.getString("identityHash"))
        assertEquals(1L, pending.getLong("at"))
    }

    @Test
    fun rotateFallbackLeavesPendingUnbindOnOldToken() = runTest {
        val t = RoutingTransport { p -> if (p == "/api/v1/devices/rotate") okJson("""{"rotated":false}""") else okJson() }
        val storage = MemoryStorage()
        storage.set("notikit.pendingUnbind", """{"token":"old","at":1}""")
        NotikitSession(Notikit("https://push.test", "nk", transport = t), storage).rotateToken("old", "new")
        assertEquals("old", JSONObject(storage.get("notikit.pendingUnbind")!!).getString("token"))
    }

    @Test
    fun pendingUnbindDroppedOnNonRetryable4xx() = runTest {
        val storage = MemoryStorage()
        storage.set("notikit.pendingUnbind", """{"token":"t1","at":1}""")
        NotikitSession(Notikit("https://push.test", "nk", transport = RoutingTransport { failJson(403) }), storage).flush()
        assertEquals(null, storage.get("notikit.pendingUnbind"))
    }

    @Test
    fun pendingUnbindKeptOnServerErrorAndRateLimit() = runTest {
        for (status in listOf(500, 429)) {
            val storage = MemoryStorage()
            storage.set("notikit.pendingUnbind", """{"token":"t1","at":1}""")
            NotikitSession(Notikit("https://push.test", "nk", transport = RoutingTransport { failJson(status) }), storage).flush()
            assertTrue(storage.get("notikit.pendingUnbind") != null, "status $status")
        }
    }

    @Test
    fun reportReceivedSendsOnceAndSkipsDuplicates() = runTest {
        val t = RoutingTransport { okJson("""{"recorded":true}""") }
        val notikit = Notikit("https://push.test", "nk", transport = t)

        val first = notikit.reportReceived("log1", "t1")
        val second = notikit.reportReceived("log1", "t1")

        assertEquals(true, first?.getBoolean("recorded"))
        assertEquals(null, second)
        assertEquals(listOf("/api/v1/messages/received"), t.paths())
        assertEquals("log1", t.calls[0].second.getString("log_id"))
        assertEquals("t1", t.calls[0].second.getString("token"))
        assertEquals(null, notikit.reportReceived("", "t1"))
        assertEquals(1, t.calls.size)
    }

    @Test
    fun reportReceivedRetriesAfterServerError() = runTest {
        var status = 500
        val t = RoutingTransport { if (status == 500) failJson(500) else okJson("""{"recorded":true}""") }
        val notikit = Notikit("https://push.test", "nk", transport = t)

        assertFailsWith<NotikitException> { notikit.reportReceived("log1", "t1") }
        status = 200
        assertEquals(true, notikit.reportReceived("log1", "t1")?.getBoolean("recorded"))
        assertEquals(2, t.calls.size)
    }

    @Test
    fun reportReceivedRetriesAfterNetworkError() = runTest {
        var fail = true
        val t = object : HttpTransport {
            var count = 0
            override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
                count++
                if (fail) throw java.io.IOException("offline")
                return okJson("""{"recorded":true}""")
            }
        }
        val notikit = Notikit("https://push.test", "nk", transport = t)
        assertFailsWith<java.io.IOException> { notikit.reportReceived("log1", "t1") }
        fail = false
        notikit.reportReceived("log1", "t1")
        assertEquals(2, t.count)
    }

    @Test
    fun reportReceivedRemembersOn4xx() = runTest {
        val t = RoutingTransport { failJson(404) }
        val notikit = Notikit("https://push.test", "nk", transport = t)
        assertFailsWith<NotikitException> { notikit.reportReceived("log1", "t1") }
        assertEquals(null, notikit.reportReceived("log1", "t1"))
        assertEquals(1, t.calls.size)
    }

    @Test
    fun blockingReportReceived() {
        val t = RoutingTransport { okJson("""{"recorded":true}""") }
        val blocking = NotikitBlocking(Notikit("https://push.test", "nk", transport = t))
        assertEquals(true, blocking.reportReceived("log1", "t1")?.getBoolean("recorded"))
        assertEquals(null, blocking.reportReceived("log1", "t1"))
    }

    private fun ok() = FakeTransport(200, """{"success":true,"data":{},"error":null}""")
}

class MemoryStorage : NotikitStorage {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String): String? = m[key]
    override fun set(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}
