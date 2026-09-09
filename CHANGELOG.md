# Changelog

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
