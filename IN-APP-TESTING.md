# In-app module (0.2.3)

`nudgeon-inapp` is an optional Android API 26+ module. Available on Maven Central starting with 0.2.0 as `io.nudgeon:nudgeon-inapp`. It connects to the NudgeOn source workbench and never enables production campaigns automatically.

```kotlin
val client = InAppTestClient(
    application = application,
    configuration = InAppTestClient.Configuration(
        apiUrl = "https://api.example.com", sdkKey = sdkKey,
        allowedSchemes = setOf("myapp"), allowedWebHosts = setOf("example.com")
    ),
    host = { currentActivity },
    isAllowed = { mayShowEvent },
    onAction = { action -> /* route action.url with the app router */ }
)
// Main thread, app-owned debug/settings action after the person requests a test:
client.showConnection(currentActivity)
```

Paste the console pairing code, compare the displayed number and confirm in the console. Keep the app open. Connection lasts 30 minutes; test credentials remain in memory. Call `contextChanged()` on identity/consent/screen changes, `end()` to disconnect, and `destroy()` when the owning app component is disposed.

WebView must support `WEB_MESSAGE_LISTENER`. The module never falls back to an unrestricted JavascriptInterface. Native close/back, Activity changes and a five-minute watchdog can dismiss without JavaScript permission. HTTPS content and deep-link targets require explicit host/scheme allow lists. No push token is needed. HTML never receives the SDK key or test credential.

Build and test:

```sh
./gradlew :nudgeon-inapp:assembleDebug :nudgeon-inapp:testDebugUnitTest
```

Source formats, server setup and complete test flow are documented in `nudgeon-platform/docs-public/IN-APP-WORKBENCH.md`. JVM contract tests do not verify rendering on an actual WebView.

The sample app includes an **In-app event test** button and `InAppTestActivity`. Its action handler logs the validated destination, so testing does not unexpectedly navigate away. The API/key come from the sample app’s existing local.properties or Gradle configuration.

## 운영 캠페인 모듈 (0.2.0)

`InAppCampaignClient`는 테스트 페어링 없이 게시된 공개 콘텐츠를 조회합니다. 설치 자격은 기기 보호 저장소에 보관하고, 기간·트리거·빈도 제한을 서버에서 확인합니다. 호스트가 표시 허용, 화면/이벤트, 계정·동의 변경을 연결해야 합니다.

자세한 설정과 양쪽 SDK 예시는 플랫폼의 [캠페인 사용 안내](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/IN-APP-CAMPAIGNS.md)에 있습니다. 플랫폼은 인앱 API와 migration 0010–0012가 포함된 버전이 필요합니다. 테스트 모드를 열 때 운영 클라이언트를 disable하세요.

## Durable campaign telemetry

Live campaign events are written atomically to an installation-scoped journal before sending (up to 1,000 records, seven-day retention). They replay in order with stable event IDs after restart; transient failures use exponential backoff up to 60 seconds plus jitter. The server accepts historical events for seven days without reopening an expired or paused delivery. Permanent 400/404/409 responses discard that event; 401 disables the client without rotating installation identity. Storage failures emit `EVENT_STORAGE_FAILED`. `forgetInstallation` clears the journal. Test-pairing sessions remain memory-only and require reconnecting after restart.

```kotlin
implementation("io.nudgeon:nudgeon-sdk:0.2.3")
implementation("io.nudgeon:nudgeon-inapp:0.2.3")
```

## HTML 안의 오늘 하루 안 보기 (0.2.1+)

업데이트한 NudgeOn 서버에서 저장한 소스는 `window.nudgeonBridge.hideToday()`를 호출할 수 있습니다. 표시 중인 라이브 캠페인에서 impression → hide_today → dismiss(hide_today)를 기록하고 닫습니다. 동일 설치·캠페인을 캠페인 시간대의 다음 자정까지 제외합니다. SDK 0.2.3와 업데이트한 서버에서 `Asia/Seoul`을 설정하면 한국 시간 자정이며, 기존 설정의 기본값은 UTC입니다. 별도 manifest 액션 등록은 필요 없습니다.

테스트 연결 모드에서는 `LIVE_CAMPAIGN_REQUIRED`로 거절하고 팝업을 유지합니다. 콘솔 미리보기에서는 모의 실행임을 표시합니다. 기존 SDK 0.2.0은 HTML 호출을 지원하지 않으므로 0.2.1 이상이 필요합니다. 일반 `dismiss()`는 오늘 하루 숨김을 적용하지 않습니다.

## 캠페인 시간대·정상 중단 (0.2.3+)

서버·콘솔을 먼저 업데이트한 뒤 SDK를 적용하세요. SDK는 `campaign-time-zone` capability를 보내고, 불변 게시 버전의 시간대를 네이티브 숨김 안내 및 HTML 브리지에 전달합니다. 새 서버에서 다시 저장한 소스의 `window.nudgeonBridge.timeZone`은 읽기 전용이며, 구 소스/서버의 기본값은 UTC입니다. UTC 외 캠페인은 0.2.3 이상에만 표시됩니다. API에서 시간대를 생략한 기존 캠페인은 UTC를 유지합니다.

라이브 캠페인의 백그라운드·화면/세션/표시 조건 변경·비활성화·서버 중지·기간 만료는 `cancelled`와 사유로 전송하며 실제 렌더링/통신 오류는 `failed`로 유지합니다. 구 서버에서는 표시 후 정상 종료를 `dismiss`, 표시 전 중단을 `failed(HOST_BLOCKED)`로 보내 호환성을 유지합니다. 테스트 연결의 로그와는 별도입니다.

검증: iPhone 15 Pro/iOS 27 및 Fold3/Android 15에서 KST 숨김 만료·실제 자정 후 재노출, 정상 중단, 수정 예제 레이아웃 확인. [플랫폼 검증 기록](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/IN-APP-DEVICE-QA-2026-09-17.md)을 참고하세요.
