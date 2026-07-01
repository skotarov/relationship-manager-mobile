package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)
    private val crmExecutor = Executors.newSingleThreadExecutor()
    private val crmGeneration = AtomicInteger(0)
    private val contactsSyncPreparer by lazy { HomeContactsSyncPreparer(this) }
    private val noteSavedReceiver by lazy {
        HomeNoteSavedReceiverController(this) {
            filteredFullLogController.invalidate()
            companyGeneralNotesController.invalidate()
            renderCalls()
        }
    }
    private val homeActions by lazy {
        HomeActions(this, binding, ::startTemporaryNoteRefresh) {
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
    private val crmFiltersController by lazy {
        HomeCrmFiltersController(
            activity = this,
            binding = binding,
            handler = handler,
            dp = ::dp,
            roundedRect = ::roundedRect,
            onFilterChanged = {
                homeContentRenderer.clearCalls()
                pageIndex = 0
                filteredFullLogController.invalidate()
                companyGeneralNotesController.invalidate()
                renderCalls()
            },
        )
    }
    private val filteredContactSummaryChipsUi by lazy {
        HomeCompanyScopeChipsUi(this, ::dp, ::roundedRect)
    }
    private val homeCallRowRenderer by lazy {
        HomeCallRowRenderer(
            activity = this,
            dp = ::dp,
            noteKey = HomeCallPageLoader::noteKey,
            roundedRect = ::roundedRect,
            openContactNotesScreen = homeActions::openContactNotesScreen,
            openContactNotePopupForCall = homeActions::openContactNotePopupForCall,
            openDialer = homeActions::openDialer,
            togglePhoneFilter = ::togglePhoneFilter,
        )
    }
    private val homeContentRenderer by lazy {
        HomeContentRenderer(
            activity = this,
            binding = binding,
            activePhoneFilter = { activePhoneFilter },
            activeSearchQuery = { activeSearchQuery },
            pageIndex = { pageIndex },
            isCrmModeEnabled = ::isCrmModeEnabled,
            hasActiveCrmFilters = { crmFiltersController.hasActiveFilters() },
            dp = ::dp,
            roundedRect = ::roundedRect,
            rowRenderer = homeCallRowRenderer,
            companyGeneralNotes = companyGeneralNotesController,
            scopeChipsUi = filteredContactSummaryChipsUi,
        )
    }
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
            isCrmModeEnabled = ::isCrmModeEnabled,
            pageIndex = { pageIndex },
            setCurrentCalls = homeContentRenderer::replaceCurrentCalls,
            renderEmptyState = homeContentRenderer::renderEmptyState,
            applyRenderData = homeContentRenderer::applyRenderData,
        )
    }
    private val filteredFullLogController by lazy {
        FilteredFullLogController(
            activity = this,
            binding = binding,
            dp = ::dp,
            roundedRect = ::roundedRect,
            openContactNotes = homeActions::openContactNotesScreen,
            openCallNoteEditor = homeActions::openContactNotePopupForCall,
            pageSize = ::pageSize,
            onStateChanged = ::renderCalls,
        )
    }

    private var pageIndex = 0
    private var noteRefreshUntilMs = 0L
    private var activePhoneFilter = ""
    private var activeSearchQuery = ""

    private val noteRefreshRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > noteRefreshUntilMs) return
            renderCalls()
            handler.postDelayed(this, NOTE_REFRESH_INTERVAL_MS)
        }
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
        updateSearchButtonIcon()
        crmFiltersController.updateVisibility(isCrmModeEnabled() && activePhoneFilter.isBlank())
        homeContentRenderer.prepareForRender(pageSize(), keepExistingRows = false)
        binding.settingsButton.setOnClickListener {
            HomeOverflowMenu.show(this, binding.settingsButton) { homeActions.openSettings() }
        }
        binding.crmModeButton.setOnClickListener { setCrmMode(!isCrmModeEnabled()) }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
        binding.filteredDialButton.setOnClickListener { homeActions.openDialer(activePhoneFilter) }
        binding.searchButton.setOnClickListener { toggleSearchRow() }
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val phone = intent?.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        activePhoneFilter = if (
            isCrmModeEnabled() && phone.isNotBlank() && !HomeCallPageLoader.isCrmEligible(this, phone)
        ) {
            ""
        } else {
            phone
        }
        activeSearchQuery = ""
        pageIndex = 0
        filteredFullLogController.invalidate()
        companyGeneralNotesController.invalidate()
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
        companyGeneralNotesController.invalidate()
        HomeCrmPhaseLookup.invalidate()
        crmFiltersController.refreshCompaniesIfNeeded()
        renderCalls()
    }

    override fun onPause() {
        if (::binding.isInitialized) noteSavedReceiver.unregister()
        handler.removeCallbacks(noteRefreshRunnable)
        handler.removeCallbacks(searchRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        searchGeneration.incrementAndGet()
        crmGeneration.incrementAndGet()
        searchExecutor.shutdownNow()
        crmExecutor.shutdownNow()
        crmFiltersController.release()
        companyGeneralNotesController.release()
        filteredFullLogController.release()
        contactsSyncPreparer.release()
        super.onDestroy()
    }

    private fun renderCalls() {
        val generation = crmGeneration.incrementAndGet()
        val size = pageSize()
        val crmModeEnabled = isCrmModeEnabled()
        val showCrmFilters = crmModeEnabled && activePhoneFilter.isBlank() && activeSearchQuery.isBlank()
        crmFiltersController.updateVisibility(showCrmFilters)
        homeContentRenderer.prepareForRender(size, keepExistingRows = showCrmFilters)
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            homeContentRenderer.showMissingCallLogPermission()
            return
        }
        when {
            activeSearchQuery.isNotBlank() -> searchController.renderSearchCallsAsync()
            activePhoneFilter.isNotBlank() -> filteredFullLogController.render(activePhoneFilter)
            crmModeEnabled -> renderCrmCallsAsync(size, generation)
            else -> renderLocalCalls(size)
        }
    }

    private fun renderLocalCalls(pageSize: Int) {
        val calls = HomeCallPageLoader.calls(
            context = this,
            activePhoneFilter = activePhoneFilter,
            searchQuery = activeSearchQuery,
            pageIndex = pageIndex,
            pageSize = pageSize,
            crmMode = false,
        )
        if (calls.isEmpty()) {
            homeContentRenderer.renderEmptyState()
            return
        }
        homeContentRenderer.applyRenderData(
            HomeRenderData(
                calls = calls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(this, calls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(this, calls),
            ),
            pageSize,
        )
    }

    private fun renderCrmCallsAsync(pageSize: Int, expectedGeneration: Int) {
        val filterState = crmFiltersController.state()
        if (homeContentRenderer.currentCalls.isEmpty()) homeContentRenderer.showCrmLoading()
        val requestedPage = pageIndex
        val appContext = applicationContext
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
                val current = expectedGeneration == crmGeneration.get() &&
                    !isFinishing &&
                    !isDestroyed &&
                    isCrmModeEnabled() &&
                    activePhoneFilter.isBlank() &&
                    activeSearchQuery.isBlank() &&
                    pageIndex == requestedPage &&
                    crmFiltersController.state() == filterState
                if (!current) return@post
                if (data.calls.isEmpty()) homeContentRenderer.renderEmptyState()
                else homeContentRenderer.applyRenderData(data, pageSize)
            }
        }
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
        ) {
            ""
        } else {
            number
        }
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

    private fun toggleSearchRow() {
        val show = binding.searchRow.visibility != View.VISIBLE
        if (show) {
            binding.searchRow.visibility = View.VISIBLE
            updateSearchButtonIcon()
            binding.searchInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(
                binding.searchInput,
                InputMethodManager.SHOW_IMPLICIT,
            )
        } else {
            clearSearch()
            binding.searchRow.visibility = View.GONE
            updateSearchButtonIcon()
        }
    }

    private fun clearSearch() {
        handler.removeCallbacks(searchRunnable)
        binding.searchInput.setText("")
        activeSearchQuery = ""
        pageIndex = 0
        renderCalls()
        updateSearchButtonIcon()
    }

    private fun updateSearchButtonIcon() {
        binding.searchButton.setImageResource(
            if (binding.searchRow.visibility == View.VISIBLE) R.drawable.ic_popup_close else R.drawable.ic_search,
        )
    }

    private fun isFilteredFullLogMode(): Boolean = activePhoneFilter.isNotBlank() && activeSearchQuery.isBlank()

    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    private fun startTemporaryNoteRefresh() {
        filteredFullLogController.invalidate()
        companyGeneralNotesController.invalidate()
        noteRefreshUntilMs = System.currentTimeMillis() + NOTE_REFRESH_WINDOW_MS
        handler.removeCallbacks(noteRefreshRunnable)
        handler.postDelayed(noteRefreshRunnable, NOTE_REFRESH_INTERVAL_MS)
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
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
