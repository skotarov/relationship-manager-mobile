package com.onlineimoti.calllog

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
    private val uiGeometry by lazy { HomeUiGeometry(resources) }
    private val searchExecutor = Executors.newFixedThreadPool(2)
    private val refreshExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)
    private var pageIndex = 0
    private var activePhoneFilter = ""
    private var activeSearchQuery = ""
    private var crmContactsMode = false

    private val readSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { runtimeController.onSmsPermissionResult() }
    private val contactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    private val noteSavedReceiver by lazy {
        HomeNoteSavedReceiverController(this) {
            HomeCallPageLoader.clearSearchCache()
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            crmContactsContentView.invalidate()
            renderCalls()
        }
    }
    private val noteRefreshController by lazy {
        HomeNoteRefreshController(
            handler,
            {
                HomeCallPageLoader.clearSearchCache()
                filteredFullLogController.invalidate()
                companyGeneralNotesController.invalidate()
                crmContactsContentView.invalidate()
            },
            ::renderCalls,
        )
    }
    private val homeActions by lazy {
        HomeActions(this, binding, noteRefreshController::start) {
            activePhoneFilter.isBlank() && activeSearchQuery.isBlank()
        }
    }
    private val crmTimelineToggle by lazy {
        HomeCrmTimelineModeToggle(this, binding, uiGeometry::dp) {
            timelineCoordinator.toggleCrmCallLogFromOverflow()
        }
    }
    private val companyGeneralNotesController by lazy {
        HomeCompanyGeneralNotesController(this, handler) {
            if (::binding.isInitialized && !isFinishing && !isDestroyed) {
                if (isCrmContactsMode()) crmContactsContentView.renderCurrentRowsAfterCompanyLabels(pageSize())
                else homeContentRenderer.renderCurrentRowsAfterCompanyLabels(pageSize())
            }
        }
    }
    private val serverCallNotesController by lazy { HomeServerCallNotesController(this, handler) }
    private val crmFiltersController by lazy {
        HomeCrmFiltersController(this, binding, handler, uiGeometry::dp, uiGeometry::roundedRect) {
            homeContentRenderer.clearCalls()
            crmContactsContentView.invalidate()
            pageIndex = 0
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            renderCalls()
        }
    }
    private val filteredContactSummaryChipsUi by lazy {
        HomeCompanyScopeChipsUi(this, uiGeometry::dp, uiGeometry::roundedRect)
    }
    private val homeCallRowRenderer by lazy {
        HomeCallRowRenderer(
            this, uiGeometry::dp, HomeCallPageLoader::noteKey, uiGeometry::roundedRect,
            homeActions::openContactNotesScreen, homeActions::openContactNotePopupForCall,
            homeActions::openDialer, { timelineCoordinator.togglePhoneFilter(it) },
        )
    }
    private val crmContactRowRenderer by lazy {
        HomeCrmContactRowRenderer(
            this, uiGeometry::dp, uiGeometry::roundedRect, filteredContactSummaryChipsUi,
            homeActions::openContactNotesScreen, homeActions::openDialer,
        )
    }
    private val homeContentRenderer by lazy {
        HomeContentRenderer(
            this, binding, { activePhoneFilter }, { activeSearchQuery }, { pageIndex },
            ::isCrmModeEnabled, ::isCrmContactsMode, { crmFiltersController.hasActiveFilters() },
            uiGeometry::dp, uiGeometry::roundedRect, homeCallRowRenderer, homeActions::openDialer,
            companyGeneralNotesController, filteredContactSummaryChipsUi,
        )
    }
    private val crmContactsContentView by lazy {
        HomeCrmContactsContentView(
            this, binding, { pageIndex }, homeContentRenderer, companyGeneralNotesController,
            crmContactRowRenderer, crmTimelineToggle, { crmFiltersController.hasActiveFilters() },
        )
    }
    private val pullRefreshController by lazy {
        HomePullRefreshController(binding, handler) {
            activePhoneFilter.isNotBlank() && activeSearchQuery.isBlank()
        }
    }
    private val callsLoader by lazy {
        HomeCallsLoader(
            this, handler, homeContentRenderer, crmFiltersController, serverCallNotesController,
            { activePhoneFilter }, { activeSearchQuery }, { pageIndex }, ::isCrmModeEnabled,
            pullRefreshController::complete,
            onCrmCallsRendered = { count -> crmTimelineToggle.showRange(false, pageIndex, pageSize(), count) },
            onCrmCallsEmpty = { crmTimelineToggle.showEmpty(false) },
        )
    }
    private val crmContactsLoader by lazy {
        HomeCrmContactsLoader(
            this, handler, crmContactsContentView, crmFiltersController,
            { activePhoneFilter }, { activeSearchQuery }, { pageIndex },
            ::isServerReady, ::isCrmContactsMode, pullRefreshController::complete,
        )
    }
    private val searchController by lazy {
        HomeSearchController(
            this, binding, handler, searchExecutor, searchGeneration, serverCallNotesController,
            ::pageSize, { activePhoneFilter }, { activeSearchQuery }, ::isCrmModeEnabled,
            { pageIndex }, homeContentRenderer::replaceCurrentCalls,
            homeContentRenderer::renderEmptyState, homeContentRenderer::applyRenderData,
            pullRefreshController::complete,
        )
    }
    private val searchInputController by lazy {
        HomeSearchInputController(
            this, binding, handler,
            { query -> activeSearchQuery = query; pageIndex = 0; renderCalls() },
            { activeSearchQuery = ""; pageIndex = 0; renderCalls() },
        )
    }
    private val filteredFullLogController by lazy {
        FilteredFullLogController(
            this, binding, uiGeometry::dp, uiGeometry::roundedRect,
            homeActions::openContactNotesScreen,
            { call, name, note -> homeActions.openContactNotePopupForCall(call, name, note) },
            ::pageSize, ::renderCalls,
        )
    }
    private val timelineCoordinator by lazy {
        HomeTimelineCoordinator(
            activity = this, callsLoader = callsLoader, contactsLoader = crmContactsLoader,
            serverCallNotes = serverCallNotesController, searchController = searchController,
            contentRenderer = homeContentRenderer, crmFilters = crmFiltersController,
            filteredFullLog = filteredFullLogController, pullRefresh = pullRefreshController,
            timelineToggle = crmTimelineToggle, activePhoneFilter = { activePhoneFilter },
            setActivePhoneFilter = { activePhoneFilter = it }, activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex }, setPageIndex = { pageIndex = it }, pageSize = ::pageSize,
            isCrmModeEnabled = ::isCrmModeEnabled, isCrmContactsMode = ::isCrmContactsMode,
            setCrmContactsMode = { crmContactsMode = it },
            onCrmModeChanged = {
                companyGeneralNotesController.invalidate()
                crmContactsContentView.invalidate()
                runtimeController.updateHeader()
            },
            requestSmsPermission = runtimeController::requestSmsPermissionForFilteredHistoryIfNeeded,
        )
    }
    private val runtimeController by lazy {
        HomeActivityRuntimeController(
            this, { binding }, refreshExecutor, readSmsPermissionLauncher,
            { activePhoneFilter }, { activeSearchQuery }, ::isCrmContactsMode, ::isServerReady,
            ::isFilteredFullLogMode, HomeCallPageLoader::clearSearchCache,
            filteredFullLogController::invalidate, companyGeneralNotesController::invalidate,
            crmContactsContentView::invalidate,
            { force -> crmFiltersController.refreshCompaniesIfNeeded(force = force) }, ::renderCalls,
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
        runtimeController.updateHeader()
        crmFiltersController.updateVisibility(isCrmModeEnabled() && activePhoneFilter.isBlank())
        homeContentRenderer.prepareForRender(pageSize(), keepExistingRows = false)
        searchInputController.bind()
        pullRefreshController.bind(runtimeController::refreshFromPull)
        HomeScreenActionBinder.wire(
            this, binding,
            { HomeOverflowMenu.show(this, binding.settingsButton) { homeActions.openSettings() } },
            timelineCoordinator::toggleCrmContactsMode, timelineCoordinator::clearPhoneFilter,
            { homeActions.openDialer(activePhoneFilter) }, timelineCoordinator::previousPage,
            timelineCoordinator::nextPage, timelineCoordinator::isOnLaterPage,
            timelineCoordinator::goToFirstPage,
        )
        if (DistributionCapabilities.isPlayBusinessBuild) binding.crmModeButton.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (timelineCoordinator.returnFromFullLog()) return
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent?) {
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
        runtimeController.updateHeader()
        timelineCoordinator.renderCalls()
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
