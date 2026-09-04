plugins {
    kotlin("jvm") version "2.2.20"
    `maven-publish`
}

group = "dev.notikit"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
kotlin { jvmToolchain(17) }

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "notikit"
        }
    }
}
