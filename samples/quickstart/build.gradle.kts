plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.0"
}

android {
    namespace = "io.nudgeon.quickstart"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.nudgeon.quickstart"
        minSdk = 26          // SDK 요구사항과 동일
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 실제 값은 -PnudgeonSdkKey / -PnudgeonApiHost 로 넘긴다.
        // 넘기지 않으면 placeholder 로 빌드된다 — 앱은 뜨지만 수집은 되지 않는다.
        buildConfigField("String", "SDK_KEY",
            "\"${providers.gradleProperty("nudgeonSdkKey").getOrElse("pk_replace_me")}\"")
        buildConfigField("String", "API_HOST",
            "\"${providers.gradleProperty("nudgeonApiHost").getOrElse("http://10.0.2.2:8080")}\"")
    }

    buildFeatures { buildConfig = true }

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
    // 핵심 — 로컬 모듈이 아니라 Maven Central에 게시된 아티팩트를 쓴다.
    implementation("io.nudgeon:nudgeon-sdk:0.1.0")

    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
