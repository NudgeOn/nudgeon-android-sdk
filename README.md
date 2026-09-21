# NudgeOn Android SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.nudgeon/nudgeon-sdk?label=Maven%20Central)](https://central.sonatype.com/artifact/io.nudgeon/nudgeon-sdk)
[![CI](https://github.com/NudgeOn/nudgeon-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/NudgeOn/nudgeon-android-sdk/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![status](https://img.shields.io/badge/status-beta--candidate-orange.svg)](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/RELEASE-CHECKLIST.md)

[NudgeOn](https://nudgeon.io) 고객 인게이지먼트 플랫폼의 Android(Kotlin) 네이티브 코어 SDK.
이벤트를 수집하고 푸시를 수신합니다. 공통 이벤트·식별·푸시 API를 제공합니다.

> **파트너 베타 후보입니다.** 코어와 인앱 모듈 0.2.6는 Maven Central에 공개 배포되어 있습니다.
> 플랫폼 전체의 관리형 저장소·목표 부하·24시간 시험과 외부 온보딩 검증은 남아 있습니다.
> 최신 단말·공급자 검증 범위는 [출시 체크리스트](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/RELEASE-CHECKLIST.md)를 확인하세요.
> 서버·SDK의 공통 메시지 식별자 계약은 아래 푸시 계약 문서를 따릅니다.

- **플랫폼 저장소** — [NudgeOn/nudgeon-platform](https://github.com/NudgeOn/nudgeon-platform)
- **API 가이드** — [docs-public/API.md](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/API.md)
- **푸시 계약** — [docs-public/PUSH-CONTRACT.md](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/PUSH-CONTRACT.md)
- **개발자센터** — [nudgeon.io](https://nudgeon.io)
- **인앱 웹 소스 테스트** — [nudgeon-inapp 연결 안내](IN-APP-TESTING.md) (0.2.6)


## 기본 이벤트 (Standard events — 다음 릴리스)

`NudgeOnEvents`로 콘솔과 같은 이벤트 이름을 사용할 수 있습니다. 아래 상수는 이 소스에 추가된 API이며 기존 게시 버전에는 포함되어 있지 않습니다.
SDK를 초기화한 뒤 해당 행동이 성공한 시점에 호출하세요. 예시는 서로 다른 호출 시점을 보여주며, 회원가입·로그인·구입을 한 번에 자동 수집하는 코드는 아닙니다.

```kotlin
import io.nudgeon.sdk.NudgeOn
import io.nudgeon.sdk.NudgeOnEvents

// After SDK initialization and your app's authentication succeeds:
NudgeOn.identify("user-123")
NudgeOn.track(NudgeOnEvents.SIGN_UP, mapOf("method" to "email"))
NudgeOn.track(NudgeOnEvents.LOGIN, mapOf("method" to "email"))
// After order/payment confirmation:
NudgeOn.track(NudgeOnEvents.PURCHASE_COMPLETED, mapOf(
    "order_id" to "order-123", "total_amount" to 29000, "currency" to "KRW", "item_count" to 1
))
```

| 상수 | 전송 이름 | 의미 | 권장 속성 |
|---|---|---|---|
| `NudgeOnEvents.SIGN_UP` | `sign_up` | 회원가입 | method |
| `NudgeOnEvents.LOGIN` | `login` | 로그인 | method |
| `NudgeOnEvents.PURCHASE_COMPLETED` | `purchase_completed` | 구입 | order_id, total_amount, currency, item_count |
| `NudgeOnEvents.PRODUCT_VIEWED` | `product_viewed` | 상품 조회 | product_id, price, currency |
| `NudgeOnEvents.ADD_TO_CART` | `add_to_cart` | 장바구니 담기 | product_id, quantity, price, currency |
| `NudgeOnEvents.CHECKOUT_STARTED` | `checkout_started` | 결제 시작 | cart_id, item_count, total_amount, currency |

금액은 통화의 기본 단위(원·달러 등) 숫자, 통화는 ISO 4217 코드(`KRW`, `USD` 등)를 사용합니다. 속성은 권장 예시이며 서비스별 속성도 추가할 수 있습니다.
기존 `track("custom_event", ...)`는 그대로 지원하며 `purchase` 같은 기존 이름을 자동 변환하지 않습니다.
이름은 대소문자까지 콘솔 설정과 같아야 합니다. 상수 참조 자체는 이벤트를 만들지 않고, `login` 이벤트는 사용자 식별을 대신하지 않습니다.
`track` 이후 오프라인 저장·배치·재시도는 기존 전송 경로를 사용합니다.


## 설치 (Maven Central)

```kotlin
dependencies {
    implementation("io.nudgeon:nudgeon-sdk:0.2.6")
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

> Android 13+에서 `registerForPush` 콜백은 **사용자가 권한 다이얼로그에 응답한 뒤** 최종 상태로 호출되며,
> 그 시점에 서버의 os_permission도 재동기화됩니다(등록된 토큰이 있을 때). 응답 감지는 요청한 Activity의
> resume으로 자동 처리되지만, "다시 묻지 않음" 상태처럼 다이얼로그 없이 즉시 거부되는 경우까지 잡으려면
> Activity의 `onRequestPermissionsResult`에서 `NudgeOn.onRequestPermissionsResult(requestCode, permissions,
> grantResults)`를 전달하세요. 현재 FCM token 조회·전달, data-only 알림 표시는 샘플 앱을 참고하세요.

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

**알림 표시** — NudgeOn 푸시는 data-only라 OS가 알림을 만들지 않고 **SDK가 그린다**(0.1.2+, 기본값). 제목·본문,
`image_url`이 있으면 큰 그림, 탭하면 앱 런처 액티비티가 열린다. 채널 이름·아이콘은 `NudgeOnConfig`로 바꾼다:

```kotlin
NudgeOn.initialize(this, NudgeOnConfig(
    sdkKey = "pk_…", apiHost = "https://…",
    notificationChannelName = "알림",          // 시스템 설정에 보이는 채널 이름
    notificationSmallIcon = R.drawable.ic_stat, // 0이면 앱 아이콘
    // autoDisplayNotifications = false        // 앱이 직접 그릴 때 — onPushReceived 리스너에서 표시
))
```

탭으로 열린 액티비티에서는 Intent를 SDK에 넘겨 `$push_opened`와 `onPushOpened` 리스너(딥링크 라우팅)로 잇는다:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) { …; NudgeOn.handleLaunchIntent(intent) }
override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); NudgeOn.handleLaunchIntent(intent) }
```

페이로드 필드와 무음 푸시 규칙은 플랫폼 저장소의 [PUSH-CONTRACT.md](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/PUSH-CONTRACT.md)를 따른다.

## 아키텍처 (iOS와 대칭)

- 코어가 유일한 상태 보유자: SharedPreferences 식별자 영속, 파일 오프라인 큐(1000건 상한),
  단일 워커 스레드 배치 플러시, 토큰 대사(S-5).
- 모듈: `Identity`·`EventQueue`·`Network`·`PushPayload`·`PushManager`·`EventBus`·`NudgeOnCore`.

## 로드맵

- **M1** ✅ init·identify·track·오프라인 큐
- **M2** ✅ reset·속성·푸시 등록·위임 API·리스너(콜드스타트)·토큰 대사 (현재)
- **M4** ✅ 로컬 샘플 앱 · Maven Central 배포(0.1.0) · 실기기 FCM 수신(0.1.0, 2026-09-07)
- **0.1.1** 같은 message_id 재수신 접기 · **0.1.2** SDK 알림 표시(BigPicture)·`imageUrl`·`handleLaunchIntent`

## 게시 (메인테이너)

1. `CHANGELOG.md`·`nudgeon/build.gradle.kts`의 `version`을 올리고 머지한다.
2. 태그 `X.Y.Z`를 푸시하면 **Central bundle** 워크플로가 서명된 번들을 만든다 → GitHub Release에 첨부한다.
3. **Publish to Maven Central** 워크플로를 `version=X.Y.Z`로 실행한다 (시크릿 `CENTRAL_USERNAME`/`CENTRAL_PASSWORD` = Central Portal User Token). 로컬에서는 `CENTRAL_USERNAME=… CENTRAL_PASSWORD=… scripts/central-publish.sh X.Y.Z`.
4. `repo1.maven.org`에 보이면 README·quickstart 좌표 PR을 머지한다.

## 기여

버그 제보와 PR을 환영합니다. [CONTRIBUTING.md](CONTRIBUTING.md)를 참고하세요.
보안 문제는 공개 이슈 대신 `security@nudgeon.io`로 알려주세요.

## 라이선스

[Apache License 2.0](LICENSE). NudgeOn 이름·워드마크·로고는 이 허여 대상이 아닙니다 —
[상표 정책](TRADEMARKS.md)을 따릅니다.

## 앱 실행 직후 광고 (0.2.4+)

앱 시작 화면과 동의·라우팅이 끝난 뒤 준비된 화면에서 기존 `enable()` 대신 호출합니다.

```kotlin
campaigns.enableAfterLaunch(timeoutSeconds = 3.0, displaySeconds = 4.0) { result ->
    // SHOWN / NO_CAMPAIGN / TIMED_OUT / BLOCKED / CANCELLED / FAILED / ALREADY_HANDLED
    Log.d("LaunchAd", result.name)
}
```

앱 프로세스당 한 번만 시도하며 Scene/Activity/클라이언트 재생성으로 다시 표시하지 않습니다.
광고가 없거나 준비가 늦으면 메인 화면을 그대로 사용합니다. 준비 중 `screen()`을 곧바로
호출하면 시작 시도가 취소됩니다. 객체는 앱 소유자가 보관하고 실제 화면 변경만 전달하세요.
시작 기회를 이미 사용했어도 이후 화면/이벤트 캠페인은 활성 상태로 유지합니다.
시작 광고는 불투명 전면 화면으로 표시되며 실제 표시부터 기본 4초 뒤 자동 종료됩니다.
`displaySeconds`는 3~5초로 제한되며 닫기·오늘 하루 숨김 버튼 없이 메인으로 넘어갑니다.
호스트는 메인 UI 앞에 시작 화면을 유지하고 결과 콜백에서 해제합니다. 최대 3초 fallback을 두세요.
일반 인앱 캠페인의 닫기·숨김 동작과 캠페인 시간대·빈도 제한은 유지됩니다.
서버/콘솔을 먼저 반영하세요. [전체 계약](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/APP-LAUNCH-ADS.md).
