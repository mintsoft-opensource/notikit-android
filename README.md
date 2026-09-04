# Notikit Android (Kotlin/JVM)

> Notikit Android SDK — 유저 중심 푸시 디바이스 등록/식별. Kotlin & Java 호환.

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
