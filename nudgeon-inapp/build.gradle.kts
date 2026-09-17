plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("maven-publish"); id("signing") }
group = "io.nudgeon"
version = "0.2.2"
android {
    namespace = "io.nudgeon.inapp"
    compileSdk = 34
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    publishing { singleVariant("release") { withSourcesJar(); withJavadocJar() } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":nudgeon"))
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "nudgeon-inapp"
                pom {
                    name.set("NudgeOn Android In-App SDK")
                    description.set("Optional transparent WebView campaigns and source testing for NudgeOn")
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
                url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
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
