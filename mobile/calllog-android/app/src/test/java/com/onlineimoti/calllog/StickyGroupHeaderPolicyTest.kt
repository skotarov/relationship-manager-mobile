package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickyGroupHeaderPolicyTest {
    @Test
    fun hidesBeforeFirstGroupTitleTouchesStickyBoundary() {
        assertNull(StickyGroupHeaderPolicy.resolve(listOf(1, 240), overlayHeight = 32))
    }

    @Test
    fun pinsExactlyWhenFirstGroupTitleTouchesViewportTop() {
        assertEquals(
            StickyGroupHeaderState(activeIndex = 0, translationY = 0),
            StickyGroupHeaderPolicy.resolve(listOf(0, 240), overlayHeight = 32),
        )
    }

    @Test
    fun pinsAtHistoryBoundaryBelowFixedActions() {
        assertEquals(
            StickyGroupHeaderState(activeIndex = 0, translationY = 0),
            StickyGroupHeaderPolicy.resolve(
                headerTops = listOf(50, 240),
                overlayHeight = 32,
                stickyTop = 50,
            ),
        )
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
    fun nextGroupPushesCurrentTitleRelativeToHistoryBoundary() {
        assertEquals(
            StickyGroupHeaderState(activeIndex = 0, translationY = -12),
            StickyGroupHeaderPolicy.resolve(
                headerTops = listOf(-500, 70),
                overlayHeight = 32,
                stickyTop = 50,
            ),
        )
    }

    @Test
    fun nextGroupReplacesPreviousAtStickyBoundary() {
        assertEquals(
            StickyGroupHeaderState(activeIndex = 1, translationY = 0),
            StickyGroupHeaderPolicy.resolve(listOf(-500, 0), overlayHeight = 32),
        )
    }
}
