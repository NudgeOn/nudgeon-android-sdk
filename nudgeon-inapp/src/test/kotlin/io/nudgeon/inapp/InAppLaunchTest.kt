package io.nudgeon.inapp

import org.junit.Assert.*
import org.junit.Test

class InAppLaunchTest {
    @Test fun recreationDoesNotClaimAgainButDifferentAppsCan() {
        val registry = InAppLaunchRegistry()
        assertTrue(registry.claim("app-a"))
        assertFalse(registry.claim("app-a"))
        assertTrue(registry.claim("app-b"))
        assertTrue(InAppLaunchRegistry().claim("app-a"))
    }
    @Test fun lateAuthorizationCannotShowEvenBeforeTimerRuns() {
        val window = InAppLaunchWindow(3.0,100000)
        assertTrue(window.canPresent(102999))
        assertFalse(window.canPresent(103000))
        assertEquals(InAppLaunchResult.TIMED_OUT,window.complete(InAppLaunchResult.SHOWN,104000))
        assertNull(window.complete(InAppLaunchResult.FAILED,105000))
    }
    @Test fun cancellationIsTerminalAndShownDoesNotTimeOutLater() {
        val window = InAppLaunchWindow(3.0,100000)
        assertEquals(InAppLaunchResult.CANCELLED,window.complete(InAppLaunchResult.CANCELLED,101000))
        assertFalse(window.canPresent(102000))
        assertNull(window.complete(InAppLaunchResult.SHOWN,102000))
        val shown = InAppLaunchWindow(3.0,100000)
        assertEquals(InAppLaunchResult.SHOWN,shown.complete(InAppLaunchResult.SHOWN,102000))
        assertNull(shown.complete(InAppLaunchResult.TIMED_OUT,104000))
    }
    @Test fun displayDurationAlwaysStaysWithinThreeToFiveSeconds() {
        assertEquals(4.0,InAppLaunchWindow.displayDuration(Double.NaN),0.0)
        assertEquals(4.0,InAppLaunchWindow.displayDuration(Double.POSITIVE_INFINITY),0.0)
        assertEquals(3.0,InAppLaunchWindow.displayDuration(-1.0),0.0)
        assertEquals(4.5,InAppLaunchWindow.displayDuration(4.5),0.0)
        assertEquals(5.0,InAppLaunchWindow.displayDuration(100.0),0.0)
    }
    @Test fun invalidTimeoutsStayBounded() {
        assertEquals(13000,InAppLaunchWindow(Double.NaN,10000).deadline)
        assertEquals(13000,InAppLaunchWindow(Double.NEGATIVE_INFINITY,10000).deadline)
        assertEquals(11000,InAppLaunchWindow(-10.0,10000).deadline)
        assertEquals(20000,InAppLaunchWindow(10000.0,10000).deadline)
    }
}
