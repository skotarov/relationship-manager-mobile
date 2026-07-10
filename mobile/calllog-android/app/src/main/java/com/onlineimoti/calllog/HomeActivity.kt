package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HomeActivity : FontScaledAppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private val uiGeometry: HomeUiGeometry by lazy { HomeUiGeometry(resources) }
    private val searchExecutor = Executors.newFixedThreadPool(2)
    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)
    private var smsPermissionPromptShownThisSession = false
    private var smsPermissionRequestInFlight = false
    private var pageIndex = 0
    private var activePhoneFilter = ""
    private var activeSearchQuery = ""
    private var crmContactsMode = false

    private val readSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        smsPermissionRequestInFlight = false
        HomeCallPageLoader.clearSearchCache()
        filteredFullLogController.invalidate()
        if (::binding.isInitialized && !isFinishing && !isDestroyed) renderCalls()
    }
    private val contactsSyncPreparer: HomeContactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    private val noteSavedReceiver: HomeNoteSavedReceiverController by lazy {
        HomeNoteSavedReceiverController(this) {
            HomeCallPageLoader.clearSearchCache()
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            crmContactsContentView.invalidate()
            renderCalls()
        }
    }
    private val noteRefreshController: HomeNoteRefreshController by lazy {
        HomeNoteRefreshController(handler, {
            HomeCallPageLoader.clearSearchCache()
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            crmContactsContentView.invalidate()
        }, ::renderCalls)
    }
    private val homeActions: HomeActions by lazy {
        HomeActions(this, binding, noteRefreshController::start) {
            activePhoneFilter.isBlank() && activeSearchQuery.isBlank()
        }
    }
    private val crmTimelineToggle: HomeCrmTimelineModeToggle by lazy {
        HomeCrmTimelineModeToggle(this, binding, uiGeometry::dp) {
            timelineCoordinator.toggleCrmCallLogFromOverflow()
        }
    }
    private val companyGeneralNotesController: HomeCompanyGeneralNotesController by lazy {
        HomeCompanyGeneralNotesController(this, handler) {
            if (::binding.isInitialized && !isFinishing && !isDestroyed) {
                if (isCrmContactsMode()) crmContactsContentView.renderCurrentRowsAfterCompanyLabels(pageSize())
                else homeContentRenderer.renderCurrentRowsAfterCompanyLabels(pageSize())
            }
        }
    }
    private val serverCallNotesController: HomeServerCallNotesController by lazy {
        HomeServerCallNotesController(this, handler)
    }
    private val crmFiltersController: HomeCrmFiltersController by lazy {
        HomeCrmFiltersController(this, binding, handler, uiGeometry::dp, uiGeometry::roundedRect) {
            homeContentRenderer.clearCalls()
            crmContactsContentView.invalidate()
            pageIndex = 0
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            renderCalls()
        }
    }
    private val filteredContactSummaryChipsUi: HomeCompanyScopeChipsUi by lazy {
        HomeCompanyScopeChipsUi(this, uiGeometry::dp, uiGeometry::roundedRect)
    }
    private val homeCallRowRenderer: HomeCallRowRenderer by lazy {
        HomeCallRowRenderer(
            this, uiGeometry::dp, HomeCallPageLoader::noteKey, uiGeometry::roundedRect,
            homeActions::openContactNotesScreen, homeActions::openContactNotePopupForCall,
            homeActions::openDialer, { timelineCoordinator.togglePhoneFilter(it) },
        )
    }
    private val crmContactRowRenderer: HomeCrmContactRowRenderer by lazy {
        HomeCrmContactRowRenderer(
            this,
            uiGeometry::dp,
            uiGeometry::roundedRect,
            filteredContactSummaryChipsUi,
            homeActions::openContactNotesScreen,
            homeActions::openDialer,
        )
    }
    private val homeContentRenderer: HomeContentRenderer by lazy {
        HomeContentRenderer(
            this, binding, { activePhoneFilter }, { activeSearchQuery }, { pageIndex },
            ::isCrmModeEnabled, ::isCrmContactsMode, { crmFiltersController.hasActiveFilters() }, uiGeometry::dp,
            uiGeometry::roundedRect, homeCallRowRenderer, homeActions::openDialer,
            companyGeneralNotesController, filteredContactSummaryChipsUi,
        )
    }
    private val crmContactsContentView: HomeCrmContactsContentView by lazy {
        HomeCrmContactsContentView(
            this,
            binding,
            { pageIndex },
            homeContentRenderer,
            companyGeneralNotesController,
            crmContactRowRenderer,
            crmTimelineToggle,
            { crmFiltersController.hasActiveFilters() },
        )
    }
    private val pullRefreshController: HomePullRefreshController by lazy {
        HomePullRefreshController(binding, handler) {
            activePhoneFilter.isNotBlank() && activeSearchQuery.isBlank()
        }
    }
    private val callsLoader: HomeCallsLoader by lazy {
        HomeCallsLoader(
            this, handler, homeContentRenderer, crmFiltersController, serverCallNotesController,
            { activePhoneFilter }, { activeSearchQuery }, { pageIndex }, ::isCrmModeEnabled,
            pullRefreshController::complete,
            onCrmCallsRendered = { count ->
                crmTimelineToggle.showRange(false, pageIndex, pageSize(), count)
            },
            onCrmCallsEmpty = { crmTimelineToggle.showEmpty(false) },
        )
    }
    private val crmContactsLoader: HomeCrmContactsLoader by lazy {
        HomeCrmContactsLoader(
            this,
            handler,
            crmContactsContentView,
            crmFiltersController,
            { activePhoneFilter },
            { activeSearchQuery },
            { pageIndex },
            ::isServerReady,
            ::isCrmContactsMode,
            pullRefreshController::complete,
        )
    }
    private val searchController: HomeSearchController by lazy {
        HomeSearchController(
            this, binding, handler, searchExecutor, searchGeneration, serverCallNotesController,
            ::pageSize, { activePhoneFilter }, { activeSearchQuery }, ::isCrmModeEnabled, { pageIndex },
            homeContentRenderer::replaceCurrentCalls, homeContentRenderer::renderEmptyState,
            homeContentRenderer::applyRenderData, pullRefreshController::complete,
        )
    }
    private val searchInputController: HomeSearchInputController by lazy {
        HomeSearchInputController(
            this, binding, handler,
            { query ->
                activeSearchQuery = query
                pageIndex = 0
                renderCalls()
            },
            {
                activeSearchQuery = ""
                pageIndex = 0
                renderCalls()
            },
        )
    }
    private val filteredFullLogController: FilteredFullLogController by lazy {
        FilteredFullLogController(
            this, binding, uiGeometry::dp, uiGeometry::roundedRect,
            homeActions::openContactNotesScreen,
            { call, displayName, renderedNote ->
                homeActions.openContactNotePopupForCall(call, displayName, renderedNote)
            },
            ::pageSize,
            ::renderCalls,
        )
    }
    private val timelineCoordinator: HomeTimelineCoordinator by lazy {
        HomeTimelineCoordinator(
            activity = this,
            callsLoader = callsLoader,
            contactsLoader = crmContactsLoader,
            serverCallNotes = serverCallNotesController,
            searchController = searchController,
            contentRenderer = homeContentRenderer,
            crmFilters = crmFiltersController,
            filteredFullLog = filteredFullLogController,
            pullRefresh = pullRefreshController,
            timelineToggle = crmTimelineToggle,
            activePhoneFilter = { activePhoneFilter },
            setActivePhoneFilter = { activePhoneFilter = it },
            activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex },
            setPageIndex = { pageIndex = it },
            pageSize = ::pageSize,
            isCrmModeEnabled = ::isCrmModeEnabled,
            isCrmContactsMode = ::isCrmContactsMode,
            setCrmContactsMode = { crmContactsMode = it },
            onCrmModeChanged = {
                companyGeneralNotesController.invalidate()
                crmContactsContentView.invalidate()
                updateCrmContactsHeader()
            },
            requestSmsPermission = ::requestSmsPermissionForFilteredHistoryIfNeeded,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        if (DistributionCapabilities.isPlayBusinessBuild && !CorporateAccess.isActive(this)) {
            startActivity(Intent(this, CompanyAccountActivity::class.java).apply {
                putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_LOGIN)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
            return
        }
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        crmContactsMode = DistributionCapabilities.isPlayBusinessBuild
        binding.crmContactsBackButton.setOnClickListener {
            if (!timelineCoordinator.returnFromFullLog()) timelineCoordinator.returnToCallLog()
        }
        crmTimelineToggle
        activePhoneFilter = intent.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        updateCrmContactsHeader()
        crmFiltersController.updateVisibility(isCrmModeEnabled() && activePhoneFilter.isBlank())
        homeContentRenderer.prepareForRender(pageSize(), keepExistingRows = false)
        searchInputController.bind()
        pullRefreshController.bind(::refreshFromPull)
        HomeScreenActionBinder.wire(
            this,
            binding,
            { HomeOverflowMenu.show(this, binding.settingsButton) { homeActions.openSettings() } },
            timelineCoordinator::toggleCrmContactsMode,
            timelineCoordinator::clearPhoneFilter,
            { homeActions.openDialer(activePhoneFilter) },
            timelineCoordinator::previousPage,
            timelineCoordinator::nextPage,
            timelineCoordinator::isOnLaterPage,
            timelineCoordinator::goToFirstPage,
        )
        if (DistributionCapabilities.isPlayBusinessBuild) binding.crmModeButton.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (timelineCoordinator.returnFromFullLog()) return
        super.onBackPressed()
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
        crmContactsContentView.invalidate()
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
        crmContactsContentView.invalidate()
        HomeCrmPhaseLookup.invalidate()
        crmFiltersController.refreshCompaniesIfNeeded()
        renderCalls()
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            noteSavedReceiver.unregister()
            pullRefreshController.cancel()
        }
        noteRefreshController.cancel()
        searchInputController.cancelPending()
        searchController.cancelActiveTask()
        super.onPause()
    }

    override fun onDestroy() {
        searchGeneration.incrementAndGet()
        pullRefreshController.cancel()
        searchController.cancelActiveTask()
        searchExecutor.shutdownNow()
        refreshExecutor.shutdownNow()
        callsLoader.release()
        crmContactsLoader.release()
        serverCallNotesController.release()
        crmFiltersController.release()
        companyGeneralNotesController.release()
        filteredFullLogController.release()
        contactsSyncPreparer.release()
        super.onDestroy()
    }

    private fun renderCalls() {
        updateCrmContactsHeader()
        timelineCoordinator.renderCalls()
    }

    /** Reuses the compact back-and-title header for both Contacts and a filtered full log. */
    private fun updateCrmContactsHeader() {
        if (!::binding.isInitialized) return
        val contactsVisible = isCrmContactsMode() && isServerReady()
        val fullLogVisible = !contactsVisible && isFilteredFullLogMode()
        val customHeaderVisible = contactsVisible || fullLogVisible
        binding.relationshipManagerWordmark.visibility = if (customHeaderVisible) View.GONE else View.VISIBLE
        binding.crmContactsHeader.visibility = if (customHeaderVisible) View.VISIBLE else View.GONE
        binding.crmContactsTitleText.text = when {
            fullLogVisible -> getString(R.string.open_full_log)
            contactsVisible -> getString(R.string.runtime_crm_clients)
            else -> ""
        }
    }

    private fun requestSmsPermissionForFilteredHistoryIfNeeded() {
        if (!DistributionCapabilities.supportsLocalDeviceData ||
            SmsMessageReader.hasReadSmsPermission(this) ||
            smsPermissionRequestInFlight ||
            smsPermissionPromptShownThisSession
        ) return
        smsPermissionPromptShownThisSession = true
        smsPermissionRequestInFlight = true
        readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    private fun refreshFromPull() {
        val appContext = applicationContext
        HomeCallPageLoader.clearSearchCache()
        filteredFullLogController.invalidate()
        companyGeneralNotesController.invalidate()
        crmContactsContentView.invalidate()
        HomeCrmPhaseLookup.invalidate()
        crmFiltersController.refreshCompaniesIfNeeded(force = true)
        runCatching {
            refreshExecutor.execute {
                runCatching { CallReportNoteOutboxScheduler.enqueue(appContext, reason = "home_pull_refresh") }
                runCatching { CallReportTopicNoteOutbox.requestSyncNow(appContext) }
                runCatching { CallReportSyncScheduler.enqueueCatchUp(appContext, reason = "home_pull_refresh") }
            }
        }
        renderCalls()
    }

    private fun isCrmModeEnabled() = HomeCrmModeStore.isEnabled(this)
    private fun isServerReady() = CallReportRemoteAccess.isReady(ConfigStore.load(this))
    private fun isCrmContactsMode() = DistributionCapabilities.isPlayBusinessBuild || crmContactsMode
    private fun isFilteredFullLogMode() = activePhoneFilter.isNotBlank() && activeSearchQuery.isBlank()
    private fun pageSize() = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_PHONE_FILTER = "phone_filter"
    }
}
