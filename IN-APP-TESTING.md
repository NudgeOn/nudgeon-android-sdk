# In-app module (0.2.0)

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
implementation("io.nudgeon:nudgeon-sdk:0.2.0")
implementation("io.nudgeon:nudgeon-inapp:0.2.0")
```
