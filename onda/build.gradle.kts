// Onda Android SDK — 코어 모듈 (PRD-01A 3.2)
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.onda.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26 // Android 8+ (PRD-01A 1.2)
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    // 기본 FCM 서비스용. compileOnly — 위임 API만 쓰는 앱엔 Firebase 강제 안 함 (PRD-01A 3.2).
    compileOnly("com.google.firebase:firebase-messaging:24.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 단위 테스트에서 org.json 실제 구현 (android.jar 스텁 회피)
}
