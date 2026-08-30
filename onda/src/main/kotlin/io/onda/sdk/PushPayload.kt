package io.onda.sdk

import org.json.JSONObject

/**
 * 푸시 페이로드 (PRD-01A 2.5). FCM data 메시지(Map<String,String>) → 구조화. iOS PushPayload와 대칭.
 * Onda 발송 규약(data 키): message_id, campaign_id?, journey_id?, title, body, deep_link?, data?(JSON 문자열).
 */
data class PushPayload(
    val messageId: String,
    val campaignId: String?,
    val journeyId: String?,
    val title: String,
    val body: String,
    val deepLink: String?,
    val data: Map<String, String>,
) {
    companion object {
        /**
         * FCM data 맵에서 파싱. message_id 없으면 Onda 메시지가 아니므로 null
         * (타 푸시 SDK 공존 위임 — PRD-01A 3.2).
         */
        fun parse(data: Map<String, String>): PushPayload? {
            val messageId = data["message_id"] ?: return null
            val extra: Map<String, String> = data["data"]?.let { raw ->
                runCatching {
                    val o = JSONObject(raw)
                    o.keys().asSequence().associateWith { k -> o.get(k).toString() }
                }.getOrDefault(emptyMap())
            } ?: emptyMap()
            return PushPayload(
                messageId = messageId,
                campaignId = data["campaign_id"],
                journeyId = data["journey_id"],
                title = data["title"] ?: "",
                body = data["body"] ?: "",
                deepLink = data["deep_link"],
                data = extra,
            )
        }
    }
}

/** registerForPush 결과 (PRD-01A 2.4). Android는 provisional 개념 없음 — granted|denied. */
enum class PushPermissionResult { GRANTED, DENIED, PROVISIONAL }

/** 구독 상태 (PRD-01A 2.4). OS 권한과 서비스 opt-in은 별개 축. */
data class SubscriptionState(
    val serviceOptIn: Boolean,
    val osPermission: String, // authorized|denied|not_determined
    val tokenRegistered: Boolean,
)
