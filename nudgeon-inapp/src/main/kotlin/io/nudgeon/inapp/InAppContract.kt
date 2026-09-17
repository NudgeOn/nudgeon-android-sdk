package io.nudgeon.inapp

import org.json.JSONObject
import java.security.MessageDigest

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun validateArtifact(artifact: JSONObject) {
    val html = artifact.getString("html")
    val manifest = artifact.getJSONObject("manifest")
    require(html.toByteArray(Charsets.UTF_8).size <= 30 * 1024 * 1024 && sha256(html) == artifact.getString("artifact_sha256")) { "ASSET_HASH_MISMATCH" }
    require(manifest.getInt("format_version") == 1 && manifest.getInt("bridge_version") == 1) { "UNSUPPORTED_FORMAT" }
    require(manifest.getJSONObject("display").getDouble("backdrop_opacity") in 0.0..0.7) { "INVALID_DISPLAY" }
}
data class InAppAction(val type: String, val url: String? = null, val text: String? = null)
data class InAppPairing(val id: String, val confirmationCode: String, val expiresAt: String)
internal class InAppHttpError(val status: Int): Exception("HTTP $status")

internal fun terminationEvent(reason: String, failure: Boolean, shown: Boolean, lifecycleEvents: Boolean): Pair<String,String> =
    if (failure) "failed" to reason else if (lifecycleEvents) "cancelled" to reason else if (shown) "dismiss" to reason else "failed" to "HOST_BLOCKED"
