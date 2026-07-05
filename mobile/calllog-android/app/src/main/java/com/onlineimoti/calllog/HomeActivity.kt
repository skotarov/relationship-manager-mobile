package com.onlineimoti.calllog

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private val uiGeometry: HomeUiGeometry by lazy { HomeUiGeometry(resources) }
    private val searchExecutor = Executors.newFixedThreadPool(2)
    private val searchGeneration = AtomicInteger(0)
    private var smsPermissionPromptShownThisSession = false
    private var smsPermissionRequestInFlight = false
    private var pageIndex = 0
    private var activePhoneFilter = ""
    private var activeSearchQuery = ""

    private val readSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        smsPermissionRequestInFlight = false
        HomeCallPageLoader.clearSearchCache()
        filteredFullLogController.invalidate()
        if (::binding.isInitialized && !isFinishing && !isDestroyed) renderCalls()
    }
    private val contactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    private val noteSavedReceiver by lazy {
        HomeNoteSavedReceiverController(this) {
            HomeCallPageLoader.clearSearchCache()
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            renderCalls()
        }
    }
    private val noteRefreshController by lazy {
        HomeNoteRefreshController(handler, {
            HomeCallPageLoader.clearSearchCache()
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
        }, ::renderCalls)
    }
    private val homeActions by lazy {
        HomeActions(this, binding, noteRefreshController::start) {
            activePhoneFilter.isBlank() && activeSearchQuery.isBlank()
        }
    }
    private val companyGeneralNotesController by lazy {
        HomeCompanyGeneralNotesController(this, handler) {
            if (::binding.isInitialized && !isFinishing && !isDestroyed) {
                homeContentRenderer.renderCurrentRowsAfterCompanyLabels(pageSize())
            }
        }
    }
    private val serverCallNotesController by lazy { HomeServerCallNotesController(this, handler) }
    private val crmFiltersController by lazy {
        HomeCrmFiltersController(this, binding, handler, uiGeometry::dp, uiGeometry::roundedRect) {
            homeContentRenderer.clearCalls()
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
    private val homeContentRenderer by lazy {
        HomeContentRenderer(
            this, binding, { activePhoneFilter }, { activeSearchQuery }, { pageIndex },
            ::isCrmModeEnabled, { crmFiltersController.hasActiveFilters() }, uiGeometry::dp,
            uiGeometry::roundedRect, homeCallRowRenderer, companyGeneralNotesController,
            filteredContactSummaryChipsUi,
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
        )
    }
    private val searchController by lazy {
        HomeSearchController(
            this, binding, handler, searchExecutor, searchGeneration, serverCallNotesController,
            ::pageSize, { activePhoneFilter }, { activeSearchQuery }, ::isCrmModeEnabled, { pageIndex },
            homeContentRenderer::replaceCurrentCalls, homeContentRenderer::renderEmptyState,
            homeContentRenderer::applyRenderData, pullRefreshController::complete,
        )
    }
    private val searchInputController by lazy {
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
    private val filteredFullLogController by lazy {
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
    private val timelineCoordinator by lazy {
        HomeTimelineCoordinator(
            activity = this,
            callsLoader = callsLoader,
            serverCallNotes = serverCallNotesController,
            searchController = searchController,
            contentRenderer = homeContentRenderer,
            crmFilters = crmFiltersController,
            filteredFullLog = filteredFullLogController,
            pullRefresh = pullRefreshController,
            activePhoneFilter = { activePhoneFilter },
            setActivePhoneFilter = { activePhoneFilter = it },
            activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex },
            setPageIndex = { pageIndex = it },
            pageSize = ::pageSize,
            isCrmModeEnabled = ::isCrmModeEnabled,
            onCrmModeChanged = companyGeneralNotesController::invalidate,
            requestSmsPermission = ::requestSmsPermissionForFilteredHistoryIfNeeded,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        activePhoneFilter = intent.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        crmFiltersController.updateVisibility(isCrmModeEnabled() && activePhoneFilter.isBlank())
        homeContentRenderer.prepareForRender(pageSize(), keepExistingRows = false)
        searchInputController.bind()
        pullRefreshController.bind(::refreshFromPull)
        HomeScreenActionBinder.wire(
            this,
            binding,
            { HomeOverflowMenu.show(this, binding.settingsButton) { homeActions.openSettings() } },
            ::isCrmModeEnabled,
            timelineCoordinator::setCrmMode,
            timelineCoordinator::clearPhoneFilter,
            { homeActions.openDialer(activePhoneFilter) },
            timelineCoordinator::previousPage,
            timelineCoordinator::nextPage,
        )
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
        callsLoader.release()
        serverCallNotesController.release()
        crmFiltersController.release()
        companyGeneralNotesController.release()
        filteredFullLogController.release()
        contactsSyncPreparer.release()
        super.onDestroy()
    }

    private fun renderCalls() = timelineCoordinator.renderCalls()

    private fun requestSmsPermissionForFilteredHistoryIfNeeded() {
        if (SmsMessageReader.hasReadSmsPermission(this) ||
            smsPermissionRequestInFlight ||
            smsPermissionPromptShownThisSession
        ) return
        smsPermissionPromptShownThisSession = true
        smsPermissionRequestInFlight = true
        readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
    }

    private fun refreshFromPull() {
        HomeCallPageLoader.clearSearchCache()
        filteredFullLogController.invalidate()
        companyGeneralNotesController.invalidate()
        HomeCrmPhaseLookup.invalidate()
        CallReportNoteOutboxScheduler.enqueue(this, reason = "home_pull_refresh")
        CallReportTopicNoteOutbox.requestSyncNow(this)
        CallReportSyncScheduler.enqueueCatchUp(this, reason = "home_pull_refresh")
        renderCalls()
    }

    private fun isCrmModeEnabled() = HomeCrmModeStore.isEnabled(this)
    private fun pageSize() = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_PHONE_FILTER = "phone_filter"
    }
}
