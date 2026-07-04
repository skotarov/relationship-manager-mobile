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
        val calls = if (phoneFilter.isBlank() && searchQuery.isBlank()) {
            HomeTimelineLoader.page(
                context = activity,
                pageIndex = pageIndex(),
                pageSize = pageSize,
            )
        } else {
            HomeCallPageLoader.calls(
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

    fun renderCrmCallsAsync(pageSize: Int, expectedGeneration: Int) {
        val filterState = crmFilters.state()
        if (contentRenderer.currentCalls.isEmpty()) contentRenderer.showCrmLoading()
        val requestedPage = pageIndex()
        val appContext = activity.applicationContext
        crmExecutor.execute {
            val data = runCatching {
                val localFiltered = HomeCrmFilterEngine.filterLocal(
                    context = appContext,
                    calls = HomeCallPageLoader.crmCandidateCalls(appContext),
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
                } else {
                    contentRenderer.applyRenderData(data, pageSize)
                    serverCallNotes.enrichAsync(data) { enriched ->
                        contentRenderer.applyRenderData(enriched, pageSize)
                    }
                }
                onRenderComplete()
            }
        }
    }
}
