package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNotesStickyActionPolicyTest {
    @Test
    fun staysNormalBeforeReachingTop() {
        assertFalse(ContactNotesStickyActionPolicy.shouldStick(scrollY = 99, anchorTop = 100))
    }

    @Test
    fun sticksAtTheTopAndRemainsPinnedAfterwards() {
        assertTrue(ContactNotesStickyActionPolicy.shouldStick(scrollY = 100, anchorTop = 100))
        assertTrue(ContactNotesStickyActionPolicy.shouldStick(scrollY = 1_000, anchorTop = 100))
    }

    @Test
    fun ignoresUnknownAnchorPosition() {
        assertFalse(ContactNotesStickyActionPolicy.shouldStick(scrollY = 100, anchorTop = -1))
    }
}
