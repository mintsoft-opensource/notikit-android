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
    // 공개 API 를 suspend 로 두려면 필요하다. api() 로 노출해야 소비자가 호출부에서
    // 코루틴 타입을 볼 수 있다.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // org.json 은 android.jar 가 제공하므로 의존성을 넣지 않는다. 넣으면 lint 가
    // DuplicatePlatformClasses 로 막고, 프레임워크와 API 가 미묘하게 달라(예: JSONArray.isEmpty
    // 부재) 빌드에서만 통과하고 기기에서 깨질 수 있다.
    // JVM 단위 테스트에는 android.jar 가 없으므로 테스트 클래스패스에만 넣는다.
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "notikit"
            afterEvaluate { from(components["release"]) }

            // Maven Central 은 아래 항목이 없으면 업로드를 거부한다.
            // JitPack 은 없어도 받아주지만, 둘 다 지원하려면 채워야 한다.
            pom {
                name.set("Notikit Android SDK")
                description.set("Notikit Android SDK — push device registration, user identity, and automatic notification tap reporting.")
                url.set("https://github.com/mintsoft-opensource/notikit-android")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("notikit")
                        name.set("notikit contributors")
                        url.set("https://github.com/mintsoft-opensource")
                    }
                }
                scm {
                    url.set("https://github.com/mintsoft-opensource/notikit-android")
                    connection.set("scm:git:https://github.com/mintsoft-opensource/notikit-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/mintsoft-opensource/notikit-android.git")
                }
            }
        }
    }
}
