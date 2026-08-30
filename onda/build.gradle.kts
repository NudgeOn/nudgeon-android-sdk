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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0") // 백그라운드 플러시 (WorkManager)
    testImplementation("junit:junit:4.13.2")
}
