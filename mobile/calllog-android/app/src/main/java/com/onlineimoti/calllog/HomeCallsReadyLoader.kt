package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
        HomePageReadyState.markLoading()
        val requestedPage = pageIndex()
        val phoneFilter = activePhoneFilter()
        val searchQuery = activeSearchQuery()
        val expectedGeneration = generation.get()
        val appContext = activity.applicationContext
        val localNotesApplied = AtomicBoolean(false)
        contentRenderer.showLoading()
        localExecutor.execute {
            val calls = loadLocalCalls(appContext, phoneFilter, searchQuery, requestedPage, pageSize)
            val fastData = HomeRenderData(calls, emptyMap(), emptyMap(), emptyMap())
            handler.post {
                if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@post
                if (calls.isEmpty()) {
                    contentRenderer.renderEmptyState()
                    HomePageReadyState.markReady()
                    onRenderComplete()
                } else {
                    contentRenderer.applyProvisionalRenderData(fastData, pageSize)
                    serverCallNotes.enrichAsync(fastData) { enriched ->
                        if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@enrichAsync
                        if (!localNotesApplied.get()) contentRenderer.applySupplementalRenderData(enriched, pageSize)
                    }
                    onRenderComplete()
                }
            }
            if (calls.isEmpty()) return@execute

            runCatching {
                localNotesExecutor.execute notesTask@{
                    if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@notesTask
                    val data = runCatching {
                        HomeRenderData(
                            calls = calls,
                            contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, calls),
                            contactNamesByNumber = HomeCallPageLoader.contactNames(appContext, calls),
                            callNotesByCall = HomeCallNotesResolver.localNotes(appContext, calls),
                        )
                    }.getOrElse {
                        handler.post {
                            if (isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) {
                                HomePageReadyState.markReady()
                            }
                        }
                        return@notesTask
                    }
                    handler.post {
                        if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@post
                        localNotesApplied.set(true)
                        contentRenderer.applySupplementalRenderData(data, pageSize)
                        serverCallNotes.enrichAsync(
                            data,
                            onFinished = {
                                if (isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) {
                                    HomePageReadyState.markReady()
                                }
                            },
                        ) { enriched ->
                            if (!isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) return@enrichAsync
                            contentRenderer.applyRenderData(enriched, pageSize)
                        }
                    }
                }
            }.onFailure {
                handler.post {
                    if (isCurrentLocalRender(expectedGeneration, requestedPage, phoneFilter, searchQuery)) {
                        HomePageReadyState.markReady()
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
    ): List<PhoneCallRecord> = when {
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
            .filter { selectedPhoneKey.isBlank() || HomeCallPageLoader.noteKey(it.address) == selectedPhoneKey }
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
            .filter { seen.add(searchResultKey(it)) }
            .sortedByDescending { it.startedAt }
        return pageForMode(context, combined, requestedPage, pageSize)
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
        HomePageReadyState.markLoading()
        val filterState = crmFilters.state()
        if (contentRenderer.currentCalls.isEmpty()) contentRenderer.showCrmLoading()
        val requestedPage = pageIndex()
        val appContext = activity.applicationContext
        val localNotesApplied = AtomicBoolean(false)
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
                } else localFiltered
                pageForMode(appContext, companyFiltered, requestedPage, pageSize)
            }.getOrDefault(emptyList())
            val fastData = HomeRenderData(calls, emptyMap(), emptyMap(), emptyMap())
            handler.post {
                if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@post
                if (calls.isEmpty()) {
                    contentRenderer.renderEmptyState()
                    HomePageReadyState.markReady()
                    onCrmCallsEmpty()
                } else {
                    contentRenderer.applyProvisionalRenderData(fastData, pageSize)
                    serverCallNotes.enrichAsync(fastData) { enriched ->
                        if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@enrichAsync
                        if (!localNotesApplied.get()) contentRenderer.applySupplementalRenderData(enriched, pageSize)
                    }
                    onCrmCallsRendered(calls.size)
                }
                onRenderComplete()
            }
            if (calls.isEmpty()) return@execute

            runCatching {
                localNotesExecutor.execute crmNotesTask@{
                    if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@crmNotesTask
                    val data = runCatching {
                        HomeRenderData(
                            calls = calls,
                            contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, calls),
                            contactNamesByNumber = HomeCallPageLoader.contactNames(appContext, calls),
                            callNotesByCall = HomeCallNotesResolver.localNotes(appContext, calls),
                        )
                    }.getOrElse {
                        handler.post {
                            if (isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) {
                                HomePageReadyState.markReady()
                            }
                        }
                        return@crmNotesTask
                    }
                    handler.post {
                        if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@post
                        localNotesApplied.set(true)
                        contentRenderer.applySupplementalRenderData(data, pageSize)
                        serverCallNotes.enrichAsync(
                            data,
                            onFinished = {
                                if (isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) {
                                    HomePageReadyState.markReady()
                                }
                            },
                        ) { enriched ->
                            if (!isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) return@enrichAsync
                            contentRenderer.applyRenderData(enriched, pageSize)
                        }
                    }
                }
            }.onFailure {
                handler.post {
                    if (isCurrentCrmRender(expectedGeneration, requestedPage, filterState)) {
                        HomePageReadyState.markReady()
                    }
                }
            }
        }
    }

    private fun pageForMode(
        context: Context,
        rows: List<PhoneCallRecord>,
        requestedPage: Int,
        pageSize: Int,
    ): List<PhoneCallRecord> {
        if (PageLoadingModeStore.usesPrefetch(context)) {
            return TimelineGroupedPager.page(
                items = rows,
                pageIndex = requestedPage,
                minimumPageSize = pageSize,
                groupKey = { row -> TimelineGroupKeys.day(row.startedAt) },
            )
        }
        val offset = (requestedPage.toLong() * pageSize.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return rows.drop(offset).take(pageSize)
    }

    private fun isCurrentLocalRender(
        expectedGeneration: Int,
        requestedPage: Int,
        phoneFilter: String,
        searchQuery: String,
    ): Boolean = expectedGeneration == generation.get() &&
        !activity.isFinishing && !activity.isDestroyed &&
        activePhoneFilter() == phoneFilter && activeSearchQuery() == searchQuery &&
        pageIndex() == requestedPage

    private fun isCurrentCrmRender(
        expectedGeneration: Int,
        requestedPage: Int,
        filterState: HomeCrmFilterState,
    ): Boolean = expectedGeneration == generation.get() &&
        !activity.isFinishing && !activity.isDestroyed && isCrmModeEnabled() &&
        activePhoneFilter().isBlank() && activeSearchQuery().isBlank() &&
        pageIndex() == requestedPage && crmFilters.state() == filterState

    private companion object {
        const val SEARCH_RESULT_SCAN_LIMIT = 500
    }
}
