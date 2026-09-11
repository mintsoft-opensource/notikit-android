package dev.notikit

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /**
     * SDK 내부 작업용 스코프. SupervisorJob 이라 한 보고가 실패해도 나머지가 취소되지 않는다.
     * object 싱글턴이라 수명이 앱과 같다 — 누수가 아니라 의도된 것.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // session 이 곧 설치 여부다 — onNewIntent 는 호스트가 부르므로 여기 남겨둔다
    @Volatile private var session: NotikitSession? = null
    @Volatile private var tokenProvider: (() -> String?)? = null

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
        // 두 번째 호출은 이미 콜백에 연결된 세션을 돌려준다 — 새로 만들면 호출부가
        // 큐를 공유하지 않는 다른 세션을 쥐게 된다. @Volatile 의 확인-후-대입은
        // 원자적이지 않아(가시성만 보장) 동시 호출이 둘 다 통과할 수 있으므로 락을 쓴다.
        synchronized(this) {
            session?.let { return it }
            return installLocked(app, client, storage, token)
        }
    }

    private fun installLocked(
        app: Application,
        client: Notikit,
        storage: NotikitStorage,
        token: () -> String?,
    ): NotikitSession {
        val s = NotikitSession(client, storage)
        session = s
        tokenProvider = token

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                handle(activity.intent, s, token)

            // 콜드 스타트 탭은 onActivityCreated 가 잡는다. 앱이 살아 있을 때의 탭은
            // onNewIntent 로 오는데 getIntent() 는 원래 Intent 를 계속 돌려주므로
            // 여기서는 볼 수 없다 — 호스트가 NotikitAndroid.onNewIntent 를 불러야 한다.
            // 재개 관찰은 setIntent() 를 호출해 준 호스트를 위해 남겨둔다.
            override fun onActivityResumed(activity: Activity) =
                handle(activity.intent, s, token)

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // 밀린 클릭·언바인딩 재전송 — 앱이 다시 열린 지금이 재시도할 때다
        scope.launch { runCatching { s.flush() } }
        return s
    }

    /**
     * 앱이 살아 있는 상태에서 알림을 눌렀을 때(warm start) 호출한다.
     *
     * `ActivityLifecycleCallbacks` 에는 `onNewIntent` 가 없고, singleTop/singleTask
     * Activity 는 알림 Intent 를 `onNewIntent` 로 받는다. 이때 `getIntent()` 는
     * **원래 실행 Intent 를 계속 돌려주므로**(AOSP Activity.onNewIntent 문서) 재개만
     * 관찰해서는 이 탭을 볼 수 없다. 그래서 호스트가 넘겨줘야 한다:
     *
     *     override fun onNewIntent(intent: Intent) {
     *       super.onNewIntent(intent)
     *       setIntent(intent)
     *       NotikitAndroid.onNewIntent(intent)
     *     }
     */
    @JvmStatic
    fun onNewIntent(intent: Intent) {
        val s = session ?: return
        val t = tokenProvider ?: return
        handle(intent, s, t)
    }

    private fun handle(intent: Intent?, session: NotikitSession, token: () -> String?) {
        if (intent == null) return
        // 이미 처리한 Intent 는 건너뛴다. 표식이 없으면 화면 회전이나 재개마다
        // 같은 탭이 다시 보고되어 매번 불필요한 요청이 나간다(서버 유니크가 막긴 한다).
        if (intent.getBooleanExtra(HANDLED, false)) return

        val extras = intent.extras ?: return
        val data = extras.keySet().associateWith { extras.get(it)?.toString() ?: "" }
        if (Notikit.logIdFromPayload(data) == null) return

        // 토큰이 아직 없으면 **표식을 남기지 않고** 물러난다. FCM 토큰은 비동기라
        // onActivityCreated 시점에 null 인 경우가 흔한데, 여기서 표식을 찍어버리면
        // 그 탭은 이후 resume 에서도 걸러져 영구히 유실된다.
        val tok = token() ?: return

        // 표식은 **Intent 에 직접** 쓴다. Intent.getExtras() 는 new Bundle(mExtras) 로
        // 복사본을 돌려주므로(AOSP Intent.java), 그 Bundle 에 put 해봐야 원본에는
        // 남지 않아 표식이 전혀 동작하지 않는다.
        intent.putExtra(HANDLED, true)
        val destination = data["deep_link"]

        // 실패해도 세션이 큐에 넣어 다음에 재시도한다.
        scope.launch { runCatching { session.handleNotificationOpen(data, tok, destination) } }
    }
}
