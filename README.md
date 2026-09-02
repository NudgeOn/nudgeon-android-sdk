# NudgeOn Android SDK

NudgeOn 고객 인게이지먼트 플랫폼의 Android(Kotlin) 네이티브 코어 SDK.
공개 인터페이스 명세: `nudgeon-platform/docs/prd/PRD-01A`.

> 상태: **M2 코어 + 로컬 샘플 앱** — init · identify · track · 오프라인 큐 · reset · 속성 · 푸시 등록 · 리스너(콜드스타트) · 토큰 대사 · FMS 위임 API. iOS SDK와 API 동형.

## 설치 (Maven Central — 예정)

```kotlin
implementation("io.nudgeon:nudgeon-android:0.1.0")
```

## 빠른 시작

```kotlin
import io.nudgeon.sdk.NudgeOn
import io.nudgeon.sdk.NudgeOnConfig

NudgeOn.initialize(context, NudgeOnConfig(sdkKey = "pk_...", apiHost = "https://ingest.example.com"))
NudgeOn.identify("user-123")
NudgeOn.track("product_viewed", mapOf("product_id" to "P-1", "price" to 12900))
NudgeOn.reset() // 현재 로컬 identity/token cache reset; 서버 logout 완료를 뜻하지 않음

// 푸시 (M2)
NudgeOn.registerForPush(activity) { result -> /* GRANTED | DENIED */ }
NudgeOn.onPushOpened { payload -> router.route(payload.deepLink) } // 콜드 스타트 유실 없음
val initial = NudgeOn.getInitialPushPayload()
```

> Android 13+에서 현재 `registerForPush` 콜백은 권한 요청 직후 상태를 반환합니다. 사용자 선택 완료,
> 현재 FCM token 조회·전달, data-only 알림 표시는 아래 샘플 앱의 Activity Result/FMS 연동을 참고하세요.

## 샘플 앱

[`sample-app`](sample-app)은 로컬 `:nudgeon` 모듈을 사용해 식별·이벤트·알림 권한·FCM token·data-only
푸시 수신·알림 탭·딥링크까지 연결합니다. Firebase 설정 파일이 없어도 기본 debug 앱은 빌드됩니다.

```bash
./gradlew :sample-app:assembleDebug
```

실제 로컬 수집과 FCM 연결 방법, placeholder 경계는 [`sample-app/README.md`](sample-app/README.md)를
참조하세요.

## 푸시 통합 (PRD-01A 3.2)

**기본 경로** — 매니페스트에 서비스 등록 (firebase-messaging 필요):

```xml
<service android:name="io.nudgeon.sdk.NudgeOnFirebaseMessagingService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT"/></intent-filter>
</service>
```

**공존 경로** — 자체 FMS를 이미 쓰는 앱은 위임 API로 라우팅 (Firebase 강제 없음):

```kotlin
class MyFms : FirebaseMessagingService() {
    override fun onNewToken(t: String) { NudgeOn.setPushToken(t) }
    override fun onMessageReceived(m: RemoteMessage) {
        if (!NudgeOn.handleRemoteMessage(m.data)) { /* 우리 메시지 처리 */ }
    }
}
```

## 아키텍처 (iOS와 대칭)

- 코어가 유일한 상태 보유자: SharedPreferences 식별자 영속, 파일 오프라인 큐(1000건 상한),
  단일 워커 스레드 배치 플러시, 토큰 대사(S-5).
- 모듈: `Identity`·`EventQueue`·`Network`·`PushPayload`·`PushManager`·`EventBus`·`NudgeOnCore`.

## 로드맵

- **M1** ✅ init·identify·track·오프라인 큐
- **M2** ✅ reset·속성·푸시 등록·위임 API·리스너(콜드스타트)·토큰 대사 (현재)
- **M4** ✅ 로컬 샘플 앱 · 플랫폼 공통 계약 테스트/실기기 FCM/Maven Central 배포는 후속

Licensed under the [Apache License 2.0](LICENSE).
