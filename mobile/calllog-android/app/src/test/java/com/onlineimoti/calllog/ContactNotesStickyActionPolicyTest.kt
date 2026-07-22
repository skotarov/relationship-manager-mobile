package com.onlineimoti.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNotesStickyActionPolicyTest {
    @Test
    fun actionRowStaysNormalWhileItsTopIsStillVisible() {
        assertFalse(
            ContactNotesStickyActionPolicy.shouldStick(
                actionTopOnScreen = 101,
                viewportTopOnScreen = 100,
            ),
        )
    }

    @Test
    fun actionRowPinsExactlyWhenItStartsLeavingTheViewport() {
        assertTrue(
            ContactNotesStickyActionPolicy.shouldStick(
                actionTopOnScreen = 100,
                viewportTopOnScreen = 100,
            ),
        )
        assertTrue(
            ContactNotesStickyActionPolicy.shouldStick(
                actionTopOnScreen = 40,
                viewportTopOnScreen = 100,
            ),
        )
    }

    @Test
    fun compactIdentityUsesTheSameStateAsThePinnedActionRow() {
        assertFalse(ContactNotesStickyActionPolicy.shouldShowCompactIdentity(actionsPinned = false))
        assertTrue(ContactNotesStickyActionPolicy.shouldShowCompactIdentity(actionsPinned = true))
    }

    @Test
    fun compactIdentityAndActionsChangeAtTheSameScrollThreshold() {
        val viewportTop = 100

        val beforeThreshold = ContactNotesStickyActionPolicy.shouldStick(
            actionTopOnScreen = viewportTop + 1,
            viewportTopOnScreen = viewportTop,
        )
        assertFalse(beforeThreshold)
        assertFalse(ContactNotesStickyActionPolicy.shouldShowCompactIdentity(beforeThreshold))

        val atThreshold = ContactNotesStickyActionPolicy.shouldStick(
            actionTopOnScreen = viewportTop,
            viewportTopOnScreen = viewportTop,
        )
        assertTrue(atThreshold)
        assertTrue(ContactNotesStickyActionPolicy.shouldShowCompactIdentity(atThreshold))
    }
}
