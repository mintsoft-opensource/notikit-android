# Notikit Android

> Notikit Android SDK — 유저 중심 푸시 디바이스 등록/식별. Kotlin & Java 호환.

[소개](https://notikit.mint-soft.com) · [서버](https://github.com/mintsoft-opensource/notikit) · 다른 SDK: [JS](https://github.com/mintsoft-opensource/notikit-js) · [iOS](https://github.com/mintsoft-opensource/notikit-ios) · [Flutter](https://github.com/mintsoft-opensource/notikit-flutter)

## 알림 탭 자동 보고

`Application` 에서 한 번 설치하면 **콜드 스타트** 탭이 자동으로 보고된다. 앱 코드에서
Intent 를 직접 읽을 필요가 없다. 앱이 이미 떠 있는 상태의 탭(warm start)만 한 줄 배선이
필요하다 — 아래 참조.

```kotlin
class MyApp : Application() {
    lateinit var session: NotikitSession

    override fun onCreate() {
        super.onCreate()
        val client = Notikit(baseUrl = "https://push.example.com", apiKey = "nk_xxx")
        // 토큰은 갱신되므로 값이 아니라 콜백으로 넘긴다
        session = NotikitAndroid.install(this, client) { currentFcmToken }
    }
}
```

### warm start (앱이 떠 있을 때의 탭)

`ActivityLifecycleCallbacks` 에는 `onNewIntent` 가 없고, singleTop/singleTask Activity 는
알림 Intent 를 `onNewIntent` 로 받는다. 이때 `getIntent()` 는 **원래 실행 Intent 를 계속**
돌려주므로(안드로이드 문서) SDK 가 생명주기만 봐서는 이 탭을 볼 수 없다. 런처 Activity 에
다음을 추가한다.

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    NotikitAndroid.onNewIntent(intent)
}
```

`install` 이 돌려주는 세션으로 로그인/로그아웃을 처리한다. 저장소는
`SharedPrefsStorage` 가 기본으로 쓰이고, 필요하면 직접 구현해 넘길 수 있다.

```kotlin
session.login(StoredUser("user-1", identityHash), token)   // 로그인
session.logout(token)                                       // 로그아웃(바인딩 해제)
```

동작 방식: 안드로이드에는 iOS 같은 알림 델리게이트가 없다. 알림을 누르면 런처
Activity 가 FCM data 를 Intent extras 로 달고 실행되므로, Activity 생명주기를 관찰해
extras 에서 발송 id 를 찾는다. 같은 Intent 를 재개 때마다 다시 세지 않도록 처리 표식을
남긴다.

## 설치 (JitPack)
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.mintsoft-opensource:notikit-android:0.1.0")
}
```

Maven Central(`dev.notikit:notikit`) 배포는 아직이다.

## 사용 (Kotlin)

공개 메서드는 전부 `suspend` 이고 내부에서 `Dispatchers.IO` 로 옮기므로 **메인 스레드에서
불러도 안전하다**. 직접 스레드를 옮길 필요가 없다.

```kotlin
import com.google.firebase.messaging.FirebaseMessaging
import dev.notikit.Notikit

val notikit = Notikit(baseUrl = "https://push.example.com", apiKey = "nk_xxx") // 공개키만

lifecycleScope.launch {
    val token = FirebaseMessaging.getInstance().token.await()
    notikit.registerDevice(
        token = token,
        platform = "android",
        userId = "user-123", // 고객 서비스의 유저 id
        identityHash = "<서버계산 HMAC>",
    )
}
```

### 토큰 교체

`registerDevice` 를 새 토큰으로 부르면 **행이 하나 더 생겨** 같은 사람에게 중복 발송된다.
교체는 전용 메서드를 쓴다 — 서버가 기존 행을 제자리 갱신해 토픽 구독·클릭 이력이 보존되고,
밀린 클릭의 토큰도 함께 갱신된다.

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val old = lastKnownToken ?: return
        CoroutineScope(Dispatchers.IO).launch { session.rotateToken(old, token) }
        lastKnownToken = token
    }
}
```

## 사용 (Java)

Java 는 `suspend` 함수를 부를 수 없어 블로킹 파사드를 제공한다. **이름 그대로 막히므로
반드시 백그라운드 스레드에서** 호출한다.

```java
NotikitBlocking notikit = new NotikitBlocking(new Notikit("https://push.example.com", "nk_xxx"));
ExecutorService io = Executors.newSingleThreadExecutor();
io.execute(() -> notikit.registerDevice(token, "android", "user-123", hash));
```

`NotikitAndroid.install` 이 돌려주는 세션은 `NotikitSessionBlocking` 으로 감싸 쓴다.

## API
| | 설명 |
|---|---|
| `registerDevice(token, platform, userId?, identityHash?, ...)` | FCM 토큰 등록 |
| `identify(userId, identityHash?, attributes?)` | 유저 식별 |
| `subscribe(topic, token)` | 토픽 구독 |
| `unsubscribe(topic, token)` | 토픽 구독 해지 |
| `Notikit.customDataFromPayload(data)` | 받은 푸시에서 커스텀 필드(템플릿 필드 포함)만 꺼내기 |
| `Notikit.deepLinkFromPayload(data)` | 받은 푸시의 딥링크 |

- 유저 id 는 `userId`(요청 본문 `user_id`)로 넘긴다. 이전 이름 `externalId`(`external_id`)도
  계속 동작하지만 deprecated 다 — `externalId =` 로 부르던 코드, `StoredUser(externalId = ...)`,
  `StoredUser.externalId`, 이전 버전이 저장한 로그인 유저 모두 그대로 읽힌다.
- `api-secret` 은 서버 전용 — 앱에는 넣지 마세요(공개 api-key 만).
- 빌드: JDK 17 (툴체인 자동 provisioning). `gradle test`

## 라이선스
Apache-2.0
