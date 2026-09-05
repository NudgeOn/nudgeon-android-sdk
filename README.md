# NudgeOn Android SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.nudgeon/nudgeon-sdk?label=Maven%20Central)](https://central.sonatype.com/artifact/io.nudgeon/nudgeon-sdk)
[![CI](https://github.com/NudgeOn/nudgeon-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/NudgeOn/nudgeon-android-sdk/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![status](https://img.shields.io/badge/status-alpha-orange.svg)](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/RELEASE-CHECKLIST.md)

[NudgeOn](https://nudgeon.io) 고객 인게이지먼트 플랫폼의 Android(Kotlin) 네이티브 코어 SDK.
이벤트를 수집하고 푸시를 수신합니다. iOS SDK와 API가 동형입니다.

> ⚠️ **알파입니다. 프로덕션에 쓰지 마세요.**
> 코어 경로(init · identify · track · 오프라인 큐 · reset · 속성 · 푸시 등록 · 리스너 · 토큰 대사 · FMS 위임)는
> 동작하지만, 아래가 아직 완료되지 않았습니다.
>
> - **`message_id` 연결** — 서버·iOS·Android가 푸시 payload에서 식별자를 읽는 방식이 아직 통일되지 않았습니다. 발송·도달·리포트 간 조인이 보장되지 않습니다
> - **수신 동의 · 로그아웃 · 토큰 소유권** 서버 동기화 — `reset()`은 현재 로컬 상태만 정리합니다
> - **실기기 · 실공급자 발송 검증**
>
> API와 스키마는 예고 없이 바뀔 수 있습니다. 진행 상황은
> [출시 체크리스트](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/RELEASE-CHECKLIST.md)를 보세요.

- **플랫폼 저장소** — [NudgeOn/nudgeon-platform](https://github.com/NudgeOn/nudgeon-platform)
- **API 가이드** — [docs-public/API.md](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/API.md)
- **푸시 계약** — [docs-public/PUSH-CONTRACT.md](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/PUSH-CONTRACT.md)
- **개발자센터** — [nudgeon.io](https://nudgeon.io)

## 설치 (Maven Central)

```kotlin
dependencies {
    implementation("io.nudgeon:nudgeon-sdk:0.1.0")
}
```

`mavenCentral()`만 있으면 됩니다. 별도 저장소·인증 설정이 필요 없습니다.
Android 8(API 26) 이상, JVM 17 타깃입니다.

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

## 푸시 통합

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
- **M4** ✅ 로컬 샘플 앱 · Maven Central 배포(0.1.0) · 플랫폼 공통 계약 테스트와 실기기 FCM 검증은 후속

## 기여

버그 제보와 PR을 환영합니다. [CONTRIBUTING.md](CONTRIBUTING.md)를 참고하세요.
보안 문제는 공개 이슈 대신 `security@nudgeon.io`로 알려주세요.

## 라이선스

[Apache License 2.0](LICENSE). NudgeOn 이름·워드마크·로고는 이 허여 대상이 아닙니다 —
[상표 정책](TRADEMARKS.md)을 따릅니다.
