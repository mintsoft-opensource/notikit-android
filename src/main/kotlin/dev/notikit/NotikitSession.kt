package dev.notikit

import org.json.JSONArray
import org.json.JSONObject

/**
 * 영속 저장소 추상화. 이 모듈은 순수 JVM 라이브러리라 android.content.Context 에 의존하지
 * 않는다 — 앱에서 SharedPreferences 를 감싸 주입한다:
 *
 *     val prefs = context.getSharedPreferences("notikit", Context.MODE_PRIVATE)
 *     val storage = object : NotikitStorage {
 *         override fun get(key: String) = prefs.getString(key, null)
 *         override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
 *         override fun remove(key: String) { prefs.edit().remove(key).apply() }
 *     }
 *
 * 푸시 클릭은 앱이 죽은 상태에서 콜드 스타트로 들어오므로 메모리 저장은 쓸 수 없다.
 */
interface NotikitStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/** 로그인 시 저장해두는 유저 */
data class StoredUser(val externalId: String, val identityHash: String? = null)

/**
 * 로그인 유저를 영속 저장하고 푸시 클릭을 보고하는 세션 계층.
 *
 * 저장한 유저를 **클릭에 실어 보내지 않는다**. 서버가 신뢰하는 것은 디바이스 바인딩이고,
 * 클라이언트가 주장하는 external_id 를 믿으면 등록 시 identity_hash 로 막아둔 사칭이
 * 클릭 경로로 다시 열린다. 저장한 유저는 **바인딩을 최신으로 유지**하는 데만 쓴다.
 *
 * 네트워크를 타므로 메인 스레드에서 호출하지 말 것.
 */
