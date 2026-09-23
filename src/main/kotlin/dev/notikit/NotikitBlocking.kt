package dev.notikit

import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Java 용 블로킹 파사드.
 *
 * Kotlin 쪽 공개 API 는 `suspend` 라 메인 스레드에서 불러도 안전하지만, Java 에서는
 * suspend 함수를 부를 수 없다. 이 래퍼는 호출을 그대로 막으므로 **반드시 백그라운드
 * 스레드에서** 써야 한다 — 이름에 Blocking 을 박아 둔 이유다.
 *
 *     ExecutorService io = Executors.newSingleThreadExecutor();
 *     NotikitBlocking notikit = new NotikitBlocking(new Notikit(baseUrl, apiKey));
 *     io.execute(() -> notikit.registerDevice(token, "android", "user-1", hash));
 */
class NotikitBlocking(private val client: Notikit) {

    @JvmOverloads
    fun registerDevice(
        token: String,
        platform: String,
        userId: String? = null,
        identityHash: String? = null,
        locale: String? = null,
        timezone: String? = null,
    ): JSONObject = runBlocking {
        client.registerDevice(token, platform, userId, identityHash, locale, timezone)
    }

    @Deprecated(
        "Use userId",
        ReplaceWith("registerDevice(token, platform, userId = externalId, identityHash = identityHash, locale = locale, timezone = timezone)"),
    )
    @JvmSynthetic
    fun registerDevice(
        token: String,
        platform: String,
        externalId: String?,
        identityHash: String? = null,
        locale: String? = null,
        timezone: String? = null,
        @Suppress("UNUSED_PARAMETER") legacy: Unit = Unit,
    ): JSONObject = registerDevice(token, platform, externalId, identityHash, locale, timezone)

    @JvmOverloads
    fun identify(userId: String, identityHash: String? = null, attributes: Map<String, Any?>? = null): JSONObject =
        runBlocking { client.identify(userId, identityHash, attributes) }

    @Deprecated("Use userId", ReplaceWith("identify(userId = externalId, identityHash = identityHash, attributes = attributes)"))
    @JvmSynthetic
    fun identify(
        externalId: String,
        identityHash: String? = null,
        attributes: Map<String, Any?>? = null,
        @Suppress("UNUSED_PARAMETER") legacy: Unit = Unit,
    ): JSONObject = identify(externalId, identityHash, attributes)

    fun ping(token: String): JSONObject = runBlocking { client.ping(token) }

    fun subscribe(topic: String, token: String): JSONObject = runBlocking { client.subscribe(topic, token) }

    @JvmOverloads
    fun unbindDevice(token: String, platform: String, identityHash: String? = null): JSONObject =
        runBlocking { client.unbindDevice(token, platform, identityHash) }

    @JvmOverloads
    fun rotateToken(oldToken: String, newToken: String, identityHash: String? = null): JSONObject =
        runBlocking { client.rotateToken(oldToken, newToken, identityHash) }

    @JvmOverloads
    fun reportClick(logId: String, token: String, destination: String? = null): JSONObject =
        runBlocking { client.reportClick(logId, token, destination) }
}

/**
 * `NotikitSession` 의 Java 용 블로킹 파사드. 위와 같은 주의사항이 그대로 적용된다.
 */
class NotikitSessionBlocking(private val session: NotikitSession) {

    fun getUser(): StoredUser? = session.getUser()

    fun login(user: StoredUser, token: String) = runBlocking { session.login(user, token) }

    fun logout(token: String) = runBlocking { session.logout(token) }

    fun rotateToken(oldToken: String, newToken: String) = runBlocking { session.rotateToken(oldToken, newToken) }

    @JvmOverloads
    fun reportClick(logId: String, token: String, destination: String? = null): Boolean =
        runBlocking { session.reportClick(logId, token, destination) }

    @JvmOverloads
    fun handleNotificationOpen(data: Map<String, String>?, token: String, destination: String? = null): Boolean =
        runBlocking { session.handleNotificationOpen(data, token, destination) }

    fun flush(): Int = runBlocking { session.flush() }
}
