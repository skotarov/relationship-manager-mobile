package com.onlineimoti.calllog

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
    private val crmExecutor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    fun invalidate(): Int = generation.incrementAndGet()

    fun release() {
        generation.incrementAndGet()
        crmExecutor.shutdownNow()
    }

    fun renderLocalCalls(pageSize: Int) {
        val phoneFilter = activePhoneFilter()
        val searchQuery = activeSearchQuery()
        val calls = when {
            phoneFilter.isBlank() && searchQuery.isBlank() -> HomeTimelineLoader.page(
                context = activity,
                pageIndex = pageIndex(),
                pageSize = pageSize,
            )
            searchQuery.isNotBlank() -> searchResultsWithSms(phoneFilter, searchQuery, pageSize)
            else -> HomeCallPageLoader.calls(
                context = activity,
                activePhoneFilter = phoneFilter,
                searchQuery = searchQuery,
                pageIndex = pageIndex(),
                pageSize = pageSize,
                crmMode = false,
            )
        }
        if (calls.isEmpty()) {
            contentRenderer.renderEmptyState()
            onRenderComplete()
            return
        }
        val data = HomeRenderData(
            calls = calls,
            contactNotesByNumber = HomeCallPageLoader.contactNotes(activity, calls),
            contactNamesByNumber = HomeCallPageLoader.contactNames(activity, calls),
            callNotesByCall = HomeCallNotesResolver.localNotes(activity, calls),
        )
        contentRenderer.applyRenderData(data, pageSize)
        serverCallNotes.enrichAsync(data) { enriched ->
            contentRenderer.applyRenderData(enriched, pageSize)
        }
        onRenderComplete()
    }

    /** Searches notes/contacts through the existing index and adds matching SMS rows. */
    private fun searchResultsWithSms(
        phoneFilter: String,
        query: String,
        pageSize: Int,
    ): List<PhoneCallRecord> {
        if (HomeCallPageLoader.isSearchTooShort(query)) return emptyList()
        val baseResults = HomeCallPageLoader.calls(
            context = activity,
            activePhoneFilter = phoneFilter,
            searchQuery = query,
            pageIndex = 0,
            pageSize = SEARCH_RESULT_SCAN_LIMIT,
            crmMode = false,
        )
        val selectedPhoneKey = HomeCallPageLoader.noteKey(phoneFilter)
        val smsResults = SmsMessageReader.searchMessages(activity, query, SEARCH_RESULT_SCAN_LIMIT)
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
        val offset = (pageIndex().toLong() * pageSize.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
            val data = runCatching {
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
                val calls = companyFiltered.drop(requestedPage * pageSize).take(pageSize)
                HomeRenderData(
                    calls = calls,
                    contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, calls),
                    contactNamesByNumber = HomeCallPageLoader.contactNames(appContext, calls),
                    callNotesByCall = HomeCallNotesResolver.localNotes(appContext, calls),
                )
            }.getOrDefault(HomeRenderData(emptyList(), emptyMap(), emptyMap()))
            handler.post {
                val current = expectedGeneration == generation.get() &&
                    !activity.isFinishing &&
                    !activity.isDestroyed &&
                    isCrmModeEnabled() &&
                    activePhoneFilter().isBlank() &&
                    activeSearchQuery().isBlank() &&
                    pageIndex() == requestedPage &&
                    crmFilters.state() == filterState
                if (!current) return@post
                if (data.calls.isEmpty()) {
                    contentRenderer.renderEmptyState()
                    onCrmCallsEmpty()
                } else {
                    contentRenderer.applyRenderData(data, pageSize)
                    onCrmCallsRendered(data.calls.size)
                    serverCallNotes.enrichAsync(data) { enriched ->
                        contentRenderer.applyRenderData(enriched, pageSize)
                    }
                }
                onRenderComplete()
            }
        }
    }

    private companion object {
        const val SEARCH_RESULT_SCAN_LIMIT = 500
    }
}
