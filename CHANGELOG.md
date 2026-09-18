# Changelog

## 0.2.3 — 2026-09-18

- 앱 시작 화면 이후 `enableAfterLaunch`로 launch 캠페인을 요청합니다. 기존 enable/foreground 동작은 유지합니다.
- 프로세스 단위 시작 기회 중복 방지, 기본 3초 준비 기한, 지연 응답 표시 차단과 완료 결과 콜백을 추가했습니다.
- 화면/동의/백그라운드 변경 시 준비를 취소하며 시간 초과는 정상 중단으로 기록합니다.
- 업데이트한 서버/콘솔과 호스트의 명시적인 시작 시점 연결이 필요합니다. 코어 푸시 동작은 변경하지 않았습니다.

## 0.2.2 — 2026-09-18

- 캠페인 IANA 시간대와 KST 기준 오늘 하루 안 보기를 지원합니다. 기존 UTC 캠페인은 유지하며, UTC 외 캠페인은 시간대 capability를 지원하는 SDK에만 노출됩니다.
- 정상 백그라운드/화면/세션 전환, 기능 비활성화, 캠페인 중지/만료를 `cancelled` 사유로 보고하고 실제 오류와 분리합니다. 구 서버용 이벤트 형식도 유지합니다.
- HTML 브리지에 캠페인 시간대를 전달하고 네이티브 숨김 안내의 고정 UTC 표기를 제거했습니다.
- 인앱 모듈 변경이며 코어 푸시 API는 변경하지 않았습니다. 업데이트한 NudgeOn API·콘솔이 필요합니다.
- iPhone 15 Pro/iOS 27, Fold3/Android 15에서 정상 중단 및 KST 자정 후 재표시를 검증했습니다.


## 0.1.2 — 2026-09-10

푸시 페이로드 공통 계약([PUSH-CONTRACT.md](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/PUSH-CONTRACT.md)) 대조에서 드러난 어긋남을 닫는다. 0.1.1은 Central에 게시되지 않았으므로 이 버전이 0.1.0 다음 첫 게시본이다.

### 추가
- **SDK가 알림을 그린다** (`NudgeOnConfig.autoDisplayNotifications`, 기본 true). NudgeOn 푸시는 data-only라 OS가 알림을 만들지 않는데, 지금까지는 앱이 직접 `Notification.Builder`를 써야 했다(문서는 "자동 표시"라고 적혀 있었다). 제목·본문, `image_url`이 있으면 BigPicture, 탭하면 앱 런처 액티비티가 열린다. 채널 id/이름·small icon은 config로 지정. 앱이 직접 그리던 경우 **false로 두지 않으면 알림이 두 번 뜬다.**
- **`NudgeOn.handleLaunchIntent(intent)`**: 자동 표시 알림의 탭 Intent를 넘기면 `$push_opened`·`onPushOpened`로 잇고 extras를 일회성으로 비운다.
- **`PushPayload.imageUrl`** (`image_url`). 그동안 서버가 보내도 버렸다.

### 고침
- **같은 message_id의 재수신을 접는다.** 서버 채널 워커는 at-least-once라 공급자 전송 직후 죽으면 같은 메시지가 한 번 더 올 수 있다(플랫폼 M-4 카오스, 3,000건 중 1건). FCM에는 접기 수단이 없어 단말에서 최근 256개 message_id를 기억하고 두 번째 수신은 이벤트도 리스너도 내지 않는다. 탭(`$push_opened`)은 접지 않는다.

### 샘플
- `sample-app`: 자체 알림 코드를 지우고 SDK 표시에 위임. FCM 없이 표시 경로를 태우는 "Preview SDK notification" 버튼.

## 0.1.1 — 2026-09-10

M-1 실단말 검증(갤럭시 Z Fold3, 2026-09-07)에서 찾은 결함 두 건을 고쳤다.

### 고침
- **`registerForPush`가 권한 응답 뒤에 콜백하고 서버 os_permission을 재동기화한다** (#6). 이전에는 `requestPermissions()` 직후 아직 denied인 상태를 콜백하고 끝나서, 사용자가 허용해도 서버가 denied로 남았고 앱이 `setPushToken`을 다시 불러야 했다. 이제 시스템 다이얼로그 응답 뒤(요청한 Activity의 resume 감지) 최종 상태를 콜백하고, 등록된 토큰의 권한을 서버에 재보고한다.
  - **동작 변경**: 권한 요청을 낸 경우 콜백이 동기가 아니라 응답 뒤로 미뤄진다.
  - **신규 API** `NudgeOn.onRequestPermissionsResult(requestCode, permissions, grantResults)`: Activity에서 전달하면 즉시 완료되고, 다이얼로그 없이 거부되는 "다시 묻지 않음" 상태도 잡힌다.
- **identify 실패 재시도** (#6). 네트워크 오류로 실패한 identify가 로그만 남기고 유실되던 결함. 실패분을 (external_id, anon_id) 마커로 영속하고 다음 flush·포그라운드 복귀·앱 재시작에서 재전송한다. `reset()`은 마지막 1회 시도 후 이전 유저 identify를 버린다.

### 샘플
- `sample-app`: SDK가 권한 다이얼로그를 소유하는 경로 버튼, `nudgeon.applicationId`로 패키지명 덮어쓰기 (#7).

### 검증
- JVM 단위 18건, 에뮬레이터(API 35, GMS): 권한 허용 → 서버 `os_permission=granted`, 비행기 모드 identify → 복귀 후 성공.

## 0.1.0 — 2026-09-04

첫 Maven Central 배포 (`io.nudgeon:nudgeon-sdk:0.1.0`). Push MVP 알파.
