package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Loads local and CRM call pages while protecting Home from stale async results. */
internal class HomeCallsLoader(
    private val activity: HomeActivity,
    private val handler: Handler,
    private val contentRenderer: HomeContentRenderer,
    private val crmFilters: HomeCrmFiltersController,
    private val serverCallNotes: HomeServerCallNotesController,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val onRenderComplete: () -> Unit,
    private val onCrmCallsRendered: (Int) -> Unit = {},
    private val onCrmCallsEmpty: () -> Unit = {},
) {
    private val localExecutor = Executors.newSingleThreadExecutor()
    private val localNotesExecutor = Executors.newSingleThreadExecutor()
    private val crmExecutor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    fun invalidate(): Int = generation.incrementAndGet()

    fun release() {
        generation.incrementAndGet()
        localExecutor.shutdownNow()
        localNotesExecutor.shutdownNow()
        crmExecutor.shutdownNow()
    }

    fun renderLocalCalls(pageSize: Int) {
        val requestedPage = pageIndex()
        val phoneFilter = activePhoneFilter()
        val searchQuery = activeSearchQuery()
        val expectedGeneration = generation.get()
        val appContext = activity.applicationContext
        contentRenderer.showLoading()
        localExecutor.execute {
            val calls = loadLocalCalls(appContext, phoneFilter, searchQuery, requestedPage, pageSize)
            val fastData = HomeRenderData(
                calls = calls,
                contactNotesByNumber = emptyMap(),
                contactNamesByNumber = emptyMap(),
                callNotesByCall = emptyMap(),
            )
            handler.post {
                if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@post
                if (calls.isEmpty()) {
                    contentRenderer.renderEmptyState()
                    onRenderComplete()
                } else {
                    contentRenderer.applyRenderData(fastData, pageSize)
                    onRenderComplete()
                }
            }
            if (calls.isEmpty()) return@execute

            // Never let slow SAF/local-note reads block the next call-log page.
            // The rows are already visible; names and notes are added afterwards.
            runCatching {
                localNotesExecutor.execute notesTask@{
                    if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@notesTask
                    val data = HomeRenderData(
                        calls = calls,
                        contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, calls),
                        contactNamesByNumber = HomeCallPageLoader.contactNames(appContext, calls),
                        callNotesByCall = HomeCallNotesResolver.localNotes(appContext, calls),
                    )
                    handler.post {
                        if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@post
                        contentRenderer.applyRenderData(data, pageSize)
                        serverCallNotes.enrichAsync(data) { enriched ->
                            if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@enrichAsync
                            contentRenderer.applyRenderData(enriched, pageSize)
                        }
                    }
                }
            }
        }
    }

    private fun loadLocalCalls(
        context: Context,
        phoneFilter: String,
        searchQuery: String,
        requestedPage: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> {
        return when {
            phoneFilter.isBlank() && searchQuery.isBlank() -> HomeTimelineLoader.page(
                context = context,
                pageIndex = requestedPage,
                pageSize = pageSize,
            )
            searchQuery.isNotBlank() -> searchResultsWithSms(context, phoneFilter, searchQuery, requestedPage, pageSize)
            else -> HomeCallPageLoader.calls(
                context = context,
                activePhoneFilter = phoneFilter,
                searchQuery = searchQuery,
                pageIndex = requestedPage,
                pageSize = pageSize,
                crmMode = false,
            )
        }
    }

    /** Searches notes/contacts through the existing index and adds matching SMS rows. */
    private fun searchResultsWithSms(
        context: Context,
        phoneFilter: String,
        query: String,
        requestedPage: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> {
        if (HomeCallPageLoader.isSearchTooShort(query)) return emptyList()
        val baseResults = HomeCallPageLoader.calls(
            context = context,
            activePhoneFilter = phoneFilter,
            searchQuery = query,
            pageIndex = 0,
            pageSize = SEARCH_RESULT_SCAN_LIMIT,
            crmMode = false,
        )
        val selectedPhoneKey = HomeCallPageLoader.noteKey(phoneFilter)
        val smsResults = SmsMessageReader.searchMessages(context, query, SEARCH_RESULT_SCAN_LIMIT)
            .asSequence()
            .filter { message -> selectedPhoneKey.isBlank() || HomeCallPageLoader.noteKey(message.address) == selectedPhoneKey }
            .mapNotNull { message ->
                message.address.takeIf { it.isNotBlank() }?.let { address ->
                    PhoneCallRecord(
                        number = address,
                        name = "",
                        direction = if (message.isOutgoing) "sms_out" else "sms_in",
                        startedAt = message.timestampMs,
                        durationSeconds = 0L,
                        smsBody = message.body,
                        providerId = message.providerId,
                    )
                }
            }
            .toList()
        val seen = linkedSetOf<String>()
        val combined = (baseResults + smsResults)
            .filter { row -> seen.add(searchResultKey(row)) }
            .sortedByDescending { row -> row.startedAt }
        val offset = (requestedPage.toLong() * pageSize.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return combined.drop(offset).take(pageSize)
    }

    private fun searchResultKey(row: PhoneCallRecord): String {
        if (row.isSms) {
            val fallback = "${row.number}:${row.startedAt}:${row.smsBody.hashCode()}"
            return "sms:${row.providerId.ifBlank { fallback }}"
        }
        val fallback = "${row.number}:${row.startedAt}:${row.direction}"
        return "call:${row.providerId.ifBlank { fallback }}"
    }

    fun renderCrmCallsAsync(pageSize: Int, expectedGeneration: Int) {
        val filterState = crmFilters.state()
        if (contentRenderer.currentCalls.isEmpty()) contentRenderer.showCrmLoading()
        val requestedPage = pageIndex()
        val appContext = activity.applicationContext
        crmExecutor.execute {
            val calls = runCatching {
                val localFiltered = HomeCrmFilterEngine.filterLocal(
                    context = appContext,
                    calls = HomeTimelineLoader.crmCandidates(appContext),
                    state = filterState,
                )
                val companyFiltered = if (filterState.isCompanyFiltered) {
                    val memberships = HomeCrmCompanyMembershipStore.resolve(
                        context = appContext,
                        config = ConfigStore.load(appContext),
                        phones = localFiltered.map { it.number },
                    )
                    HomeCrmFilterEngine.filterByCompany(localFiltered, filterState, memberships.companyIdsByPhoneKey)
                } else {
                    localFiltered
                }
                companyFiltered.drop(requestedPage * pageSize).take(pageSize)
            }.getOrDefault(emptyList())
            val fastData = HomeRenderData(
                calls = calls,
                contactNotesByNumber = emptyMap(),
                contactNamesByNumber = emptyMap(),
                callNotesByCall = emptyMap(),
            )
            handler.post {
                if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@post
                if (calls.isEmpty()) {
                    contentRenderer.renderEmptyState()
                    onCrmCallsEmpty()
                } else {
                    contentRenderer.applyRenderData(fastData, pageSize)
                    onCrmCallsRendered(calls.size)
                }
                onRenderComplete()
            }
            if (calls.isEmpty()) return@execute

            // CRM rows follow the same rule as the normal call log: show the
            // page first, then enrich the already visible rows with notes.
            runCatching {
                localNotesExecutor.execute crmNotesTask@{
                    if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@crmNotesTask
                    val data = HomeRenderData(
                        calls = calls,
                        contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, calls),
                        contactNamesByNumber = HomeCallPageLoader.contactNames(appContext, calls),
                        callNotesByCall = HomeCallNotesResolver.localNotes(appContext, calls),
                    )
                    handler.post {
                        if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@post
                        contentRenderer.applyRenderData(data, pageSize)
                        serverCallNotes.enrichAsync(data) { enriched ->
                            if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@enrichAsync
                            contentRenderer.applyRenderData(enriched, pageSize)
                        }
                    }
                }
            }
        }
    }

    private fun isCurrentLocalRender(
        expectedGeneration: Int,
        requestedPage: Int,
        phoneFilter: String,
        searchQuery: String,
    ): Boolean {
        return expectedGeneration == generation.get() &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            activePhoneFilter() == phoneFilter &&
            activeSearchQuery() == searchQuery &&
            pageIndex() == requestedPage
    }

    private fun isCurrentCrmRender(
        expectedGeneration: Int,
        requestedPage: Int,
        filterState: HomeCrmFilterState,
    ): Boolean {
        return expectedGeneration == generation.get() &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            isCrmModeEnabled() &&
            activePhoneFilter().isBlank() &&
            activeSearchQuery().isBlank() &&
            pageIndex() == requestedPage &&
            crmFilters.state() == filterState
    }

    private companion object {
        const val SEARCH_RESULT_SCAN_LIMIT = 500
    }
}
