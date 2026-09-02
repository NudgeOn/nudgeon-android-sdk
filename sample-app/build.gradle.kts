import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val hasGoogleServices = file("google-services.json").isFile
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

// 실기기 테스트 설정 우선순위: -PondaSdkKey/-PondaApiHost > 루트 local.properties(onda.sdkKey/onda.apiHost, git 무시) > placeholder.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun localProp(key: String): Provider<String> = providers.provider { localProps.getProperty(key)?.takeIf { it.isNotBlank() } }

val ondaSdkKey = providers.gradleProperty("ondaSdkKey")
    .orElse(localProp("onda.sdkKey"))
    .orElse("pk_sample_replace_me")
val ondaApiHost = providers.gradleProperty("ondaApiHost")
    .orElse(localProp("onda.apiHost"))
    .orElse("http://10.0.2.2:8080")

android {
    namespace = "io.onda.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.onda.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ONDA_SDK_KEY", ondaSdkKey.get().asBuildConfigString())
        buildConfigField("String", "ONDA_API_HOST", ondaApiHost.get().asBuildConfigString())
        buildConfigField("boolean", "HAS_GOOGLE_SERVICES", hasGoogleServices.toString())
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":onda"))
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.firebase:firebase-messaging:25.0.1")

    testImplementation("junit:junit:4.13.2")
}
