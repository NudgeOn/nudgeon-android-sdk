// 플러그인 버전 단일 출처 — 각 모듈은 버전 없이 id만 적용한다.
// AGP 8.13.x ↔ Gradle 8.13 (gradle/wrapper) ↔ JDK 17 조합.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
