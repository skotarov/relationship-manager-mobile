package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyGeneralNoteCachePolicyTest {
    @Test
    fun pendingLocalNoteIsVisibleImmediately() {
        assertEquals(
            CompanyGeneralNoteCacheDecision.SHOW_LOCAL,
            CompanyGeneralNoteCachePolicy.decide(
                localNote = "Нова бележка",
                pending = true,
                savedAtMs = 1_000L,
                nowMs = 2_000L,
                serverConfirmed = false,
            ),
        )
    }

    @Test
    fun recentlyAcknowledgedNoteStaysVisibleWhileLookupPropagates() {
        assertEquals(
            CompanyGeneralNoteCacheDecision.SHOW_LOCAL,
            CompanyGeneralNoteCachePolicy.decide(
                localNote = "Нова бележка",
                pending = false,
                savedAtMs = 1_000L,
                nowMs = 1_000L + CompanyGeneralNoteCachePolicy.CONFIRMATION_GRACE_MS,
                serverConfirmed = false,
            ),
        )
    }

    @Test
    fun staleUnconfirmedCacheIsCleared() {
        assertEquals(
            CompanyGeneralNoteCacheDecision.CLEAR_LOCAL,
            CompanyGeneralNoteCachePolicy.decide(
                localNote = "Стара бележка",
                pending = false,
                savedAtMs = 1_000L,
                nowMs = 1_001L + CompanyGeneralNoteCachePolicy.CONFIRMATION_GRACE_MS,
                serverConfirmed = false,
            ),
        )
    }

    @Test
    fun serverConfirmationClearsTemporaryLocalCopy() {
        assertEquals(
            CompanyGeneralNoteCacheDecision.CLEAR_LOCAL,
            CompanyGeneralNoteCachePolicy.decide(
                localNote = "Същата бележка",
                pending = false,
                savedAtMs = 1_000L,
                nowMs = 2_000L,
                serverConfirmed = true,
            ),
        )
    }

    @Test
    fun onlyUnscopedGeneralNotesBelongInGenericLane() {
        assertTrue(CompanyGeneralNoteCachePolicy.belongsInGenericLane(true, ""))
        assertFalse(CompanyGeneralNoteCachePolicy.belongsInGenericLane(true, "company-1"))
        assertFalse(CompanyGeneralNoteCachePolicy.belongsInGenericLane(false, ""))
    }
}
