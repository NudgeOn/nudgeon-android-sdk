// NudgeOn Android SDK — 코어 모듈 (PRD-01A 3.2)
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

group = "io.nudgeon"
version = "0.1.0"

android {
    namespace = "io.nudgeon.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26 // Android 8+ (PRD-01A 1.2)
        consumerProguardFiles("consumer-rules.pro")
    }
    publishing {
        singleVariant("release") {
            // Maven Central은 sources·javadoc 아티팩트를 모두 요구한다.
            withSourcesJar()
            withJavadocJar()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nudgeon-sdk"
                pom {
                    name.set("NudgeOn Android SDK")
                    description.set("Android SDK for the NudgeOn customer engagement platform")
                    url.set("https://github.com/NudgeOn/nudgeon-android-sdk")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    // Maven Central 검증 필수 항목 — 없으면 배포가 거절된다.
                    developers {
                        developer {
                            id.set("nudgeon")
                            name.set("NudgeOn")
                            url.set("https://github.com/NudgeOn")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/NudgeOn/nudgeon-android-sdk.git")
                        developerConnection.set("scm:git:ssh://git@github.com/NudgeOn/nudgeon-android-sdk.git")
                        url.set("https://github.com/NudgeOn/nudgeon-android-sdk")
                    }
                }
            }
        }

        repositories {
            // 로컬 스테이징 — 여기서 만들어진 트리를 zip으로 묶어 Central Portal에 업로드한다.
            maven {
                name = "centralStaging"
                url = uri(layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }

    // 서명은 키가 있을 때만. 키 없이도 build/test/publishToMavenLocal이 깨지지 않아야 한다.
    signing {
        val key = providers.environmentVariable("SIGNING_KEY").orNull
        val pass = providers.environmentVariable("SIGNING_PASSWORD").orNull
        if (!key.isNullOrBlank()) {
            useInMemoryPgpKeys(key, pass)
            sign(publishing.publications["release"])
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0") // 백그라운드 플러시 (WorkManager)
    implementation("androidx.core:core-ktx:1.13.1")         // NotificationManagerCompat (권한 상태)
    implementation("androidx.lifecycle:lifecycle-process:2.8.3") // 앱 포그라운드 관찰 (R-08 권한 재동기화)
    // 기본 FCM 서비스용. compileOnly — 위임 API만 쓰는 앱엔 Firebase 강제 안 함 (PRD-01A 3.2).
    compileOnly("com.google.firebase:firebase-messaging:24.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 단위 테스트에서 org.json 실제 구현 (android.jar 스텁 회피)
}
