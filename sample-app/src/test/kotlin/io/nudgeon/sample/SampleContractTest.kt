package io.nudgeon.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleContractTest {
    @Test
    fun routerAcceptsOnlyTheSampleNamespace() {
        assertEquals("orders/42", SampleDeepLinkRouter.destination("nudgeon-sample://push/orders/42"))
        assertEquals("home", SampleDeepLinkRouter.destination("nudgeon-sample://push"))
        assertNull(SampleDeepLinkRouter.destination("https://example.com/orders/42"))
        assertNull(SampleDeepLinkRouter.destination("not a uri"))
        assertTrue(SampleDeepLinkRouter.destination(null) == null)
    }
}
