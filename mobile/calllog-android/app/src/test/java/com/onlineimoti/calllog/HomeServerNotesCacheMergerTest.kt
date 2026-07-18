package com.onlineimoti.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeServerNotesCacheMergerTest {
    private val phone = "+359 88 123 4567"
    private val phoneKey = HomeCallPageLoader.noteKey(phone)

    @Test
    fun networkFailureKeepsExistingState() {
        val state = stateWith(event(companyId = "firm-a"))
        val next = HomeServerNotesCacheMerger.apply(state, CallReportHistoryLookupResult(), 200L)
        assertEquals(state, next)
    }

    @Test
    fun successfulEmptySnapshotClearsOnlyConfirmedPhone() {
        val other = "+359 88 765 4321"
        val state = HomeServerNotesCacheState(
            eventsByPhoneKey = mapOf(
                phoneKey to listOf(event()),
                HomeCallPageLoader.noteKey(other) to listOf(event(phone = other, clientEventId = "other")),
            ),
        )
        val next = HomeServerNotesCacheMerger.apply(
            state,
            CallReportHistoryLookupResult(
                requestSuccessful = true,
                successfulPhoneKeys = setOf(phoneKey),
            ),
            200L,
        )
        assertFalse(phoneKey in next.eventsByPhoneKey)
        assertTrue(HomeCallPageLoader.noteKey(other) in next.eventsByPhoneKey)
    }

    @Test
    fun changedNoteReplacesCachedVersion() {
        val state = stateWith(event(note = "old", updatedAtMs = 10L))
        val next = applySuccess(state, event(note = "new", updatedAtMs = 20L))
        assertEquals("new", next.eventsByPhoneKey.getValue(phoneKey).single().note)
    }

    @Test
    fun identicalResultDoesNotChangeState() {
        val cached = event(note = "same", updatedAtMs = 20L)
        val state = stateWith(cached, touchedAt = 100L)
        val next = HomeServerNotesCacheMerger.apply(state, success(cached), 100L)
        assertEquals(state, next)
    }

    @Test
    fun newNoteIsAdded() {
        val next = applySuccess(HomeServerNotesCacheState(), event(note = "new"))
        assertEquals("new", next.eventsByPhoneKey.getValue(phoneKey).single().note)
    }

    @Test
    fun unscopedNoteRemainsVisibleWithNoCompanies() {
        val state = stateWith(event(companyId = ""), principal = principal(emptyList()), authoritative = true)
        assertEquals(1, HomeServerNotesCacheMerger.visibleResult(state, listOf(phone)).events.size)
    }

    @Test
    fun accessibleCompanyNoteIsVisible() {
        val state = stateWith(
            event(companyId = "firm-a"),
            principal = principal(listOf(CallReportHistoryCompany("firm-a", "A"))),
            authoritative = true,
        )
        assertEquals(1, HomeServerNotesCacheMerger.visibleResult(state, listOf(phone)).events.size)
    }

    @Test
    fun inaccessibleCompanyNoteIsHiddenButRetained() {
        val state = stateWith(
            event(companyId = "firm-a"),
            principal = principal(listOf(CallReportHistoryCompany("firm-b", "B"))),
            authoritative = true,
        )
        assertTrue(HomeServerNotesCacheMerger.visibleResult(state, listOf(phone)).events.isEmpty())
        assertEquals(1, state.eventsByPhoneKey.getValue(phoneKey).size)
    }

    @Test
    fun removingCompanyHidesNotesWithoutDeletingThem() {
        val initial = stateWith(
            event(companyId = "firm-a"),
            principal = principal(listOf(CallReportHistoryCompany("firm-a", "A"))),
            authoritative = true,
        )
        val next = HomeServerNotesCacheMerger.apply(
            initial,
            CallReportHistoryLookupResult(
                principal = principal(emptyList()),
                requestSuccessful = true,
                principalCompaniesAuthoritative = true,
            ),
            200L,
        )
        assertTrue(HomeServerNotesCacheMerger.visibleResult(next, listOf(phone)).events.isEmpty())
        assertEquals(1, next.eventsByPhoneKey.getValue(phoneKey).size)
    }

    @Test
    fun restoringCompanyMakesRetainedNotesVisibleAgain() {
        val hidden = stateWith(event(companyId = "firm-a"), principal = principal(emptyList()), authoritative = true)
        val restored = HomeServerNotesCacheMerger.apply(
            hidden,
            CallReportHistoryLookupResult(
                principal = principal(listOf(CallReportHistoryCompany("firm-a", "A"))),
                requestSuccessful = true,
                principalCompaniesAuthoritative = true,
            ),
            200L,
        )
        assertEquals(1, HomeServerNotesCacheMerger.visibleResult(restored, listOf(phone)).events.size)
    }

    @Test
    fun nonAuthoritativePrincipalDoesNotClearCompanies() {
        val state = stateWith(
            event(companyId = "firm-a"),
            principal = principal(listOf(CallReportHistoryCompany("firm-a", "A"))),
            authoritative = true,
        )
        val next = HomeServerNotesCacheMerger.apply(
            state,
            CallReportHistoryLookupResult(
                principal = principal(emptyList()),
                requestSuccessful = true,
                principalCompaniesAuthoritative = false,
            ),
            200L,
        )
        assertEquals(listOf("firm-a"), next.principal.companies.map { it.id })
    }

    @Test
    fun explicitEmptyCompaniesIsAuthoritative() {
        val state = stateWith(
            event(companyId = "firm-a"),
            principal = principal(listOf(CallReportHistoryCompany("firm-a", "A"))),
            authoritative = true,
        )
        val next = HomeServerNotesCacheMerger.apply(
            state,
            CallReportHistoryLookupResult(
                principal = principal(emptyList()),
                requestSuccessful = true,
                principalCompaniesAuthoritative = true,
            ),
            200L,
        )
        assertTrue(next.principal.companies.isEmpty())
        assertTrue(next.accessibleCompaniesAuthoritative)
    }

    @Test
    fun differentPhoneFormatsUseSameKey() {
        val state = applySuccess(HomeServerNotesCacheState(), event(phone = "+359 88 123 4567"))
        assertEquals(1, HomeServerNotesCacheMerger.visibleResult(state, listOf("0881234567")).events.size)
    }

    @Test
    fun failedTokenRefreshDoesNotChangeCompanyVisibility() {
        val state = stateWith(
            event(companyId = "firm-a"),
            principal = principal(listOf(CallReportHistoryCompany("firm-a", "A"))),
            authoritative = true,
        )
        val next = HomeServerNotesCacheMerger.apply(
            state,
            CallReportHistoryLookupResult(requestSuccessful = false),
            200L,
        )
        assertEquals(state, next)
        assertEquals(1, HomeServerNotesCacheMerger.visibleResult(next, listOf(phone)).events.size)
    }

    private fun applySuccess(
        state: HomeServerNotesCacheState,
        vararg events: CallReportHistoryEvent,
    ): HomeServerNotesCacheState = HomeServerNotesCacheMerger.apply(state, success(*events), 200L)

    private fun success(vararg events: CallReportHistoryEvent) = CallReportHistoryLookupResult(
        events = events.toList(),
        requestSuccessful = true,
        successfulPhoneKeys = setOf(phoneKey),
    )

    private fun stateWith(
        event: CallReportHistoryEvent,
        touchedAt: Long = 100L,
        principal: CallReportHistoryPrincipal = principal(emptyList()),
        authoritative: Boolean = false,
    ) = HomeServerNotesCacheState(
        eventsByPhoneKey = mapOf(phoneKey to listOf(event)),
        phoneUpdatedAtMs = mapOf(phoneKey to touchedAt),
        principal = principal,
        accessibleCompaniesAuthoritative = authoritative,
    )

    private fun principal(companies: List<CallReportHistoryCompany>) = CallReportHistoryPrincipal(
        brokerId = "broker-1",
        brokerName = "Broker",
        companies = companies,
    )

    private fun event(
        phone: String = this.phone,
        note: String = "note",
        companyId: String = "",
        clientEventId: String = "event-1",
        updatedAtMs: Long = 10L,
    ) = CallReportHistoryEvent(
        clientEventId = clientEventId,
        communicationType = "note",
        phone = phone,
        direction = "incoming",
        occurredAtMs = 1L,
        note = note,
        updatedAtMs = updatedAtMs,
        companyId = companyId,
    )
}