class NotikitSession @JvmOverloads constructor(
    private val client: Notikit,
    private val storage: NotikitStorage,
    private val platform: String = "android",
) {
    fun getUser(): StoredUser? {
        val raw = storage.get(USER_KEY) ?: return null
        return try {
            val o = JSONObject(raw)
            StoredUser(o.getString("externalId"), o.optString("identityHash").ifEmpty { null })
        } catch (e: Exception) {
            null // 손상된 값은 조용히 버린다 — 저장소 파손이 SDK 를 죽이면 안 된다
        }
    }

    /** 로그인 — 유저를 저장하고 디바이스를 그 유저에 바인딩한다. */
    fun login(user: StoredUser, token: String) {
        storage.remove(UNBIND_KEY) // 새 바인딩이 덮어쓰므로 밀린 언바인딩은 의미 없다
        val o = JSONObject().put("externalId", user.externalId)
        user.identityHash?.let { o.put("identityHash", it) }
        storage.set(USER_KEY, o.toString())
        client.registerDevice(token, platform, user.externalId, user.identityHash)
    }

    /**
     * 로그아웃 — 저장된 유저를 지우고 서버 바인딩도 해제한다.
     * 해제를 빠뜨리면 공용 기기에서 다음 사람의 클릭이 이전 계정에 붙는다.
     */
    fun logout(token: String) {
        val user = getUser()
        storage.remove(USER_KEY)
        // 이전 세션의 밀린 클릭은 버린다 — 지금 보내면 다음 로그인 유저에게 붙는다
        storage.remove(QUEUE_KEY)
        try {
            client.unbindDevice(token, platform, user?.identityHash)
        } catch (e: Exception) {
            // 로그아웃은 오프라인에서 가장 자주 일어난다. 포기하면 서버 바인딩이 이전
            // 유저로 남아 다음 사람의 클릭이 그 유저에게 붙는다 — 재시도용으로 남긴다.
            val pending = JSONObject().put("token", token).put("at", System.currentTimeMillis())
            user?.identityHash?.let { pending.put("identityHash", it) }
            storage.set(UNBIND_KEY, pending.toString())
            throw e
        }
    }

    /**
     * 푸시 클릭 보고. 실패하면 큐에 넣어 다음 flush 때 재시도한다
     * (콜드 스타트 직후·오프라인에서 클릭이 조용히 유실되면 클릭률이 낮게 잡힌다).
     */
    @JvmOverloads
    fun reportClick(logId: String, token: String, destination: String? = null): Boolean = try {
        client.reportClick(logId, token, destination)
        true
    } catch (e: Exception) {
        enqueue(logId, token, destination)
        false
    }

    /** 밀린 클릭 재전송 — SDK 초기화 직후·앱 포그라운드 진입 시 호출 */
    fun flush(): Int {
        retryPendingUnbind()

        val queue = readQueue()
        if (queue.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val current = getUser()?.externalId
        val failed = JSONArray()
        var sent = 0

        for (i in 0 until queue.length()) {
            val c = queue.optJSONObject(i) ?: continue
            if (now - c.optLong("at") >= QUEUE_TTL_MS) continue // 오래된 클릭은 버린다
            // 클릭 당시 유저와 지금 유저가 다르면 보내지 않는다 — 서버는 flush 시점의
            // 바인딩으로 유저를 해석하므로 다음 사람에게 귀속된다
            val owner = c.optString("externalId").ifEmpty { null }
            if (owner != current) continue
            try {
                client.reportClick(c.getString("logId"), c.getString("token"), c.optString("destination").ifEmpty { null })
                sent++
            } catch (e: NotikitException) {
                // 4xx 는 재시도해도 같다(토큰 교체로 404 등) — 7일간 두드리지 않고 버린다
                if (e.status < 400 || e.status >= 500 || e.status == 429) failed.put(c)
            } catch (e: Exception) {
                failed.put(c)
            }
        }

        if (failed.length() > 0) storage.set(QUEUE_KEY, failed.toString()) else storage.remove(QUEUE_KEY)
        return sent
    }

    /** 실패해 남아 있던 언바인딩 재시도 — 성공할 때까지 서버 바인딩이 이전 유저로 남는다 */
    private fun retryPendingUnbind() {
        val raw = storage.get(UNBIND_KEY) ?: return
        val o = try { JSONObject(raw) } catch (e: Exception) { storage.remove(UNBIND_KEY); return }
        val token = o.optString("token").ifEmpty { null } ?: run { storage.remove(UNBIND_KEY); return }
        try {
            client.unbindDevice(token, platform, o.optString("identityHash").ifEmpty { null })
            storage.remove(UNBIND_KEY)
        } catch (e: Exception) {
            /* 다음 flush 에서 재시도 */
        }
    }

    private fun readQueue(): JSONArray = try {
        storage.get(QUEUE_KEY)?.let { JSONArray(it) } ?: JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    private fun enqueue(logId: String, token: String, destination: String?) {
        val owner = getUser()?.externalId
        val queue = readQueue()
        // 같은 발송의 중복 클릭은 서버에서도 유니크로 걸리므로 큐 단계에서 미리 접는다
        for (i in 0 until queue.length()) {
            val c = queue.optJSONObject(i) ?: continue
            if (c.optString("logId") == logId && c.optString("token") == token) return
        }
        val entry = JSONObject()
            .put("logId", logId)
            .put("token", token)
            .put("at", System.currentTimeMillis())
        destination?.let { entry.put("destination", it) }
        owner?.let { entry.put("externalId", it) }
        queue.put(entry)

        val trimmed = if (queue.length() > QUEUE_MAX) {
            JSONArray().also { out -> for (i in queue.length() - QUEUE_MAX until queue.length()) out.put(queue.get(i)) }
        } else queue
        storage.set(QUEUE_KEY, trimmed.toString())
    }

    private companion object {
        const val USER_KEY = "notikit.user"
        const val QUEUE_KEY = "notikit.clickQueue"
        const val UNBIND_KEY = "notikit.pendingUnbind"
        const val QUEUE_MAX = 50
        const val QUEUE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
