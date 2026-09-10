plugins {
    id("com.android.library") version "8.13.0"
    kotlin("android") version "2.2.20"
    `maven-publish`
}

group = "dev.notikit"
version = "0.1.0"

/**
 * Android 라이브러리로 빌드하는 이유: 알림 탭 자동 후킹에 android.app.Application 의
 * ActivityLifecycleCallbacks 가 필요하다. 순수 JVM 모듈로는 그 API 에 접근할 수 없어
 * 앱이 직접 Intent 를 읽어 SDK 에 넘겨야 했다.
 *
 * HTTP·클릭 큐 등 로직은 여전히 안드로이드 API 에 의존하지 않으므로
 * src/test 의 단위 테스트는 에뮬레이터 없이 JVM 에서 그대로 돈다.
 */
android {
    namespace = "dev.notikit"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // org.json 은 android.jar 가 제공하므로 의존성을 넣지 않는다. 넣으면 lint 가
    // DuplicatePlatformClasses 로 막고, 프레임워크와 API 가 미묘하게 달라(예: JSONArray.isEmpty
    // 부재) 빌드에서만 통과하고 기기에서 깨질 수 있다.
    // JVM 단위 테스트에는 android.jar 가 없으므로 테스트 클래스패스에만 넣는다.
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "notikit"
            afterEvaluate { from(components["release"]) }
        }
    }
}
