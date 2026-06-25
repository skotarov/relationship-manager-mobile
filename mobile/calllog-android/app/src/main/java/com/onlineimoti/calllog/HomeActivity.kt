package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)
    private val contactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    private val noteSavedReceiver by lazy {
        HomeNoteSavedReceiverController(this) {
            filteredFullLogController.invalidate()
            renderCalls()
        }
    }
    private val homeActions by lazy {
        HomeActions(
            activity = this,
            binding = binding,
            startTemporaryNoteRefresh = ::startTemporaryNoteRefresh,
            isUnfilteredHome = { activePhoneFilter.isBlank() && activeSearchQuery.isBlank() },
        )
    }
    private val navigationController by lazy {
        HomeNavigationController(
            activity = this,
            binding = binding,
            currentFilter = { activePhoneFilter },
            setFilter = { activePhoneFilter = it },
            filterKey = HomeCallPageLoader::noteKey,
            onFilterChanged = {
                pageIndex = 0
                filteredFullLogController.invalidate()
                renderCalls()
            },
            onOpenSystemHistory = {
                startActivity(
                    Intent(this, SystemCallHistoryActivity::class.java)
                        .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
                )
            },
            onOpenSettings = homeActions::openSettings,
        )
    }
    private val searchUiController by lazy { HomeSearchUiController(this, binding) }
    private val homeStatusRenderer by lazy { HomeStatusRenderer(this, binding, ::dp) }
    private val homeSummaryViewRenderer by lazy { HomeSummaryViewRenderer(this, binding, ::dp) }
    private val searchController by lazy {
        HomeSearchController(
            context = this,
            binding = binding,
            handler = handler,
            searchExecutor = searchExecutor,
            searchGeneration = searchGeneration,
            pageSize = ::pageSize,
            activePhoneFilter = { activePhoneFilter },
            activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex },
            setCurrentCalls = { currentCalls = it },
            renderEmptyState = ::renderEmptyState,
            applyRenderData = ::applyRenderData,
        )
    }
    private val homeCallRowRenderer by lazy {
        HomeCallRowRenderer(
            activity = this,
            dp = ::dp,
            noteKey = HomeCallPageLoader::noteKey,
            roundedRect = ::homeRoundedRect,
            openContactNotesScreen = homeActions::openContactNotesScreen,
            openContactNotePopupForCall = homeActions::openContactNotePopupForCall,
            openDialer = homeActions::openDialer,
            togglePhoneFilter = navigationController::toggleFilter,
        )
    }
    private val filteredFullLogController by lazy {
        FilteredFullLogController(
            activity = this,
            binding = binding,
            dp = ::dp,
            roundedRect = ::homeRoundedRect,
            openContactNotes = homeActions::openContactNotesScreen,
            openCallNoteEditor = homeActions::openContactNotePopupForCall,
            pageSize = ::pageSize,
            onStateChanged = ::renderCalls,
        )
    }
    private var pageIndex = 0
    private var currentCalls: List<PhoneCallRecord> = emptyList()
    private var activePhoneFilter: String = ""
    private var activeSearchQuery: String = ""
    private val screenRefreshController by lazy {
        HomeScreenRefreshController(
            handler = handler,
            windowMs = NOTE_REFRESH_WINDOW_MS,
            intervalMs = NOTE_REFRESH_INTERVAL_MS,
            onStart = filteredFullLogController::invalidate,
            onRefresh = ::renderCalls,
        )
    }
    private val searchRunnable = Runnable {
        activeSearchQuery = binding.searchInput.text?.toString().orEmpty()
        pageIndex = 0
        renderCalls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        activePhoneFilter = intent.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        searchUiController.updateButtonIcon()

        binding.settingsButton.setOnClickListener { navigationController.showOverflowMenu() }
        binding.clearFilterButton.setOnClickListener { navigationController.clearFilter() }
        binding.filteredDialButton.setOnClickListener { homeActions.openDialer(activePhoneFilter) }
        binding.searchButton.setOnClickListener { searchUiController.toggle(::clearSearch) }
        binding.clearSearchButton.setOnClickListener { clearSearch() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(searchRunnable)
                handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
            }
        })
        binding.previousCallsButton.setOnClickListener {
            if (navigationController.isFilterOnly(activeSearchQuery)) {
                filteredFullLogController.previousPage()
            } else if (pageIndex > 0) {
                pageIndex -= 1
                renderCalls()
            }
        }
        binding.nextCallsButton.setOnClickListener {
            if (navigationController.isFilterOnly(activeSearchQuery)) {
                filteredFullLogController.nextPage()
            } else if (currentCalls.size >= pageSize()) {
                pageIndex += 1
                renderCalls()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        activePhoneFilter = intent?.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        activeSearchQuery = ""
        pageIndex = 0
        filteredFullLogController.invalidate()
        if (::binding.isInitialized) {
            binding.searchInput.setText("")
            renderCalls()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        noteSavedReceiver.register()
        contactsSyncPreparer.prepareOnce()
        // Keep the already loaded filtered timeline cached. A real note save broadcasts
        // ACTION_CONTACT_NOTE_SAVED and invalidates it explicitly.
        renderCalls()
    }

    override fun onPause() {
        if (::binding.isInitialized) noteSavedReceiver.unregister()
        screenRefreshController.cancel()
        handler.removeCallbacks(searchRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        searchGeneration.incrementAndGet()
        searchExecutor.shutdownNow()
        filteredFullLogController.release()
        contactsSyncPreparer.release()
        super.onDestroy()
    }

    private fun renderCalls() {
        val size = pageSize()
        binding.previousCallsButton.text = getString(R.string.dynamic_home_previous_calls, size)
        binding.nextCallsButton.text = getString(R.string.dynamic_home_next_calls, size)
        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        binding.clearFilterButton.visibility = if (activePhoneFilter.isBlank()) View.GONE else View.VISIBLE
        updatePhoneFilterStatusStyle()
        renderFilteredContactSummary()
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.homeStatusText.text = getString(R.string.dynamic_home_missing_call_log_permission)
            binding.paginationContainer.visibility = View.GONE
            return
        }
        if (activeSearchQuery.isNotBlank()) {
            searchController.renderSearchCallsAsync()
            return
        }
        if (activePhoneFilter.isNotBlank()) {
            filteredFullLogController.render(activePhoneFilter)
            return
        }
        currentCalls = HomeCallPageLoader.calls(this, activePhoneFilter, activeSearchQuery, pageIndex, size)
        if (currentCalls.isEmpty()) {
            renderEmptyState()
            return
        }
        applyRenderData(
            HomeRenderData(
                calls = currentCalls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(this, currentCalls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(this, currentCalls),
            ),
            size,
        )
    }

    private fun applyRenderData(renderData: HomeRenderData, pageSize: Int) {
        currentCalls = renderData.calls
        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        renderStatusAndPagination(pageSize)
        val isPhoneFiltered = activePhoneFilter.isNotBlank()
        renderData.calls.forEach { call ->
            val key = HomeCallPageLoader.noteKey(call.number)
            val displayName = renderData.contactNamesByNumber[key].orEmpty().ifBlank { call.displayName }
            binding.homeCallsContainer.addView(
                homeCallRowRenderer.compactCallRow(
                    call = call,
                    displayName = displayName,
                    contactNote = if (isPhoneFiltered) null else renderData.contactNotesByNumber[key],
                    callNote = ContactNoteReader.callNoteForPhone(this, call.number, call.startedAt, call.direction),
                    highlightQuery = activeSearchQuery,
                    showContactIdentity = !isPhoneFiltered,
                    showGeneralContactNote = !isPhoneFiltered,
                    showQuickActions = !isPhoneFiltered,
                ),
            )
        }
    }

    private fun renderFilteredContactSummary() {
        if (activePhoneFilter.isBlank()) {
            homeSummaryViewRenderer.render("", "")
            return
        }
        val name = ContactGroupFilter.resolveDisplayName(this, activePhoneFilter)
            .orEmpty()
            .takeIf { HomeCallPageLoader.noteKey(it) != HomeCallPageLoader.noteKey(activePhoneFilter) }
            .orEmpty()
        val note = ContactNoteReader.generalNoteForPhone(this, activePhoneFilter).orEmpty()
        homeSummaryViewRenderer.render(name, note)
    }

    private fun renderEmptyState() {
        homeStatusRenderer.renderEmptyState(
            searchQuery = activeSearchQuery,
            phoneFilter = activePhoneFilter,
            pageIndex = pageIndex,
        )
    }

    private fun renderStatusAndPagination(pageSize: Int) {
        homeStatusRenderer.renderStatusAndPagination(
            pageSize = pageSize,
            callCount = currentCalls.size,
            searchQuery = activeSearchQuery,
            phoneFilter = activePhoneFilter,
            pageIndex = pageIndex,
        )
    }

    private fun updatePhoneFilterStatusStyle() {
        homeStatusRenderer.updatePhoneFilterStyle(activePhoneFilter)
    }

    private fun clearSearch() {
        handler.removeCallbacks(searchRunnable)
        binding.searchInput.setText("")
        activeSearchQuery = ""
        pageIndex = 0
        renderCalls()
        searchUiController.updateButtonIcon()
    }

    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    private fun startTemporaryNoteRefresh() {
        screenRefreshController.start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_PHONE_FILTER = "phone_filter"
        private const val NOTE_REFRESH_WINDOW_MS = 2_000L
        private const val NOTE_REFRESH_INTERVAL_MS = 400L
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}
