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
    fun compactIdentityWaitsUntilTheLargeIdentityIsCompletelyHidden() {
        assertFalse(
            ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                identityBottomOnScreen = 101,
                viewportTopOnScreen = 100,
            ),
        )
        assertTrue(
            ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                identityBottomOnScreen = 100,
                viewportTopOnScreen = 100,
            ),
        )
        assertTrue(
            ContactNotesStickyActionPolicy.shouldShowCompactIdentity(
                identityBottomOnScreen = 20,
                viewportTopOnScreen = 100,
            ),
        )
    }
}
