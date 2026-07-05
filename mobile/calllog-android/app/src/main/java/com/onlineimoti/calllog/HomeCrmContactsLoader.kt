package com.onlineimoti.calllog

import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class HomeCrmContactsLoader(
    private val activity: HomeActivity,
    private val handler: Handler,
    private val contentRenderer: HomeContentRenderer,
    private val crmFilters: HomeCrmFiltersController,
    private val pageIndex: () -> Int,
    private val onRenderComplete: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    fun invalidate(): Int = generation.incrementAndGet()

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    fun renderAsync(pageSize: Int, expectedGeneration: Int) {
        val filterState = crmFilters.state()
        val requestedPage = pageIndex()
        executor.execute {
            val rows = ContactSearchProvider.crmEnabledContacts(activity.applicationContext)
                .map { PhoneCallRecord(it.phone, it.name, "", 0L, 0L) }
            val filtered = HomeCrmFilterEngine.filterLocal(activity.applicationContext, rows, filterState)
            val page = filtered.drop(requestedPage * pageSize).take(pageSize)
            handler.post {
                if (expectedGeneration != generation.get()) return@post
                if (page.isEmpty()) contentRenderer.renderEmptyState()
                else contentRenderer.applyRenderData(
                    HomeRenderData(
                        calls = page,
                        contactNotesByNumber = HomeCallPageLoader.contactNotes(activity, page),
                        contactNamesByNumber = page.associate { HomeCallPageLoader.noteKey(it.number) to it.displayName },
                    ),
                    pageSize,
                )
                onRenderComplete()
            }
        }
    }
}
