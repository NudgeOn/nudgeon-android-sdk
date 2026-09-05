# NudgeOn Android quickstart

**Maven Central에 게시된 SDK만 사용하는 최소 예제입니다.** 이 저장소의 로컬 `:nudgeon` 모듈을 참조하지
않습니다 — 독립 Gradle 빌드이며, 새 프로젝트에 SDK를 붙일 때 필요한 최소 코드를 그대로 보여 줍니다.

> 이 저장소에는 샘플이 두 개 있습니다.
>
> | | [`sample-app`](../../sample-app) | `samples/quickstart` (여기) |
> |---|---|---|
> | SDK 출처 | 로컬 `:nudgeon` 모듈 | **Maven Central `0.1.0`** |
> | 목적 | 개발 중 코드 검증 | **게시된 아티팩트 검증 · 통합 예제** |
> | 범위 | FCM·딥링크까지 전체 | 초기화 · identify · track · 권한 |

## 실행

```bash
# 저장소 루트에서. -p 로 독립 빌드를 지정한다.
./gradlew -p samples/quickstart assembleDebug
./gradlew -p samples/quickstart installDebug
```

Android SDK 경로가 필요하면 `samples/quickstart/local.properties`(git 무시)에 적습니다.

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

실제 수집을 확인하려면 publishable 키와 API host를 넘기세요. 넘기지 않으면 placeholder로
빌드되어 앱은 뜨지만 이벤트가 서버에 도달하지 않습니다.

```bash
./gradlew -p samples/quickstart installDebug \
  -PnudgeonSdkKey=pk_your_publishable_key \
  -PnudgeonApiHost=http://10.0.2.2:8080     # 에뮬레이터에서 호스트의 :8080
```

## 통합에 필요한 전부

**1. 의존성** — `mavenCentral()` 외에 추가 저장소나 인증이 필요 없습니다.

```kotlin
dependencies {
    implementation("io.nudgeon:nudgeon-sdk:0.1.0")
}
```

**2. 초기화** — 실제 앱에서는 `Application.onCreate`가 더 적절합니다.

```kotlin
NudgeOn.initialize(
    context,
    NudgeOnConfig(sdkKey = "pk_...", apiHost = "https://ingest.example.com"),
)
```

**3. 식별과 이벤트**

```kotlin
NudgeOn.identify("user-123")
NudgeOn.setUserAttributes(mapOf("plan" to "free"))
NudgeOn.track("product_viewed", mapOf("product_id" to "P-1", "price" to 12900))
```

## 이 예제가 다루는 것

- 초기화와 SDK 상태 표시 — device id · anon id · 수신 동의 · OS 권한 · 토큰 등록 여부
- `identify` · `setUserAttributes` · `track` · `flush` · `reset`
- Android 13+ 알림 런타임 권한 요청과 **OS 권한 / 서비스 수신 동의 분리**
  (`POST_NOTIFICATIONS` 허용 ≠ 마케팅 수신 동의. 예제는 사용자가 허용했을 때만
  `setPushSubscription(true)`를 호출합니다)
- 푸시 열림 · 수신 리스너와 콜드 스타트 페이로드 재생

## 다루지 않는 것

FCM 연동, data-only 알림 표시, 딥링크 라우팅은 [`sample-app`](../../sample-app)을 보세요.
Firebase 설정 파일이 필요합니다.

## 알아 둘 것

`reset()`은 **로컬 식별자와 토큰 캐시만** 정리합니다. 서버 측 구독 해제와 이전 계정 연결 해제는
아직 완료되지 않았습니다 — [출시 체크리스트](https://github.com/NudgeOn/nudgeon-platform/blob/main/docs-public/RELEASE-CHECKLIST.md)의
P0-03을 참고하세요.
