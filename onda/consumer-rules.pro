# Onda SDK 소비자 ProGuard 규칙.
# 공개 API·데이터 모델(리플렉션/직렬화 대상)을 난독화에서 보호.
-keep class io.onda.sdk.Onda { *; }
-keep class io.onda.sdk.OndaConfig { *; }
-keep class io.onda.sdk.PushPayload { *; }
-keep class io.onda.sdk.SubscriptionState { *; }
-keep enum io.onda.sdk.PushPermissionResult { *; }
-keep class io.onda.sdk.OndaFirebaseMessagingService { *; }
