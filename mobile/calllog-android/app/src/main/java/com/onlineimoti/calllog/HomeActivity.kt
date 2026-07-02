package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
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
            filteredFullLogController.invalidate(); companyGeneralNotesController.invalidate(); renderCalls()
        }
    }
    private val homeActions by lazy {
        HomeActions(this, binding, ::startTemporaryNoteRefresh) { activePhoneFilter.isBlank() && activeSearchQuery.isBlank() }
    }
    private val searchController by lazy {
        HomeSearchController(
            context = this, binding = binding, handler = handler, searchExecutor = searchExecutor,
            searchGeneration = searchGeneration, pageSize = ::pageSize,
            activePhoneFilter = { activePhoneFilter }, activeSearchQuery = { activeSearchQuery },
            isCrmModeEnabled = ::isCrmModeEnabled, pageIndex = { pageIndex }, setCurrentCalls = { currentCalls = it },
            renderEmptyState = ::renderEmptyState, applyRenderData = ::applyRenderData,
        )
    }
    private val companyGeneralNotesController by lazy {
        HomeCompanyGeneralNotesController(this, handler) {
            if (::binding.isInitialized && !isFinishing && !isDestroyed) renderCurrentRowsAfterCompanyLabels()
        }
    }
    private val filteredContactSummaryChipsUi by lazy { HomeCompanyScopeChipsUi(this, ::dp, ::roundedRect) }
    private val homeCallRowRenderer by lazy {
        HomeCallRowRenderer(
            activity = this, dp = ::dp, noteKey = HomeCallPageLoader::noteKey, roundedRect = ::roundedRect,
            openContactNotesScreen = homeActions::openContactNotesScreen,
            openContactNotePopupForCall = homeActions::openContactNotePopupForCall,
            openDialer = homeActions::openDialer, togglePhoneFilter = ::togglePhoneFilter,
        )
    }
    private val filteredFullLogController by lazy {
        FilteredFullLogController(
            activity = this, binding = binding, dp = ::dp, roundedRect = ::roundedRect,
            openContactNotes = homeActions::openContactNotesScreen,
            openCallNoteEditor = homeActions::openContactNotePopupForCall,
            pageSize = ::pageSize, onStateChanged = ::renderCalls,
        )
    }

    private var pageIndex = 0
    private var currentCalls: List<PhoneCallRecord> = emptyList()
    private var noteRefreshUntilMs = 0L
    private var activePhoneFilter = ""
    private var activeSearchQuery = ""

    private val noteRefreshRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > noteRefreshUntilMs) return
            renderCalls(); handler.postDelayed(this, NOTE_REFRESH_INTERVAL_MS)
        }
    }
    private val searchRunnable = Runnable {
        activeSearchQuery = binding.searchInput.text?.toString().orEmpty(); pageIndex = 0; renderCalls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        if (EnterpriseAccessGate.redirectIfNeeded(this)) return
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        activePhoneFilter = intent.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        updateSearchButtonIcon(); updateCrmModeBadge()
        binding.settingsButton.setOnClickListener { showHomeOverflowMenu() }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
        binding.filteredDialButton.setOnClickListener { homeActions.openDialer(activePhoneFilter) }
        binding.searchButton.setOnClickListener { toggleSearchRow() }
        binding.clearSearchButton.setOnClickListener { clearSearch() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { handler.removeCallbacks(searchRunnable); handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS) }
        })
        binding.previousCallsButton.setOnClickListener {
            if (isFilteredFullLogMode()) filteredFullLogController.previousPage()
            else if (pageIndex > 0) { pageIndex--; renderCalls() }
        }
        binding.nextCallsButton.setOnClickListener {
            if (isFilteredFullLogMode()) filteredFullLogController.nextPage()
            else if (currentCalls.size >= pageSize()) { pageIndex++; renderCalls() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent); setIntent(intent)
        val phone = intent?.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
        activePhoneFilter = if (isCrmModeEnabled() && phone.isNotBlank() && !HomeCallPageLoader.isCrmEligible(this, phone)) "" else phone
        activeSearchQuery = ""; pageIndex = 0
        filteredFullLogController.invalidate(); companyGeneralNotesController.invalidate()
        if (::binding.isInitialized) { binding.searchInput.setText(""); renderCalls() }
    }

    override fun onResume() {
        super.onResume()
        if (EnterpriseAccessGate.redirectIfNeeded(this)) return
        if (!::binding.isInitialized) return
        noteSavedReceiver.register(); contactsSyncPreparer.prepareOnce(); companyGeneralNotesController.invalidate(); renderCalls()
    }

    override fun onPause() {
        if (::binding.isInitialized) noteSavedReceiver.unregister()
        handler.removeCallbacks(noteRefreshRunnable); handler.removeCallbacks(searchRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        searchGeneration.incrementAndGet(); crmGeneration.incrementAndGet()
        searchExecutor.shutdownNow(); crmExecutor.shutdownNow()
        companyGeneralNotesController.release(); filteredFullLogController.release(); contactsSyncPreparer.release()
        super.onDestroy()
    }

    private fun renderCalls() {
        val generation = crmGeneration.incrementAndGet()
        val size = pageSize()
        val loadingCrmRows = isCrmModeEnabled() && activePhoneFilter.isBlank() && activeSearchQuery.isBlank()
        binding.previousCallsButton.text = getString(R.string.dynamic_home_previous_calls, size)
        binding.nextCallsButton.text = getString(R.string.dynamic_home_next_calls, size)
        if (!loadingCrmRows || currentCalls.isEmpty()) binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = View.GONE
        binding.clearFilterButton.visibility = if (activePhoneFilter.isBlank()) View.GONE else View.VISIBLE
        updateCrmModeBadge(); updatePhoneFilterStatusStyle(); renderFilteredContactSummary()
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.homeStatusText.text = getString(R.string.dynamic_home_missing_call_log_permission)
            binding.paginationContainer.visibility = View.GONE
            return
        }
        if (activeSearchQuery.isNotBlank()) { searchController.renderSearchCallsAsync(); return }
        if (activePhoneFilter.isNotBlank()) { filteredFullLogController.render(activePhoneFilter); return }
        if (isCrmModeEnabled()) { renderCrmCallsAsync(size, generation); return }
        val calls = HomeCallPageLoader.calls(this, activePhoneFilter, activeSearchQuery, pageIndex, size, crmMode = false)
        if (calls.isEmpty()) { renderEmptyState(); return }
        applyRenderData(HomeRenderData(calls, HomeCallPageLoader.contactNotes(this, calls), HomeCallPageLoader.contactNames(this, calls)), size)
    }

    private fun renderCrmCallsAsync(pageSize: Int, expectedGeneration: Int) {
        if (currentCalls.isEmpty()) {
            binding.homeStatusText.text = "Зареждане на CRM разговори…"
            binding.paginationContainer.visibility = View.GONE
        }
        val requestedPage = pageIndex
        val appContext = applicationContext
        crmExecutor.execute {
            val data = runCatching {
                val calls = HomeCallPageLoader.calls(appContext, "", "", requestedPage, pageSize, crmMode = true)
                HomeRenderData(calls, HomeCallPageLoader.contactNotes(appContext, calls), HomeCallPageLoader.contactNames(appContext, calls))
            }.getOrDefault(HomeRenderData(emptyList(), emptyMap(), emptyMap()))
            handler.post {
                val current = expectedGeneration == crmGeneration.get() && !isFinishing && !isDestroyed &&
                    isCrmModeEnabled() && activePhoneFilter.isBlank() && activeSearchQuery.isBlank() && pageIndex == requestedPage
                if (!current) return@post
                if (data.calls.isEmpty()) renderEmptyState() else applyRenderData(data, pageSize)
            }
        }
    }

    private fun applyRenderData(renderData: HomeRenderData, pageSize: Int) {
        applyRenderData(renderData, pageSize, refreshCompanyLabels = true)
    }

    private fun applyRenderData(
        renderData: HomeRenderData,
        pageSize: Int,
        refreshCompanyLabels: Boolean,
    ) {
        currentCalls = renderData.calls
        binding.homeCallsContainer.removeAllViews(); binding.fullLogProgress.visibility = View.GONE
        renderStatusAndPagination(pageSize)
        val phoneFiltered = activePhoneFilter.isNotBlank()
        val companyLabels = if (phoneFiltered) emptyMap() else companyGeneralNotesController.labelsFor(renderData.calls)
        renderData.calls.forEach { call ->
            val key = HomeCallPageLoader.noteKey(call.number)
            binding.homeCallsContainer.addView(homeCallRowRenderer.compactCallRow(
                call = call,
                displayName = renderData.contactNamesByNumber[key].orEmpty().ifBlank { call.displayName },
                contactNote = if (phoneFiltered) null else renderData.contactNotesByNumber[key],
                companyGeneralNoteLabels = if (phoneFiltered) null else companyLabels[key],
                callNote = ContactNoteReader.callNoteForPhone(this, call.number, call.startedAt, call.direction),
                highlightQuery = activeSearchQuery, showContactIdentity = !phoneFiltered,
                showGeneralContactNote = !phoneFiltered, showQuickActions = !phoneFiltered,
            ))
        }
        if (!phoneFiltered && refreshCompanyLabels) companyGeneralNotesController.refresh(renderData.calls)
    }

    private fun renderCurrentRowsAfterCompanyLabels() {
        if (currentCalls.isEmpty() || activePhoneFilter.isNotBlank()) return
        val calls = currentCalls
        applyRenderData(
            HomeRenderData(
                calls = calls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(this, calls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(this, calls),
            ),
            pageSize(),
            refreshCompanyLabels = false,
        )
    }

    private fun renderFilteredContactSummary() {
        val container = binding.filteredContactSummaryContainer
        container.removeAllViews()
        if (activePhoneFilter.isBlank()) { container.visibility = View.GONE; return }
        val phone = activePhoneFilter
        val name = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
            .takeIf { HomeCallPageLoader.noteKey(it) != HomeCallPageLoader.noteKey(phone) }.orEmpty()
        val summary = PhoneCallRecord(phone, "", "", 0L, 0L)
        val labels = companyGeneralNotesController.labelsFor(listOf(summary))[HomeCallPageLoader.noteKey(phone)]
        val crm = CallReportRemoteAccess.isReady(ConfigStore.load(applicationContext)) && CrmContactSyncStore.isEnabled(applicationContext, phone)
        val note = ContactNoteReader.generalNoteForPhone(this, phone).orEmpty()
        container.visibility = View.VISIBLE
        container.addView(summaryText(name.ifBlank { phone }, 18f, true, dp(2)))
        if (name.isNotBlank()) container.addView(summaryText(phone, 14f, false, dp(2)))
        if (crm || !labels.isNullOrEmpty()) container.addView(filteredContactSummaryChipsUi.create(labels, crm))
        if (note.isNotBlank()) {
            val colors = NoteUiStyle.General
            container.addView(TextView(this).apply {
                text = note; setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0)
                compoundDrawablePadding = dp(5); setTextColor(colors.text); textSize = 13f; maxLines = 4
                setPadding(dp(10), dp(7), dp(10), dp(7)); background = roundedRect(colors.background, dp(10), colors.border, dp(1))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
        }
        companyGeneralNotesController.refresh(listOf(summary))
    }

    private fun summaryText(value: String, size: Float, bold: Boolean, bottom: Int): TextView = TextView(this).apply {
        text = value; setTextColor(if (size >= 18f) getColor(R.color.calllog_text) else getColor(R.color.calllog_muted_text)); textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), 0, dp(4), bottom)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun renderEmptyState() {
        binding.fullLogProgress.visibility = View.GONE
        binding.homeStatusText.text = when {
            activeSearchQuery.isNotBlank() -> getString(R.string.dynamic_home_no_search_results, activeSearchQuery.trim())
            activePhoneFilter.isNotBlank() && pageIndex == 0 -> getString(R.string.dynamic_home_filter_no_calls_or_sms, activePhoneFilter)
            pageIndex == 0 -> getString(R.string.dynamic_home_no_calls)
            else -> getString(R.string.dynamic_home_no_more_calls)
        }
        updatePhoneFilterStatusStyle(); binding.previousCallsButton.isEnabled = pageIndex > 0; binding.nextCallsButton.isEnabled = false
        binding.pageText.text = getString(R.string.dynamic_home_page, pageIndex + 1); binding.paginationContainer.visibility = View.VISIBLE
    }

    private fun renderStatusAndPagination(pageSize: Int) {
        val start = pageIndex * pageSize + 1
        val end = pageIndex * pageSize + currentCalls.size
        binding.homeStatusText.text = when {
            activeSearchQuery.isNotBlank() && activePhoneFilter.isNotBlank() -> getString(R.string.dynamic_home_status_filter_search, activePhoneFilter, activeSearchQuery.trim(), start, end)
            activeSearchQuery.isNotBlank() -> getString(R.string.dynamic_home_status_search, activeSearchQuery.trim(), start, end)
            activePhoneFilter.isNotBlank() -> getString(R.string.dynamic_home_status_filter, activePhoneFilter, start, end)
            else -> getString(R.string.dynamic_home_status_calls, start, end)
        }
        updatePhoneFilterStatusStyle(); binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= pageSize
        binding.pageText.text = getString(R.string.dynamic_home_page, pageIndex + 1); binding.paginationContainer.visibility = View.VISIBLE
    }

    private fun updatePhoneFilterStatusStyle() {
        val filtered = activePhoneFilter.isNotBlank()
        binding.filteredDialButton.visibility = if (filtered) View.VISIBLE else View.GONE
        if (filtered) {
            binding.filteredStatusContainer.background = roundedRect(Color.rgb(255, 237, 213), dp(12), Color.rgb(251, 146, 60), dp(1))
            binding.filteredStatusContainer.setPadding(dp(10), dp(2), dp(4), dp(2))
            binding.homeStatusText.background = null; binding.homeStatusText.setTextColor(Color.rgb(154, 52, 18)); binding.homeStatusText.setPadding(0, dp(4), 0, dp(4))
        } else {
            binding.filteredStatusContainer.background = null; binding.filteredStatusContainer.setPadding(0, 0, 0, 0)
            binding.homeStatusText.background = null; binding.homeStatusText.setTextColor(Color.rgb(71, 85, 105)); binding.homeStatusText.setPadding(0, 0, 0, 0)
        }
    }

    private fun updateCrmModeBadge() {
        val visible = isCrmModeEnabled()
        binding.crmModeBadge.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) binding.crmModeBadge.background = roundedRect(getColor(R.color.callreport_icon_background), dp(9), Color.TRANSPARENT, 0)
    }

    private fun isCrmModeEnabled(): Boolean = HomeCrmModeStore.isEnabled(this)

    private fun setCrmMode(enabled: Boolean) {
        if (!HomeCrmModeStore.setEnabled(this, enabled)) return
        currentCalls = emptyList()
        activePhoneFilter = ""; pageIndex = 0; filteredFullLogController.invalidate(); companyGeneralNotesController.invalidate(); renderCalls()
    }

    private fun togglePhoneFilter(number: String) {
        if (isCrmModeEnabled() && !HomeCallPageLoader.isCrmEligible(this, number)) return
        val key = HomeCallPageLoader.noteKey(number)
        activePhoneFilter = if (activePhoneFilter.isNotBlank() && HomeCallPageLoader.noteKey(activePhoneFilter) == key) "" else number
        pageIndex = 0; filteredFullLogController.invalidate(); renderCalls()
    }

    private fun clearPhoneFilter() {
        if (activePhoneFilter.isBlank()) return
        activePhoneFilter = ""; pageIndex = 0; filteredFullLogController.invalidate(); renderCalls()
    }

    private fun openDefaultCallLog() {
        startActivity(Intent(this, SystemCallHistoryActivity::class.java).putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL))
    }

    private fun showHomeOverflowMenu() {
        PopupMenu(this, binding.settingsButton).apply {
            if (HomeCrmModeStore.isAvailable(this@HomeActivity)) {
                menu.setGroupCheckable(MENU_GROUP_CRM_MODE, true, false)
                menu.add(MENU_GROUP_CRM_MODE, MENU_CRM_MODE_TOGGLE, 0, "CRM Mode").apply {
                    isCheckable = true
                    isChecked = isCrmModeEnabled()
                }
            }
            menu.add(0, MENU_PHONE_CALL_LOG, 10, getString(R.string.home_overflow_phone_log))
            menu.add(0, MENU_SETTINGS, 20, getString(R.string.home_overflow_settings))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_CRM_MODE_TOGGLE -> { setCrmMode(!isCrmModeEnabled()); true }
                    MENU_PHONE_CALL_LOG -> { openDefaultCallLog(); true }
                    MENU_SETTINGS -> { homeActions.openSettings(); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun toggleSearchRow() {
        val show = binding.searchRow.visibility != View.VISIBLE
        if (show) {
            binding.searchRow.visibility = View.VISIBLE; updateSearchButtonIcon(); binding.searchInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else { clearSearch(); binding.searchRow.visibility = View.GONE; updateSearchButtonIcon() }
    }

    private fun clearSearch() {
        handler.removeCallbacks(searchRunnable); binding.searchInput.setText(""); activeSearchQuery = ""; pageIndex = 0; renderCalls(); updateSearchButtonIcon()
    }

    private fun updateSearchButtonIcon() {
        binding.searchButton.setImageResource(if (binding.searchRow.visibility == View.VISIBLE) R.drawable.ic_popup_close else R.drawable.ic_search)
    }

    private fun isFilteredFullLogMode(): Boolean = activePhoneFilter.isNotBlank() && activeSearchQuery.isBlank()
    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    private fun startTemporaryNoteRefresh() {
        filteredFullLogController.invalidate(); companyGeneralNotesController.invalidate()
        noteRefreshUntilMs = System.currentTimeMillis() + NOTE_REFRESH_WINDOW_MS
        handler.removeCallbacks(noteRefreshRunnable); handler.postDelayed(noteRefreshRunnable, NOTE_REFRESH_INTERVAL_MS)
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius.toFloat(); setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_PHONE_FILTER = "phone_filter"
        private const val MENU_GROUP_CRM_MODE = 100
        private const val MENU_CRM_MODE_TOGGLE = 101
        private const val MENU_PHONE_CALL_LOG = 1
        private const val MENU_SETTINGS = 2
        private const val NOTE_REFRESH_WINDOW_MS = 2_000L
        private const val NOTE_REFRESH_INTERVAL_MS = 400L
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}
