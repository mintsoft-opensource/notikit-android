package dev.notikit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.Executors

/**
 * SharedPreferences 기반 저장소. 앱이 직접 구현하지 않아도 되도록 기본 제공한다.
 * 푸시 클릭은 앱이 죽은 상태에서 콜드 스타트로 들어오므로 메모리 저장은 쓸 수 없다.
 */
class SharedPrefsStorage(context: Context, name: String = "notikit") : NotikitStorage {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * 알림 탭 자동 보고.
 *
 * iOS 와 달리 안드로이드에는 가로챌 델리게이트가 없다 — 알림을 누르면 런처 Activity 가
 * FCM data 를 **Intent extras 로 달고** 실행된다. 그래서 Activity 생성/재개를 관찰해
 * extras 에서 발송 id 를 찾는다. 앱 코드에 손댈 필요가 없다.
 *
 *     class MyApp : Application() {
 *       override fun onCreate() {
 *         super.onCreate()
 *         NotikitAndroid.install(this, client) { currentFcmToken }
 *       }
 *     }
 *
 * 토큰은 갱신되므로 값이 아니라 콜백으로 받는다 — 값으로 고정하면 갱신 후 클릭이
 * 서버에서 매칭되지 않는다.
 */
object NotikitAndroid {
    /** 같은 Intent 를 재개(onResume) 때마다 다시 세지 않기 위한 표식 */
    private const val HANDLED = "notikit_handled"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notikit-io").apply { isDaemon = true }
    }

    @Volatile private var installed = false

    /**
     * @param token 현재 푸시 토큰을 돌려주는 콜백. null 이면 그 탭은 보고하지 않는다.
     * @return 클릭 큐·유저 저장을 담당하는 세션. 로그인/로그아웃에 그대로 쓴다.
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        app: Application,
        client: Notikit,
        storage: NotikitStorage = SharedPrefsStorage(app),
        token: () -> String?,
    ): NotikitSession {
        val session = NotikitSession(client, storage)
        if (installed) return session
        installed = true

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                handle(activity.intent, session, token)

            // 앱이 살아 있는 상태에서 알림을 누르면 새 Intent 로 재개된다.
            // onNewIntent 는 여기서 관찰할 수 없어 재개 시점에 한 번 더 본다.
            override fun onActivityResumed(activity: Activity) =
                handle(activity.intent, session, token)

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // 밀린 클릭·언바인딩 재전송 — 앱이 다시 열린 지금이 재시도할 때다
        io.execute { runCatching { session.flush() } }
        return session
    }

    private fun handle(intent: Intent?, session: NotikitSession, token: () -> String?) {
        val extras = intent?.extras ?: return
        // 이미 처리한 Intent 는 건너뛴다. 표식이 없으면 화면 회전이나 재개마다
        // 같은 탭이 다시 보고되어 클릭 수가 부풀려진다(서버 유니크가 막긴 하지만
        // 매번 불필요한 요청이 나간다).
        if (extras.getBoolean(HANDLED, false)) return

        val data = extras.keySet().associateWith { extras.get(it)?.toString() ?: "" }
        if (Notikit.logIdFromPayload(data) == null) return

        extras.putBoolean(HANDLED, true)
        val tok = token() ?: return
        val destination = data["deep_link"]

        // 네트워크는 메인 스레드에서 못 쓴다. 실패해도 세션이 큐에 넣어 다음에 재시도한다.
        io.execute { runCatching { session.handleNotificationOpen(data, tok, destination) } }
    }
}
