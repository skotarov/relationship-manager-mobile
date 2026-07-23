package com.onlineimoti.calllog

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRefreshRenderPolicyTest {
    @After
    fun tearDown() {
        HomeRefreshRenderPolicy.clear()
    }

    @Test
    fun keepsRowsForExactlyOneRender() {
        HomeRefreshRenderPolicy.requestKeepExistingRows()

        assertTrue(HomeRefreshRenderPolicy.consumeKeepExistingRows())
        assertFalse(HomeRefreshRenderPolicy.consumeKeepExistingRows())
    }

    @Test
    fun clearCancelsPendingRetention() {
        HomeRefreshRenderPolicy.requestKeepExistingRows()
        HomeRefreshRenderPolicy.clear()

        assertFalse(HomeRefreshRenderPolicy.consumeKeepExistingRows())
    }
}
