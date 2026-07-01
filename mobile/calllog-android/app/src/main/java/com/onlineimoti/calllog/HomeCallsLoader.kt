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
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
) {
    private val crmExecutor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    fun invalidate(): Int = generation.incrementAndGet()

    fun release() {
        generation.incrementAndGet()
        crmExecutor.shutdownNow()
    }

    fun renderLocalCalls(pageSize: Int) {
        val calls = HomeCallPageLoader.calls(
            context = activity,
            activePhoneFilter = activePhoneFilter(),
            searchQuery = activeSearchQuery(),
            pageIndex = pageIndex(),
            pageSize = pageSize,
            crmMode = false,
        )
        if (calls.isEmpty()) {
            contentRenderer.renderEmptyState()
            return
        }
        contentRenderer.applyRenderData(
            HomeRenderData(
                calls = calls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(activity, calls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(activity, calls),
            ),
            pageSize,
        )
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
                if (data.calls.isEmpty()) contentRenderer.renderEmptyState()
                else contentRenderer.applyRenderData(data, pageSize)
            }
        }
    }
}
