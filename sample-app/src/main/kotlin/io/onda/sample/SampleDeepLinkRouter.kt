package io.onda.sample

import java.net.URI

/** The sample only routes its own deep-link namespace; arbitrary URLs remain visible but unopened. */
internal object SampleDeepLinkRouter {
    fun destination(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "onda-sample" || uri.host != "push") return null
        return uri.path?.trim('/')?.ifBlank { "home" } ?: "home"
    }
}
