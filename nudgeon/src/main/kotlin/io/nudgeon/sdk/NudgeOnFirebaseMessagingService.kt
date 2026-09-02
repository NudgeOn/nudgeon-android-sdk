package io.nudgeon.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 기본 FCM 서비스 (PRD-01A 3.2). 매니페스트에 등록하면 NudgeOn 메시지를 자동 처리한다.
 * 자체 FirebaseMessagingService를 이미 쓰는 앱은 이 서비스를 등록하지 말고
 * `NudgeOn.handleRemoteMessage(msg.data)` 위임 API를 호출한다 (멀티 푸시 SDK 공존).
 *
 * firebase-messaging은 compileOnly 의존 — 위임 API만 쓰는 앱엔 Firebase가 강제되지 않는다.
 */
open class NudgeOnFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        NudgeOn.setPushToken(token) // 토큰 대사 (S-5)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val handled = NudgeOn.handleRemoteMessage(message.data) // 포그라운드/백그라운드 데이터 수신
        if (!handled) super.onMessageReceived(message)
        // 알림 표시는 앱/서버 notification 페이로드에 따르며, 도달 지표는 서버 발송 계측으로 대체.
    }
}
