package io.nudgeon.sdk

/** SDK 설정 (PRD-01A 2.1). iOS NudgeOnConfig와 대칭. */
data class NudgeOnConfig(
    val sdkKey: String,
    val apiHost: String, // 셀프호스팅 시 교체 — SaaS 기본값과 동등 취급
    val flushIntervalSeconds: Long = 10,
    val flushBatchSize: Int = 10,
    val autoTrackSessions: Boolean = true,
    val autoRegisterPushToken: Boolean = true,
)
