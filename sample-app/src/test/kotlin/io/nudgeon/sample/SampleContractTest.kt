package io.nudgeon.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleContractTest {
    @Test
    fun intentBoundaryKeepsOnlyDocumentedStringKeys() {
        val result = PushIntentData.from(
            mapOf(
                "message_id" to "m-1",
                "deep_link" to "nudgeon-sample://push/orders/42",
                "image_url" to "https://cdn.example.test/push.png",
                "title" to 123,
                "android.internal" to "ignored",
            ),
        )

        assertEquals("m-1", result["message_id"])
        assertEquals("nudgeon-sample://push/orders/42", result["deep_link"])
        assertEquals("https://cdn.example.test/push.png", result["image_url"])
        assertFalse(result.containsKey("title"))
        assertFalse(result.containsKey("android.internal"))
    }

    @Test
    fun routerAcceptsOnlyTheSampleNamespace() {
        assertEquals("orders/42", SampleDeepLinkRouter.destination("nudgeon-sample://push/orders/42"))
        assertEquals("home", SampleDeepLinkRouter.destination("nudgeon-sample://push"))
        assertNull(SampleDeepLinkRouter.destination("https://example.com/orders/42"))
        assertNull(SampleDeepLinkRouter.destination("not a uri"))
        assertTrue(SampleDeepLinkRouter.destination(null) == null)
    }
}
