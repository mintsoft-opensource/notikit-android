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

    /** 수신 보고 중복 방지 — 재배달된 푸시가 같은 요청을 다시 내보내지 않게 한다 */
    private val receipts = ReceiptDedupe()

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

    /** 디바이스 등록·업서트. userId(고객 서비스의 유저 id)가 있으면 그 유저에 연결한다. */
    @JvmOverloads
    suspend fun registerDevice(
        token: String,
        platform: String,
        userId: String? = null,
        identityHash: String? = null,
        locale: String? = null,
        timezone: String? = null,
    ): JSONObject {
        val body = JSONObject()
            .put("token", token)
            .put("platform", platform)
        userId?.let {
            body.put("user_id", it)
            identityHash?.let { h -> body.put("identity_hash", h) }
        }
        locale?.let { body.put("locale", it) }
        timezone?.let { body.put("timezone", it) }
        return post("/api/v1/devices", body)
    }

    /** `externalId =` 로 부르던 기존 호출을 살려 둔다. 마지막 인자는 오버로드 구분용이다. */
    @Deprecated(
        "Use userId",
        ReplaceWith("registerDevice(token, platform, userId = externalId, identityHash = identityHash, locale = locale, timezone = timezone)"),
    )
    @JvmSynthetic
    suspend fun registerDevice(
        token: String,
        platform: String,
        externalId: String?,
        identityHash: String? = null,
        locale: String? = null,
        timezone: String? = null,
        @Suppress("UNUSED_PARAMETER") legacy: Unit = Unit,
    ): JSONObject = registerDevice(token, platform, externalId, identityHash, locale, timezone)

    /** 유저 식별. name 은 치환 변수 {{name}} 과 콘솔 표시에 쓰인다 */
    @JvmOverloads
    suspend fun identify(
        userId: String,
        identityHash: String? = null,
        attributes: Map<String, Any?>? = null,
        name: String? = null,
    ): JSONObject {
        val body = JSONObject().put("user_id", userId)
        identityHash?.let { body.put("identity_hash", it) }
        name?.let { body.put("name", it) }
        attributes?.let { body.put("attributes", JSONObject(it)) }
        return post("/api/v1/users/identify", body)
    }

    /** `externalId =` 로 부르던 기존 호출을 살려 둔다. 마지막 인자는 오버로드 구분용이다. */
    @Deprecated(
        "Use userId",
        ReplaceWith("identify(userId = externalId, identityHash = identityHash, attributes = attributes, name = name)"),
    )
    @JvmSynthetic
    suspend fun identify(
        externalId: String,
        identityHash: String? = null,
        attributes: Map<String, Any?>? = null,
        name: String? = null,
        @Suppress("UNUSED_PARAMETER") legacy: Unit = Unit,
    ): JSONObject = identify(externalId, identityHash, attributes, name)

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
        val body = JSONObject().put("token", token).put("platform", platform).put("user_id", JSONObject.NULL)
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
     * 유저는 서버가 토큰의 바인딩에서 해석하므로 user_id 를 보내지 않는다.
     */
    @JvmOverloads
    suspend fun reportClick(logId: String, token: String, destination: String? = null): JSONObject {
        val body = JSONObject().put("log_id", logId).put("token", token)
        destination?.let { body.put("destination", it) }
        return post("/api/v1/messages/click", body)
    }

    /**
     * 푸시 **수신** 보고 — 단말이 실제로 알림을 받았다는 사실을 남긴다.
     *
     * FCM 접수(발송 성공)는 기기가 꺼져 있어도, 앱이 지워져 있어도 성공한다. 앱이 이걸
     * 부르지 않으면 콘솔의 "도달" 칸은 영원히 0 이다. 부르는 자리는 **알림을 받은 순간**,
     * 곧 `FirebaseMessagingService.onMessageReceived` 다.
     *
     * 같은 발송을 두 번 이상 부르면 **요청을 내보내지 않고** null 을 돌려준다(로컬 중복 방지).
     * 서버도 `(발송, 기기)` 유니크로 한 번만 센다 — 로컬 기억은 낭비되는 요청을 없애는 쪽이다.
     *
     * @return 보고했으면 서버 응답(`recorded`), 이미 보고한 발송이면 null
     */
    suspend fun reportReceived(logId: String, token: String): JSONObject? {
        if (!receipts.claim(logId)) return null
        return try {
            post("/api/v1/messages/received", JSONObject().put("log_id", logId).put("token", token))
        } catch (e: Exception) {
            // 4xx 는 다시 보내도 같은 답이다(없는 발송·수신자 아님·형식 오류) — 기억을 유지해
            // 재배달마다 같은 요청을 반복하지 않는다. 네트워크 장애·5xx·429 만 풀어 준다.
            if (e !is NotikitException || e.status >= 500 || e.status == 429) receipts.release(logId)
            throw e
        }
    }

    companion object {
        /** 푸시 페이로드에서 notikit 이 예약해 쓰는 data 키 */
        const val LOG_ID_KEY: String = "notikit_log_id"

        /** FCM data 에서 발송 id 추출 — 없으면 notikit 발송이 아니다 */
        @JvmStatic
        fun logIdFromPayload(data: Map<String, String>?): String? =
            data?.get(LOG_ID_KEY)?.takeIf { it.isNotEmpty() }

        /** 푸시 data 에서 딥링크 추출 */
        @JvmStatic
        fun deepLinkFromPayload(data: Map<String, String>?): String? =
            data?.get("deep_link")?.takeIf { it.isNotEmpty() }

        /** notikit·FCM 이 쓰는 키. 이것을 뺀 나머지가 발송 때 넣은 커스텀 필드다(서버의 금지 키 목록과 같다). */
        private val INTERNAL_KEYS = setOf(
            "deep_link", LOG_ID_KEY, "actions", "title", "body", "icon", "image",
            "from", "collapse_key", "notification", "message_type", "fcm_options",
        )
        private val INTERNAL_PREFIXES = listOf("google.", "gcm.")

        /**
         * 발송 때 넣은 커스텀 필드(템플릿 필드 포함)만 골라낸다.
         * `RemoteMessage.data` 나 알림 탭 Intent 의 extras 를 그대로 넘기면 된다.
         */
        @JvmStatic
        fun customDataFromPayload(data: Map<String, String>?): Map<String, String> =
            data.orEmpty().filterKeys { k -> k !in INTERNAL_KEYS && INTERNAL_PREFIXES.none { k.startsWith(it) } }
    }
}

