package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

internal class HomeSearchController(
    private val context: Context,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val searchExecutor: ExecutorService,
    private val searchGeneration: AtomicInteger,
    private val pageSize: () -> Int,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val isCrmModeEnabled: () -> Boolean,
    private val pageIndex: () -> Int,
    private val setCurrentCalls: (List<PhoneCallRecord>) -> Unit,
    private val renderEmptyState: () -> Unit,
    private val applyRenderData: (HomeRenderData, Int) -> Unit,
    private val onRenderComplete: () -> Unit,
) {
    fun renderSearchCallsAsync() {
        val query = activeSearchQuery()
        val currentPageSize = pageSize()
        if (HomeCallPageLoader.isSearchTooShort(query)) {
            setCurrentCalls(emptyList())
            binding.homeStatusText.text = context.getString(R.string.dynamic_home_search_minimum)
            binding.previousCallsButton.isEnabled = false
            binding.nextCallsButton.isEnabled = false
            binding.pageText.text = context.getString(R.string.dynamic_home_page, pageIndex() + 1)
            binding.paginationContainer.visibility = View.VISIBLE
            onRenderComplete()
            return
        }

        val generation = searchGeneration.incrementAndGet()
        val phoneFilter = activePhoneFilter()
        val crmMode = isCrmModeEnabled()
        val page = pageIndex()
        binding.homeStatusText.text = context.getString(R.string.dynamic_home_searching, query.trim())
        binding.previousCallsButton.isEnabled = false
        binding.nextCallsButton.isEnabled = false
        binding.paginationContainer.visibility = View.VISIBLE

        searchExecutor.execute {
            val calls = HomeCallPageLoader.calls(
                context = context,
                activePhoneFilter = phoneFilter,
                searchQuery = query,
                pageIndex = page,
                pageSize = currentPageSize,
                crmMode = crmMode,
            )
            val renderData = HomeRenderData(
                calls = calls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(context, calls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(context, calls),
            )
            handler.post {
                if (generation != searchGeneration.get()) return@post
                if (
                    query != activeSearchQuery() ||
                    phoneFilter != activePhoneFilter() ||
                    crmMode != isCrmModeEnabled() ||
                    page != pageIndex()
                ) {
                    return@post
                }
                setCurrentCalls(renderData.calls)
                if (renderData.calls.isEmpty()) {
                    binding.homeCallsContainer.removeAllViews()
                    renderEmptyState()
                } else {
                    applyRenderData(renderData, currentPageSize)
                }
                onRenderComplete()
            }
        }
    }
}

internal data class HomeRenderData(
    val calls: List<PhoneCallRecord>,
    val contactNotesByNumber: Map<String, String>,
    val contactNamesByNumber: Map<String, String>,
)
