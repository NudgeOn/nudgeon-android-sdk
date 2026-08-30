# Onda Android SDK

Onda 고객 인게이지먼트 플랫폼의 Android(Kotlin) 네이티브 코어 SDK.
공개 인터페이스 명세: `onda-platform/docs/prd/PRD-01A`.

> 상태: **M1 골격**. 공개 API 표면 확정, 코어 구현(큐·네트워크·플러시) 진행 예정.

## 설치 (Maven Central — 예정)

```kotlin
implementation("io.onda:onda-android:0.1.0")
```

## 빠른 시작

```kotlin
import io.onda.sdk.Onda
import io.onda.sdk.OndaConfig

Onda.initialize(context, OndaConfig(sdkKey = "pk_...", apiHost = "https://ingest.example.com"))
Onda.identify("user-123")
Onda.track("product_viewed", mapOf("product_id" to "P-1", "price" to 12900))
Onda.reset() // 로그아웃 시 필수
```

## 아키텍처 (iOS와 대칭)

- 코어가 유일한 상태 보유자: SharedPreferences 식별자 영속, SQLite/파일 오프라인 큐,
  WorkManager 백그라운드 플러시, 토큰 라이프사이클.
- 푸시(M2): `OndaFirebaseMessagingService` + 타사 FMS 공존 위임 API `Onda.handleRemoteMessage()`,
  Android 13+ POST_NOTIFICATIONS 권한.

## 로드맵

- **M1** init·identify·track·오프라인 큐 (공개 API 확정, 구현 진행)
- **M2** reset·속성·푸시 등록·위임 API·리스너
- **M4** 데모 앱·계약 테스트·Maven Central 배포

MIT License.
