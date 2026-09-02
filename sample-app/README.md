# NudgeOn Android SDK sample

`sample-app`은 로컬 `:nudgeon` 모듈을 사용하는 최소 Android 앱입니다. 다음 공개 경로를 직접 보여 줍니다.

- `Application`에서 publishable `pk_` 키와 API host로 초기화
- `identify` / 사용자 속성 / `track` / `flush` / `reset`
- Activity Result로 Android 13+ 알림 권한 결정을 받은 뒤 NudgeOn 상태 동기화
- 현재 FCM token 조회 후 `NudgeOn.setPushToken`
- data-only FCM 수신 → `NudgeOn.handleRemoteMessage` → 로컬 알림 생성
- 알림 탭 → `PendingIntent` → `NudgeOn.handlePushOpened` → `nudgeon-sample://push/...` 라우팅

## 1. 기본 빌드와 실행

Firebase 설정 없이도 앱 자체는 빌드·실행됩니다. 저장소 루트에서:

```bash
./gradlew :sample-app:assembleDebug
./gradlew :sample-app:installDebug
```

기본값은 안전한 placeholder 키 `pk_sample_replace_me`와 Android Emulator용 로컬 주소
`http://10.0.2.2:8080`입니다. 실제 수집을 확인하려면 publishable SDK key와 API host를 설정하세요.
설정 우선순위는 `-P` 프로퍼티 > 루트 `local.properties` > placeholder 입니다.

**방법 A — 루트 `local.properties`(git 무시)에 적어 두기 (실기기 반복 설치에 권장):**

```properties
sdk.dir=/Users/you/Library/Android/sdk
nudgeon.sdkKey=pk_your_publishable_key
nudgeon.apiHost=http://192.168.0.10:8080
```

**방법 B — 한 번만 명령줄로 전달:**

```bash
./gradlew :sample-app:installDebug \
  -PnudgeonSdkKey=pk_your_publishable_key \
  -PnudgeonApiHost=http://10.0.2.2:8080
```

실기기는 `10.0.2.2`(에뮬레이터 전용)가 아니라 같은 네트워크의 API 호스트 IP 또는 공개 HTTPS 주소를 쓰세요.
로컬 HTTP 허용은 debug 빌드에만 켜져 있습니다. release 빌드는 HTTPS host를 사용하세요.

## 2. FCM 연결

1. Firebase 프로젝트에 Android 앱 `io.nudgeon.sample`을 등록합니다.
2. 실제 `google-services.json`을 이 디렉터리에 둡니다. 파일은 Git에서 무시됩니다.
3. 앱을 다시 빌드합니다. 파일이 있을 때만 Google Services Gradle plugin이 적용됩니다.
4. 앱을 실행하면 시작 시 FCM token을 조회해 화면 상단 **FCM token** 칸에 전체 값을 표시하고
   `NudgeOn.setPushToken`으로 전달합니다. 길게 눌러 복사한 뒤 콘솔/서버 테스트 발송에 사용하세요.
   **Request notification permission**으로 Android 13+ 권한을 허용해야 알림이 표시됩니다.
   **Fetch and sync current FCM token**은 수동 재조회 버튼입니다.
5. 상태 칸의 `device_id`는 서버에 등록되는 NudgeOn 디바이스 식별자입니다. 푸시가 도착하면 Activity log에
   `푸시 수신: message_id=… title=… body=…`가 찍히고, 알림을 탭하면 `handlePushOpened` 경로가 기록됩니다.

`google-services.json.example`은 필드 모양만 보여 주는 placeholder이며 실제 Firebase 설정이 아닙니다.

## 3. data-only 푸시 계약

샘플 서비스는 아래 문자열 data key를 받습니다. `message_id`가 없으면 NudgeOn 메시지가 아니므로 처리하지 않습니다.

```json
{
  "message_id": "sample-message-1",
  "campaign_id": "sample-campaign",
  "title": "NudgeOn sample",
  "body": "Tap to open the sample route",
  "deep_link": "nudgeon-sample://push/orders/42",
  "data": "{\"order_id\":\"42\"}"
}
```

SDK의 기본 `NudgeOnFirebaseMessagingService`는 data 위임만 하고 알림 UI를 만들지 않습니다. 그래서 이 앱의
`SampleMessagingService`가 수신 이벤트를 SDK에 전달한 다음 NotificationChannel·알림·PendingIntent를
만듭니다. 알림을 탭하면 `MainActivity.onCreate` 또는 `onNewIntent`가 같은 data를
`NudgeOn.handlePushOpened`로 전달합니다.

FCM 없이 앱 딥링크만 확인하려면:

```bash
adb shell am start -W \
  -a android.intent.action.VIEW \
  -d 'nudgeon-sample://push/orders/42' \
  io.nudgeon.sample
```

알림 탭으로 전달되는 NudgeOn data 경계를 로컬에서 흉내 내려면:

```bash
adb shell am start -W \
  -n io.nudgeon.sample/.MainActivity \
  --es message_id local-open-1 \
  --es title 'Local push open' \
  --es deep_link 'nudgeon-sample://push/orders/42'
```

## 현재 검증 경계

- placeholder key로는 서버가 실제 이벤트를 수락하지 않습니다.
- 실제 FCM token·전달·알림 탭은 유효한 Firebase 설정과 에뮬레이터/기기에서 확인해야 합니다.
- `identify`와 `reset`은 비동기이고 `track`은 호출 시점 identity를 읽습니다. 샘플은 연속 호출의 즉시 사용자
  귀속을 보장하지 않으며, 각 버튼도 요청/큐 등록만 표시합니다.
- `flush`와 token 등록에는 앱이 읽을 수 있는 서버 완료 콜백이 없습니다. 로컬 상태 표시를 수집 접수,
  푸시 전달 또는 저니 실행 완료 증거로 사용하지 마세요.
- 현재 SDK의 `setPushSubscription`과 `reset`은 로컬 상태/캐시를 바꾸며, 서버의 구독 변경·기기 logout
  endpoint 완료까지 증명하지 않습니다. 샘플 UI도 이를 “Local” 상태로 표시합니다.
