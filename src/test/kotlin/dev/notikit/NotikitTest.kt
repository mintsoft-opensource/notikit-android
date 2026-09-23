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

    private fun ok() = FakeTransport(200, """{"success":true,"data":{},"error":null}""")
}

class MemoryStorage : NotikitStorage {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String): String? = m[key]
    override fun set(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}
