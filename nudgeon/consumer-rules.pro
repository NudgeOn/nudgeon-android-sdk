# NudgeOn SDK 소비자 ProGuard 규칙.
# 공개 API·데이터 모델(리플렉션/직렬화 대상)을 난독화에서 보호.
-keep class io.nudgeon.sdk.NudgeOn { *; }
-keep class io.nudgeon.sdk.NudgeOnConfig { *; }
-keep class io.nudgeon.sdk.PushPayload { *; }
-keep class io.nudgeon.sdk.SubscriptionState { *; }
-keep enum io.nudgeon.sdk.PushPermissionResult { *; }
-keep class io.nudgeon.sdk.NudgeOnFirebaseMessagingService { *; }
