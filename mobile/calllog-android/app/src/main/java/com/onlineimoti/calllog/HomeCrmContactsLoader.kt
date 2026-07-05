package com.onlineimoti.calllog

import android.os.Handler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Loads explicitly enabled CRM contacts and applies the same phase/company filters as CRM calls. */
internal class HomeCrmContactsLoader(
    private val activity: HomeActivity,
    private val handler: Handler,
    private val contactsContent: HomeCrmContactsContentView,
    private val crmFilters: HomeCrmFiltersController,
    private val activePhoneFilter: () -> String,
    private val activeSearchQuery: () -> String,
    private val pageIndex: () -> Int,
    private val isCrmModeEnabled: () -> Boolean,
    private val isCrmContactsMode: () -> Boolean,
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
        val appContext = activity.applicationContext
        contactsContent.showLoading()
        executor.execute {
            val data = runCatching {
                val contacts = ContactSearchProvider.crmEnabledContacts(appContext)
                    .map { PhoneCallRecord(it.phone, it.name, "", 0L, 0L) }
                val phaseFiltered = HomeCrmFilterEngine.filterLocal(appContext, contacts, filterState)
                val companyFiltered = if (filterState.isCompanyFiltered) {
                    val memberships = HomeCrmCompanyMembershipStore.resolve(
                        context = appContext,
                        config = ConfigStore.load(appContext),
                        phones = phaseFiltered.map { it.number },
                    )
                    HomeCrmFilterEngine.filterByCompany(
                        calls = phaseFiltered,
                        state = filterState,
                        companyIdsByPhoneKey = memberships.companyIdsByPhoneKey,
                    )
                } else {
                    phaseFiltered
                }
                val page = companyFiltered
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.ifBlank { it.number } })
                    .drop(requestedPage * pageSize)
                    .take(pageSize)
                HomeRenderData(
                    calls = page,
                    contactNotesByNumber = HomeCallPageLoader.contactNotes(appContext, page),
                    contactNamesByNumber = page.associate { HomeCallPageLoader.noteKey(it.number) to it.displayName },
                )
            }.getOrDefault(HomeRenderData(emptyList(), emptyMap(), emptyMap()))
            handler.post {
                val current = expectedGeneration == generation.get() &&
                    !activity.isFinishing &&
                    !activity.isDestroyed &&
                    isCrmModeEnabled() &&
                    isCrmContactsMode() &&
                    activePhoneFilter().isBlank() &&
                    activeSearchQuery().isBlank() &&
                    pageIndex() == requestedPage &&
                    crmFilters.state() == filterState
                if (!current) return@post
                if (data.calls.isEmpty()) contactsContent.renderEmpty(pageSize)
                else contactsContent.render(data, pageSize)
                onRenderComplete()
            }
        }
    }
}