/**
 * 수신 보고 중복 방지 — "이 발송은 이미 보고했다"를 프로세스 안에서만 기억한다.
 *
 * FCM 은 같은 메시지를 다시 배달할 수 있다. 서버가 한 번만 세므로 도달 수가 부풀지는
 * 않지만, 기억하지 않으면 재배달마다 요청이 한 번씩 더 나가 수신 보고 rate limit 을 깎는다.
 * 영속 저장은 쓰지 않는다 — 여기서 놓친 중복은 낭비된 요청 한 건으로 끝나고, 잘못 기억해
 * **보고를 영영 빠뜨리는 쪽**이 더 나쁘다.
 */
internal class ReceiptDedupe(private val max: Int = RECEIPT_DEDUPE_SIZE) {
    // LinkedHashSet 은 삽입 순서를 지킨다 — 가장 오래 전에 본 것부터 버린다
    private val seen = LinkedHashSet<String>()

    /** 처음 보는 발송이면 기억하고 true. 이미 본 발송(또는 빈 id)이면 false. 보고 **전에** 잡는다. */
    @Synchronized
    fun claim(logId: String): Boolean {
        if (logId.isEmpty() || !seen.add(logId)) return false
        if (seen.size > max) seen.remove(seen.first())
        return true
    }

    /** 다시 시도할 가치가 있는 이유(네트워크·5xx·429)로 실패했을 때만 기억을 되돌린다 */
    @Synchronized
    fun release(logId: String) {
        seen.remove(logId)
    }

    companion object {
        /** 한 프로세스가 기억하는 발송 id 수. 넘으면 오래된 것부터 버린다. */
        const val RECEIPT_DEDUPE_SIZE = 200
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
