package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedPagesExplicitResetTest {
    @Test
    fun lateRenderDoesNotRequestRetainedPageReset() {
        assertFalse(RetainedPagesResetPolicy.shouldReset(explicitContextReset = false))
    }

    @Test
    fun explicitContextChangeCanResetRetainedPages() {
        assertTrue(RetainedPagesResetPolicy.shouldReset(explicitContextReset = true))
    }
}
