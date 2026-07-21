package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickyGroupHeaderPolicyTest {
    @Test
    fun hidesBeforeFirstGroupTitleLeavesViewport() {
        assertNull(StickyGroupHeaderPolicy.resolve(listOf(0, 240), overlayHeight = 32))
    }

    @Test
    fun keepsCurrentGroupFixedBetweenTitles() {
        assertEquals(
            StickyGroupHeaderState(activeIndex = 0, translationY = 0),
            StickyGroupHeaderPolicy.resolve(listOf(-120, 240), overlayHeight = 32),
        )
    }

    @Test
    fun nextGroupPushesCurrentTitleOut() {
        assertEquals(
            StickyGroupHeaderState(activeIndex = 0, translationY = -12),
            StickyGroupHeaderPolicy.resolve(listOf(-500, 20), overlayHeight = 32),
        )
    }

    @Test
    fun nextGroupReplacesPreviousAfterCrossingTop() {
        assertEquals(
            StickyGroupHeaderState(activeIndex = 1, translationY = 0),
            StickyGroupHeaderPolicy.resolve(listOf(-500, -1), overlayHeight = 32),
        )
    }
}
