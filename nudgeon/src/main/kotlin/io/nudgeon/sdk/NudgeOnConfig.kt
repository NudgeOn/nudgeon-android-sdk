package io.nudgeon.sdk

/** SDK 설정 (PRD-01A 2.1). iOS NudgeOnConfig와 대칭. */
data class NudgeOnConfig(
    val sdkKey: String,
    val apiHost: String, // 셀프호스팅 시 교체 — SaaS 기본값과 동등 취급
    val flushIntervalSeconds: Long = 10,
    val flushBatchSize: Int = 10,
    val autoTrackSessions: Boolean = true,
    val autoRegisterPushToken: Boolean = true,
    /**
     * FCM data-only 푸시를 SDK가 시스템 알림으로 표시(기본 true — PUSH-CONTRACT.md "자동 표시").
     * 앱이 직접 알림을 그리면 false로 두고 `onPushReceived` 리스너에서 표시한다.
     */
    val autoDisplayNotifications: Boolean = true,
    /** 자동 표시 알림 채널 id/이름 (Android 8+). 이름은 시스템 설정에 보이므로 앱 언어에 맞게 지정. */
    val notificationChannelId: String = "nudgeon_default",
    val notificationChannelName: String = "Notifications",
    /** 자동 표시 알림의 small icon 리소스. 0이면 앱 아이콘. */
    val notificationSmallIcon: Int = 0,
)
