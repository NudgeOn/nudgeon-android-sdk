# Onda Android SDK

Onda 고객 인게이지먼트 플랫폼의 Android(Kotlin) 네이티브 코어 SDK.
공개 인터페이스 명세: `onda-platform/docs/prd/PRD-01A`.

> 상태: **M2 코어 완성** — init · identify · track · 오프라인 큐 · reset · 속성 · 푸시 등록 · 리스너(콜드스타트) · 토큰 대사 · FMS 위임 API. iOS SDK와 API 동형.
> (로컬 툴체인(kotlinc/gradle) 부재로 이 환경에서 미컴파일 — CI/로컬 gradle 검증 필요.)

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

// 푸시 (M2)
Onda.registerForPush(activity) { result -> /* GRANTED | DENIED */ }
Onda.onPushOpened { payload -> router.route(payload.deepLink) } // 콜드 스타트 유실 없음
val initial = Onda.getInitialPushPayload()
```

## 푸시 통합 (PRD-01A 3.2)

**기본 경로** — 매니페스트에 서비스 등록 (firebase-messaging 필요):

```xml
<service android:name="io.onda.sdk.OndaFirebaseMessagingService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT"/></intent-filter>
</service>
```

**공존 경로** — 자체 FMS를 이미 쓰는 앱은 위임 API로 라우팅 (Firebase 강제 없음):

```kotlin
class MyFms : FirebaseMessagingService() {
    override fun onNewToken(t: String) { Onda.setPushToken(t) }
    override fun onMessageReceived(m: RemoteMessage) {
        if (!Onda.handleRemoteMessage(m.data)) { /* 우리 메시지 처리 */ }
    }
}
```

## 아키텍처 (iOS와 대칭)

- 코어가 유일한 상태 보유자: SharedPreferences 식별자 영속, 파일 오프라인 큐(1000건 상한),
  단일 워커 스레드 배치 플러시, 토큰 대사(S-5).
- 모듈: `Identity`·`EventQueue`·`Network`·`PushPayload`·`PushManager`·`EventBus`·`OndaCore`.

## 로드맵

- **M1** ✅ init·identify·track·오프라인 큐
- **M2** ✅ reset·속성·푸시 등록·위임 API·리스너(콜드스타트)·토큰 대사 (현재)
- **M4** 데모 앱·계약 테스트·Maven Central 배포

MIT License.
