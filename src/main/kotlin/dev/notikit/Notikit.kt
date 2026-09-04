package dev.notikit

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val status: Int, val body: String)

/** 테스트/커스텀을 위한 HTTP 추상화 */
interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

class NotikitException(message: String, val status: Int) : Exception(message)

/**
 * Notikit Android/Kotlin SDK — 유저 중심 푸시 등록/식별.
 * FCM 토큰은 Firebase Messaging 이 획득하고, 이 SDK 가 서버에 등록한다.
 *
 * 클라이언트 SDK 는 공개 api-key 만 사용(발송용 api-secret 미포함).
 */
class Notikit @JvmOverloads constructor(
    baseUrl: String,
    private val apiKey: String,
    private val transport: HttpTransport = DefaultHttpTransport(),
) {
    private val baseUrl: String = baseUrl.trimEnd('/')

    private fun post(path: String, body: JSONObject): JSONObject {
        val headers = mutableMapOf("content-type" to "application/json", "api-key" to apiKey)

        val res = transport.post("$baseUrl$path", headers, body.toString())
        val json = try { JSONObject(res.body) } catch (e: Exception) {
            throw NotikitException("Invalid response", res.status)
        }
        if (res.status >= 400 || !json.optBoolean("success", false)) {
            throw NotikitException(json.optString("error", "Request failed"), res.status)
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    @JvmOverloads
    fun registerDevice(
        token: String,
        platform: String,
        externalId: String? = null,
        identityHash: String? = null,
        locale: String? = null,
        timezone: String? = null,
    ): JSONObject {
        val body = JSONObject()
            .put("token", token)
            .put("platform", platform)
        externalId?.let {
            body.put("external_id", it)
            identityHash?.let { h -> body.put("identity_hash", h) }
        }
        locale?.let { body.put("locale", it) }
        timezone?.let { body.put("timezone", it) }
        return post("/api/v1/devices", body)
    }

    @JvmOverloads
    fun identify(externalId: String, identityHash: String? = null, attributes: Map<String, Any?>? = null): JSONObject {
        val body = JSONObject().put("external_id", externalId)
        identityHash?.let { body.put("identity_hash", it) }
        attributes?.let { body.put("attributes", JSONObject(it)) }
        return post("/api/v1/users/identify", body)
    }

    fun subscribe(topic: String, token: String): JSONObject {
        return post("/api/v1/topics/subscribe", JSONObject().put("topic", topic).put("token", token))
    }
}

internal class DefaultHttpTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return HttpResponse(status, text)
    }
}
