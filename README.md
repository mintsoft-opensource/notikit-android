# Notikit Android

> Notikit Android SDK — 유저 중심 푸시 디바이스 등록/식별. Kotlin & Java 호환.

## 알림 탭 자동 보고

`Application` 에서 한 번만 설치하면 알림 탭이 자동으로 보고된다. 앱 코드에서
Intent 를 직접 읽을 필요가 없다.

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

## 설치 (Maven / JitPack)
```kotlin
dependencies {
    implementation("dev.notikit:notikit:0.1.0")
}
```

## 사용 (Kotlin)
```kotlin
import com.google.firebase.messaging.FirebaseMessaging
import dev.notikit.Notikit

val notikit = Notikit(baseUrl = "https://push.example.com", apiKey = "nk_xxx") // 공개키만

FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    notikit.registerDevice(
        token = token,
        platform = "android",
        externalId = "user-123",
        identityHash = "<서버계산 HMAC>",
    )
}
```

## 사용 (Java)
```java
Notikit notikit = new Notikit("https://push.example.com", "nk_xxx");
notikit.registerDevice(token, "android", "user-123", hash, null, null);
```

## API
| | 설명 |
|---|---|
| `registerDevice(token, platform, externalId?, identityHash?, ...)` | FCM 토큰 등록 |
| `identify(externalId, identityHash?, attributes?)` | 유저 식별 |
| `subscribe(topic, token)` | 토픽 구독 |

- `api-secret` 은 서버 전용 — 앱에는 넣지 마세요(공개 api-key 만).
- 빌드: JDK 17 (툴체인 자동 provisioning). `gradle test`

## 라이선스
Apache-2.0
