package dev.notikit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val status: Int, val body: String)

/**
 * 테스트/커스텀을 위한 HTTP 추상화.
 *
 * 블로킹으로 둔다. SDK 가 **항상 Dispatchers.IO 에서** 호출하므로 구현체는 평범한
 * 동기 코드를 쓰면 되고, Java 로도 구현할 수 있다.
 */
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

    /**
     * 모든 공개 메서드가 여기를 지난다. transport 가 블로킹이어도 **여기서 IO 로 옮기므로**
     * 호출부는 메인 스레드에서 불러도 안전하다. 예전에는 호출 스레드에서 그대로 막혀
     * 메인에서 부르면 NetworkOnMainThreadException 으로 죽었다.
     */
    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val headers = mutableMapOf("content-type" to "application/json", "api-key" to apiKey)

        val res = transport.post("$baseUrl$path", headers, body.toString())
        val json = try { JSONObject(res.body) } catch (e: Exception) {
            throw NotikitException("Invalid response", res.status)
        }
        if (res.status >= 400 || !json.optBoolean("success", false)) {
            throw NotikitException(json.optString("error", "Request failed"), res.status)
        }
        json.optJSONObject("data") ?: JSONObject()
    }

    @JvmOverloads
    suspend fun registerDevice(
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
    suspend fun identify(externalId: String, identityHash: String? = null, attributes: Map<String, Any?>? = null): JSONObject {
        val body = JSONObject().put("external_id", externalId)
        identityHash?.let { body.put("identity_hash", it) }
        attributes?.let { body.put("attributes", JSONObject(it)) }
        return post("/api/v1/users/identify", body)
    }

    /**
     * 앱 열림 보고 — 접속 통계(DAU/WAU/MAU)의 원천.
     * registerDevice 는 무거우므로 앱을 열 때마다는 이쪽을 쓴다.
     */
    suspend fun ping(token: String): JSONObject {
        return post("/api/v1/devices/ping", JSONObject().put("token", token))
    }

    /** 토픽 구독. 규칙으로 채워지는 토픽은 명단이 자동으로 정해지므로 409 가 온다. */
    suspend fun subscribe(topic: String, token: String): JSONObject {
        return post("/api/v1/topics/subscribe", JSONObject().put("topic", topic).put("token", token))
    }

    /**
     * 토픽 구독 해지.
     *
     * 알림 설정 토글을 끄는 경로다. 이게 없으면 유저가 한 번 켠 토픽을 앱에서 끌 수 없다.
     * 구독과 달리 없는 토픽을 만들지 않는다 — 없으면 404.
     */
    suspend fun unsubscribe(topic: String, token: String): JSONObject {
        return post("/api/v1/topics/unsubscribe", JSONObject().put("topic", topic).put("token", token))
    }

    /**
     * 디바이스 바인딩 해제 (로그아웃/계정전환).
     * 해제하지 않으면 이후 클릭이 이전 계정에 계속 귀속된다.
     */
    @JvmOverloads
    suspend fun unbindDevice(token: String, platform: String, identityHash: String? = null): JSONObject {
        val body = JSONObject().put("token", token).put("platform", platform).put("external_id", JSONObject.NULL)
        // 서버가 현재 바인딩된 유저의 해시를 검증한다 — 남의 토큰으로 해제하는 것을 막는다
        identityHash?.let { body.put("identity_hash", it) }
        return post("/api/v1/devices", body)
    }

    /**
     * 푸시 토큰 교체 (FCM onNewToken).
     *
     * 새 토큰으로 registerDevice 를 부르면 **행이 하나 더 생긴다** — 옛 행이 유저
     * 바인딩을 유지한 채 활성으로 남아 같은 사람에게 중복 발송된다. 이 메서드는
     * 서버가 기존 행의 토큰을 제자리 갱신하게 해 기기 id·토픽 구독·클릭 이력을 보존한다.
     *
     * @param identityHash 유저가 묶인 기기라면 필요하다(남이 알림을 가져가지 못하게).
     */
    @JvmOverloads
    suspend fun rotateToken(oldToken: String, newToken: String, identityHash: String? = null): JSONObject {
        val body = JSONObject().put("old_token", oldToken).put("new_token", newToken)
        identityHash?.let { body.put("identity_hash", it) }
        return post("/api/v1/devices/rotate", body)
    }

    /**
     * 푸시 클릭(알림 탭) 보고.
     * 유저는 서버가 토큰의 바인딩에서 해석하므로 external_id 를 보내지 않는다.
     */
    @JvmOverloads
    suspend fun reportClick(logId: String, token: String, destination: String? = null): JSONObject {
        val body = JSONObject().put("log_id", logId).put("token", token)
        destination?.let { body.put("destination", it) }
        return post("/api/v1/messages/click", body)
    }

    companion object {
        /** 푸시 페이로드에서 notikit 이 예약해 쓰는 data 키 */
        const val LOG_ID_KEY: String = "notikit_log_id"

        /** FCM data 에서 발송 id 추출 — 없으면 notikit 발송이 아니다 */
        @JvmStatic
        fun logIdFromPayload(data: Map<String, String>?): String? =
            data?.get(LOG_ID_KEY)?.takeIf { it.isNotEmpty() }
    }
}

internal class DefaultHttpTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
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
        } finally {
            conn.disconnect()
        }
    }
}
