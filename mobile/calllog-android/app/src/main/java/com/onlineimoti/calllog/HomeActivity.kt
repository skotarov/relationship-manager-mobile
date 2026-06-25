package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
    private val searchUiController by lazy { HomeSearchUiController(this, binding) }
    private val homeStatusRenderer by lazy { HomeStatusRenderer(this, binding, ::dp) }
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
            togglePhoneFilter = ::togglePhoneFilter,
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
    private var noteRefreshUntilMs = 0L
    private var activePhoneFilter: String = ""
    private var activeSearchQuery: String = ""

    private val noteRefreshRunnable = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() > noteRefreshUntilMs) return
            // The full log was invalidated once when the note changed. Do not repeatedly
            // trigger background history reloads while the popup is closing.
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
        searchUiController.updateButtonIcon()

        binding.settingsButton.setOnClickListener { showHomeOverflowMenu() }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
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
            if (isFilteredFullLogMode()) {
                filteredFullLogController.previousPage()
            } else if (pageIndex > 0) {
                pageIndex -= 1
                renderCalls()
            }
        }
        binding.nextCallsButton.setOnClickListener {
            if (isFilteredFullLogMode()) {
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
        handler.removeCallbacks(noteRefreshRunnable)
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
        val container = binding.filteredContactSummaryContainer
        container.removeAllViews()
        if (activePhoneFilter.isBlank()) {
            container.visibility = View.GONE
            return
        }
        val name = ContactGroupFilter.resolveDisplayName(this, activePhoneFilter)
            .orEmpty()
            .takeIf { HomeCallPageLoader.noteKey(it) != HomeCallPageLoader.noteKey(activePhoneFilter) }
            .orEmpty()
        val note = ContactNoteReader.generalNoteForPhone(this, activePhoneFilter).orEmpty()
        if (name.isBlank() && note.isBlank()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        if (name.isNotBlank()) {
            container.addView(TextView(this).apply {
                text = name
                setTextColor(getColor(R.color.calllog_text))
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(2), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) }
            })
        }
        if (note.isNotBlank()) {
            val colors = NoteUiStyle.General
            container.addView(TextView(this).apply {
                text = note
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0)
                compoundDrawablePadding = dp(5)
                setTextColor(colors.text)
                textSize = 13f
                maxLines = 4
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = homeRoundedRect(colors.background, dp(10), colors.border, dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
        }
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

    private fun togglePhoneFilter(number: String) {
        val key = HomeCallPageLoader.noteKey(number)
        activePhoneFilter = if (activePhoneFilter.isNotBlank() && HomeCallPageLoader.noteKey(activePhoneFilter) == key) "" else number
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

    private fun openDefaultCallLog() {
        startActivity(
            Intent(this, SystemCallHistoryActivity::class.java)
                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
        )
    }

    private fun showHomeOverflowMenu() {
        PopupMenu(this, binding.settingsButton).apply {
            menu.add(0, MENU_PHONE_CALL_LOG, 0, getString(R.string.home_overflow_phone_log))
            menu.add(0, MENU_SETTINGS, 1, getString(R.string.home_overflow_settings))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_PHONE_CALL_LOG -> {
                        openDefaultCallLog()
                        true
                    }
                    MENU_SETTINGS -> {
                        homeActions.openSettings()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun clearSearch() {
        handler.removeCallbacks(searchRunnable)
        binding.searchInput.setText("")
        activeSearchQuery = ""
        pageIndex = 0
        renderCalls()
        searchUiController.updateButtonIcon()
    }

    private fun isFilteredFullLogMode(): Boolean {
        return activePhoneFilter.isNotBlank() && activeSearchQuery.isBlank()
    }

    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    private fun startTemporaryNoteRefresh() {
        filteredFullLogController.invalidate()
        noteRefreshUntilMs = System.currentTimeMillis() + NOTE_REFRESH_WINDOW_MS
        handler.removeCallbacks(noteRefreshRunnable)
        handler.postDelayed(noteRefreshRunnable, NOTE_REFRESH_INTERVAL_MS)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_PHONE_FILTER = "phone_filter"
        private const val MENU_PHONE_CALL_LOG = 1
        private const val MENU_SETTINGS = 2
        private const val NOTE_REFRESH_WINDOW_MS = 2_000L
        private const val NOTE_REFRESH_INTERVAL_MS = 400L
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}
