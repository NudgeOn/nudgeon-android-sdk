package io.nudgeon.sample

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.nudgeon.sdk.NudgeOn

/**
 * 자체 FirebaseMessagingService 공존 예시 (PRD-01A 3.2). NudgeOn 메시지는 SDK에 위임하면 끝이다 —
 * 알림 표시(제목·본문·이미지·탭 → 앱 진입)는 SDK가 한다(`NudgeOnConfig.autoDisplayNotifications`, 기본 true).
 * 앱이 직접 알림을 그리려면 config에서 끄고 `NudgeOn.onPushReceived` 리스너에서 그린다.
 */
class SampleMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        NudgeOn.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!NudgeOn.handleRemoteMessage(message.data)) {
            super.onMessageReceived(message) // NudgeOn 메시지가 아님 — 앱의 다른 푸시 처리
        }
    }
}
