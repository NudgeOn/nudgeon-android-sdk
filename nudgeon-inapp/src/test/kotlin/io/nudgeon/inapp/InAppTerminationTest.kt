package io.nudgeon.inapp
import org.junit.Assert.assertEquals
import org.junit.Test
class InAppTerminationTest {
    @Test fun normalCancellationAndActualFailureStayDistinctWithLegacyFallback() {
        assertEquals("cancelled" to "background", terminationEvent("background",false,true,true))
        assertEquals("failed" to "WEBVIEW_ERROR", terminationEvent("WEBVIEW_ERROR",true,true,true))
        assertEquals("dismiss" to "background", terminationEvent("background",false,true,false))
        assertEquals("failed" to "HOST_BLOCKED", terminationEvent("host_blocked",false,false,false))
    }
}
