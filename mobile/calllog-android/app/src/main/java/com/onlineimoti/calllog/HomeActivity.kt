package com.onlineimoti.calllog

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private val uiGeometry: HomeUiGeometry by lazy { HomeUiGeometry(resources) }
    /** Two workers let the current query start even while an older provider read is unwinding. */
    private val searchExecutor = Executors.newFixedThreadPool(2)
    private val searchGeneration = AtomicInteger(0)
    private val contactsSyncPreparer: HomeContactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    private val noteSavedReceiver: HomeNoteSavedReceiverController by lazy {
        HomeNoteSavedReceiverController(this) {
            HomeCallPageLoader.clearSearchCache()
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            renderCalls()
        }
    }
    private val noteRefreshController: HomeNoteRefreshController by lazy {
        HomeNoteRefreshController(
            handler = handler,
            onPrepare = {
                HomeCallPageLoader.clearSearchCache()
                filteredFullLogController.invalidate()
                companyGeneralNotesController.invalidate()
            },
            onRefresh = ::renderCalls,
        )
    }
    private val homeActions: HomeActions by lazy {
        HomeActions(this, binding, noteRefreshController::start) {
            activePhoneFilter.isBlank() && activeSearchQuery.isBlank()
        }
    }
    private val companyGeneralNotesController: HomeCompanyGeneralNotesController by lazy {
        HomeCompanyGeneralNotesController(this, handler) {
            if (::binding.isInitialized && !isFinishing && !isDestroyed) {
                homeContentRenderer.renderCurrentRowsAfterCompanyLabels(pageSize())
            }
        }
    }
    private val serverCallNotesController: HomeServerCallNotesController by lazy {
        HomeServerCallNotesController(this, handler)
    }
    private val crmFiltersController: HomeCrmFiltersController by lazy {
        HomeCrmFiltersController(
            activity = this,
            binding = binding,
            handler = handler,
            dp = uiGeometry::dp,
            roundedRect = uiGeometry::roundedRect,
            onFilterChanged = {
                homeContentRenderer.clearCalls()
                pageIndex = 0
                filteredFullLogController.invalidate()
                companyGeneralNotesController.invalidate()
                renderCalls()
            },
        )
    }
    private val filteredContactSummaryChipsUi: HomeCompanyScopeChipsUi by lazy {
        HomeCompanyScopeChipsUi(this, uiGeometry::dp, uiGeometry::roundedRect)
    }
    private val homeCallRowRenderer: HomeCallRowRenderer by lazy {
        HomeCallRowRenderer(
            activity = this,
            dp = uiGeometry::dp,
            noteKey = HomeCallPageLoader::noteKey,
            roundedRect = uiGeometry::roundedRect,
            openContactNotesScreen = homeActions::openContactNotesScreen,
            openContactNotePopupForCall = homeActions::openContactNotePopupForCall,
            openDialer = homeActions::openDialer,
            togglePhoneFilter = ::togglePhoneFilter,
        )
    }
    private val homeContentRenderer: HomeContentRenderer by lazy {
        HomeContentRenderer(
            activity = this,
            binding = binding,
            activePhoneFilter = { activePhoneFilter },
            activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex },
            isCrmModeEnabled = ::isCrmModeEnabled,
            hasActiveCrmFilters = { crmFiltersController.hasActiveFilters() },
            dp = uiGeometry::dp,
            roundedRect = uiGeometry::roundedRect,
            rowRenderer = homeCallRowRenderer,
            companyGeneralNotes = companyGeneralNotesController,
            scopeChipsUi = filteredContactSummaryChipsUi,
        )
    }
    private val callsLoader: HomeCallsLoader by lazy {
        HomeCallsLoader(
            activity = this,
            handler = handler,
            contentRenderer = homeContentRenderer,
            crmFilters = crmFiltersController,
            serverCallNotes = serverCallNotesController,
            activePhoneFilter = { activePhoneFilter },
            activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex },
            isCrmModeEnabled = ::isCrmModeEnabled,
            onRenderComplete = ::completePullToRefresh,
        )
    }
    private val searchController: HomeSearchController by lazy {
        HomeSearchController(
            context = this,
            binding = binding,
            handler = handler,
            searchExecutor = searchExecutor,
            searchGeneration = searchGeneration,
            serverCallNotes = serverCallNotesController,
            pageSize = ::pageSize,
            activePhoneFilter = { activePhoneFilter },
            activeSearchQuery = { activeSearchQuery },
            isCrmModeEnabled = ::isCrmModeEnabled,
            pageIndex = { pageIndex },
            setCurrentCalls = homeContentRenderer::replaceCurrentCalls,
            renderEmptyState = homeContentRenderer::renderEmptyState,
            applyRenderData = homeContentRenderer::applyRenderData,
            onRenderComplete = ::completePullToRefresh,
        )
    }
    private val searchInputController: HomeSearchInputController by lazy {
        HomeSearchInputController(
            activity = this,
            binding = binding,
            handler = handler,
            onSearchChanged = { query ->
                activeSearchQuery = query
                pageIndex = 0
                renderCalls()
            },
            onSearchCleared = {
                activeSearchQuery = ""
                pageIndex = 0
                renderCalls()
            },
        )
    }
    private val filteredFullLogController: FilteredFullLogController by lazy {
        FilteredFullLogController(
            activity = this,
            binding = binding,
            dp = uiGeometry::dp,
            roundedRect = uiGeometry::roundedRect,
            openContactNotes = homeActions::openContactNotesScreen,
            openCallNoteEditor = homeActions::openContactNotePopupForCall,
            pageSize = ::pageSize,
            onStateChanged = ::renderCalls,
        )
    }
    private val filteredFullLogRefreshWatcher = object : Runnable {
        override fun run() {
            if (!pullRefreshInProgress || !::binding.isInitialized) return
            if (binding.fullLogProgress.visibility == View.VISIBLE) {
                handler.postDelayed(this, FILTERED_REFRESH_CHECK_DELAY_MS)
            } else {
                completePullToRefresh()
            }
        }
    }

    private var pageIndex = 0
    private var activePhoneFilter = ""
    private var activeSearchQuery = ""
    private var pullRefreshInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        activePhoneFilter = intent.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        crmFiltersController.updateVisibility(isCrmModeEnabled() && activePhoneFilter.isBlank())
        homeContentRenderer.prepareForRender(pageSize(), keepExistingRows = false)
        searchInputController.bind()
        binding.homeCallsRefreshLayout.setOnRefreshListener(::refreshFromPull)
        binding.settingsButton.setOnClickListener {
            HomeOverflowMenu.show(this, binding.settingsButton) { homeActions.openSettings() }
        }
        binding.crmModeButton.setOnClickListener { setCrmMode(!isCrmModeEnabled()) }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
        binding.filteredDialButton.setOnClickListener { homeActions.openDialer(activePhoneFilter) }
        binding.previousCallsButton.setOnClickListener {
            if (isFilteredFullLogMode()) {
                filteredFullLogController.previousPage()
            } else if (pageIndex > 0) {
                pageIndex--
                renderCalls()
            }
        }
        binding.nextCallsButton.setOnClickListener {
            if (isFilteredFullLogMode()) {
                filteredFullLogController.nextPage()
            } else if (homeContentRenderer.currentCalls.size >= pageSize()) {
                pageIndex++
                renderCalls()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val phone = intent?.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        activePhoneFilter = if (
            isCrmModeEnabled() && phone.isNotBlank() && !HomeCallPageLoader.isCrmEligible(this, phone)
        ) "" else phone
        activeSearchQuery = ""
        pageIndex = 0
        filteredFullLogController.invalidate()
        companyGeneralNotesController.invalidate()
        if (::binding.isInitialized) {
            searchInputController.resetText()
            renderCalls()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        HomeCallPageLoader.clearSearchCache()
        noteSavedReceiver.register()
        contactsSyncPreparer.prepareOnce()
        companyGeneralNotesController.invalidate()
        HomeCrmPhaseLookup.invalidate()
        crmFiltersController.refreshCompaniesIfNeeded()
        renderCalls()
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            noteSavedReceiver.unregister()
            pullRefreshInProgress = false
            binding.homeCallsRefreshLayout.setRefreshing(false)
        }
        handler.removeCallbacks(filteredFullLogRefreshWatcher)
        noteRefreshController.cancel()
        searchInputController.cancelPending()
        searchController.cancelActiveTask()
        super.onPause()
    }

    override fun onDestroy() {
        searchGeneration.incrementAndGet()
        searchController.cancelActiveTask()
        searchExecutor.shutdownNow()
        callsLoader.release()
        serverCallNotesController.release()
        crmFiltersController.release()
        companyGeneralNotesController.release()
        filteredFullLogController.release()
        contactsSyncPreparer.release()
        super.onDestroy()
    }

    private fun renderCalls() {
        val renderGeneration = callsLoader.invalidate()
        serverCallNotesController.invalidate()
        if (activeSearchQuery.isBlank()) searchController.cancelActiveTask()
        val size = pageSize()
        val crmModeEnabled = isCrmModeEnabled()
        val showCrmFilters = crmModeEnabled && activePhoneFilter.isBlank() && activeSearchQuery.isBlank()
        crmFiltersController.updateVisibility(showCrmFilters)
        homeContentRenderer.prepareForRender(size, keepExistingRows = showCrmFilters)
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            homeContentRenderer.showMissingCallLogPermission()
            completePullToRefresh()
            return
        }
        when {
            activeSearchQuery.isNotBlank() -> searchController.renderSearchCallsAsync()
            activePhoneFilter.isNotBlank() -> filteredFullLogController.render(activePhoneFilter)
            crmModeEnabled -> callsLoader.renderCrmCallsAsync(size, renderGeneration)
            else -> callsLoader.renderLocalCalls(size)
        }
    }

    private fun refreshFromPull() {
        if (pullRefreshInProgress) return
        pullRefreshInProgress = true
        HomeCallPageLoader.clearSearchCache()
        filteredFullLogController.invalidate()
        companyGeneralNotesController.invalidate()
        HomeCrmPhaseLookup.invalidate()
        CallReportNoteOutboxScheduler.enqueue(this, reason = "home_pull_refresh")
        CallReportTopicNoteOutbox.requestSyncNow(this)
        CallReportSyncScheduler.enqueueCatchUp(this, reason = "home_pull_refresh")
        renderCalls()
        if (isFilteredFullLogMode()) {
            handler.removeCallbacks(filteredFullLogRefreshWatcher)
            handler.post(filteredFullLogRefreshWatcher)
        }
    }

    private fun completePullToRefresh() {
        if (!pullRefreshInProgress || !::binding.isInitialized) return
        pullRefreshInProgress = false
        handler.removeCallbacks(filteredFullLogRefreshWatcher)
        binding.homeCallsRefreshLayout.setRefreshing(false)
    }

    private fun isCrmModeEnabled(): Boolean = HomeCrmModeStore.isEnabled(this)

    private fun setCrmMode(enabled: Boolean) {
        if (!HomeCrmModeStore.setEnabled(this, enabled)) return
        homeContentRenderer.clearCalls()
        activePhoneFilter = ""
        pageIndex = 0
        filteredFullLogController.invalidate()
        companyGeneralNotesController.invalidate()
        renderCalls()
    }

    private fun togglePhoneFilter(number: String) {
        if (isCrmModeEnabled() && !HomeCallPageLoader.isCrmEligible(this, number)) return
        val key = HomeCallPageLoader.noteKey(number)
        activePhoneFilter = if (
            activePhoneFilter.isNotBlank() && HomeCallPageLoader.noteKey(activePhoneFilter) == key
        ) "" else number
        pageIndex = 0
        filteredFullLogController.invalidate()
        renderCalls()
    }

    private fun clearPhoneFilter() {
        if (activePhoneFilter.isBlank()) return
        activePhoneFilter = ""
        pageIndex = 0
        filteredFullLogController.invalidate()
        renderCalls()
    }

    private fun isFilteredFullLogMode(): Boolean = activePhoneFilter.isNotBlank() && activeSearchQuery.isBlank()

    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_PHONE_FILTER = "phone_filter"
        private const val FILTERED_REFRESH_CHECK_DELAY_MS = 80L
    }
}
