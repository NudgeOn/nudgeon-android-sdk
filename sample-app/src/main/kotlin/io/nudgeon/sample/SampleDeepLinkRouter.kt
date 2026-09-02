package io.nudgeon.sample

import java.net.URI

/** The sample only routes its own deep-link namespace; arbitrary URLs remain visible but unopened. */
internal object SampleDeepLinkRouter {
    fun destination(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "nudgeon-sample" || uri.host != "push") return null
        return uri.path?.trim('/')?.ifBlank { "home" } ?: "home"
    }
}
