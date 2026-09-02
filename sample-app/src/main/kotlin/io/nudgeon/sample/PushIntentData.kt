package io.nudgeon.sample

/** Keeps only the documented NudgeOn data-message keys when crossing an Intent boundary. */
internal object PushIntentData {
    private val allowedKeys = setOf(
        "message_id",
        "campaign_id",
        "journey_id",
        "title",
        "body",
        "deep_link",
        "image_url",
        "data",
    )

    fun from(values: Map<String, Any?>): Map<String, String> =
        values.mapNotNull { (key, value) ->
            if (key in allowedKeys && value is String) key to value else null
        }.toMap()
}
