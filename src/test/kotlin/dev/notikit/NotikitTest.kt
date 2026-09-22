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

        notikit.registerDevice(token = "t1", platform = "android", externalId = "u1", identityHash = "h")

        assertEquals("https://push.test/api/v1/devices", fake.lastUrl)
        assertEquals("nk_test", fake.lastHeaders?.get("api-key"))
        val body = JSONObject(fake.lastBody!!)
        assertEquals("u1", body.getString("external_id"))
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
}
