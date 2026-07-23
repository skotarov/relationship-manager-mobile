package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRenderReusePolicyTest {
    @Test
    fun reusesRowsOnlyWhenSameDataIsStillRendered() {
        assertTrue(HomeRenderReusePolicy.canReuseExistingRows(
            unchanged = true,
            forceRender = false,
            hasRenderedRows = true,
        ))
    }

    @Test
    fun rebuildsWhenContainerIsEmptyEvenIfDataIsUnchanged() {
        assertFalse(HomeRenderReusePolicy.canReuseExistingRows(
            unchanged = true,
            forceRender = false,
            hasRenderedRows = false,
        ))
    }

    @Test
    fun forcedRenderAlwaysRebuilds() {
        assertFalse(HomeRenderReusePolicy.canReuseExistingRows(
            unchanged = true,
            forceRender = true,
            hasRenderedRows = true,
        ))
    }

    @Test
    fun changedDataAlwaysRebuilds() {
        assertFalse(HomeRenderReusePolicy.canReuseExistingRows(
            unchanged = false,
            forceRender = false,
            hasRenderedRows = true,
        ))
    }
}
