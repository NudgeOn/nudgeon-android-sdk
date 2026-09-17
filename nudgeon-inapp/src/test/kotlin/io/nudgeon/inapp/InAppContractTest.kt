package io.nudgeon.inapp
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class InAppContractTest {
    private fun artifact() = JSONObject().put("html","<html>Test</html>").put("artifact_sha256",sha256("<html>Test</html>"))
        .put("manifest",JSONObject().put("format_version",1).put("bridge_version",1).put("display",JSONObject().put("backdrop_opacity",0.4)))
    @Test fun validatesIntegrity() { validateArtifact(artifact()); assertThrows(IllegalArgumentException::class.java) { validateArtifact(artifact().put("html","tampered")) } }
    @Test fun rejectsUnsupportedProtocol() { val a=artifact();a.getJSONObject("manifest").put("bridge_version",2);assertThrows(IllegalArgumentException::class.java) { validateArtifact(a) } }
}
