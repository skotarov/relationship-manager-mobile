package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
    private val homeActions by lazy { HomeActions(this, binding, ::startTemporaryNoteRefresh) }
    private val homeCallRowRenderer by lazy {
        HomeCallRowRenderer(
            activity = this,
            dp = ::dp,
            noteKey = HomeCallPageLoader::noteKey,
            roundedRect = ::roundedRect,
            openContactNotesScreen = homeActions::openContactNotesScreen,
            openDialer = homeActions::openDialer,
            togglePhoneFilter = ::togglePhoneFilter,
            openContactNotePopupForCall = homeActions::openContactNotePopupForCall,
        )
    }
    private var pageIndex = 0
    private var currentCalls: List<PhoneCallRecord> = emptyList()
    private var noteSavedReceiverRegistered = false
    private var noteRefreshUntilMs = 0L
    private var activePhoneFilter: String = ""
    private var activeSearchQuery: String = ""

    private data class RenderData(
        val calls: List<PhoneCallRecord>,
        val contactNotesByNumber: Map<String, String>,
        val contactNamesByNumber: Map<String, String>,
    )

    private val noteSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            HomeCallPageLoader.clearSearchCache()
            renderCalls()
        }
    }

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

        binding.settingsButton.setOnClickListener { homeActions.openSettings() }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
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
            if (pageIndex > 0) {
                pageIndex -= 1
                renderCalls()
            }
        }
        binding.nextCallsButton.setOnClickListener {
            if (currentCalls.size >= pageSize()) {
                pageIndex += 1
                renderCalls()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerNoteSavedReceiver()
        renderCalls()
    }

    override fun onPause() {
        unregisterNoteSavedReceiver()
        handler.removeCallbacks(noteRefreshRunnable)
        handler.removeCallbacks(searchRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        searchGeneration.incrementAndGet()
        searchExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun registerNoteSavedReceiver() {
        if (noteSavedReceiverRegistered) return
        val filter = IntentFilter(ACTION_CONTACT_NOTE_SAVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noteSavedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noteSavedReceiver, filter)
        }
        noteSavedReceiverRegistered = true
    }

    private fun unregisterNoteSavedReceiver() {
        if (!noteSavedReceiverRegistered) return
        runCatching { unregisterReceiver(noteSavedReceiver) }
        noteSavedReceiverRegistered = false
    }

    private fun renderCalls() {
        val pageSize = pageSize()
        binding.previousCallsButton.text = "Предишни $pageSize"
        binding.nextCallsButton.text = "Следващи $pageSize"
        binding.homeCallsContainer.removeAllViews()
        binding.clearFilterButton.visibility = if (activePhoneFilter.isBlank()) View.GONE else View.VISIBLE
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.homeStatusText.text = "Липсва достъп до телефонния log. Отвори ⚙ Настройки и разреши Call log."
            binding.paginationContainer.visibility = View.GONE
            return
        }

        if (activeSearchQuery.isNotBlank()) {
            renderSearchCallsAsync(pageSize)
            return
        }

        currentCalls = HomeCallPageLoader.calls(this, activePhoneFilter, activeSearchQuery, pageIndex, pageSize)
        if (currentCalls.isEmpty()) {
            renderEmptyState()
            return
        }

        val renderData = RenderData(
            calls = currentCalls,
            contactNotesByNumber = HomeCallPageLoader.contactNotes(this, currentCalls),
            contactNamesByNumber = HomeCallPageLoader.contactNames(this, currentCalls),
        )
        applyRenderData(renderData, pageSize)
    }

    private fun renderSearchCallsAsync(pageSize: Int) {
        val query = activeSearchQuery
        if (HomeCallPageLoader.isSearchTooShort(query)) {
            currentCalls = emptyList()
            binding.homeStatusText.text = "Въведи поне 2 символа за търсене или поне 3 цифри."
            binding.previousCallsButton.isEnabled = false
            binding.nextCallsButton.isEnabled = false
            binding.pageText.text = "Стр. ${pageIndex + 1}"
            binding.paginationContainer.visibility = View.VISIBLE
            return
        }

        val generation = searchGeneration.incrementAndGet()
        val phoneFilter = activePhoneFilter
        val page = pageIndex
        binding.homeStatusText.text = "Търси „${query.trim()}“…"
        binding.previousCallsButton.isEnabled = false
        binding.nextCallsButton.isEnabled = false
        binding.paginationContainer.visibility = View.VISIBLE

        searchExecutor.execute {
            val calls = HomeCallPageLoader.calls(this, phoneFilter, query, page, pageSize)
            val renderData = RenderData(
                calls = calls,
                contactNotesByNumber = HomeCallPageLoader.contactNotes(this, calls),
                contactNamesByNumber = HomeCallPageLoader.contactNames(this, calls),
            )
            handler.post {
                if (generation != searchGeneration.get()) return@post
                if (query != activeSearchQuery || phoneFilter != activePhoneFilter || page != pageIndex) return@post
                currentCalls = renderData.calls
                if (renderData.calls.isEmpty()) {
                    binding.homeCallsContainer.removeAllViews()
                    renderEmptyState()
                } else {
                    applyRenderData(renderData, pageSize)
                }
            }
        }
    }

    private fun applyRenderData(renderData: RenderData, pageSize: Int) {
        currentCalls = renderData.calls
        binding.homeCallsContainer.removeAllViews()
        renderStatusAndPagination(pageSize)
        renderData.calls.forEach { call ->
            val key = HomeCallPageLoader.noteKey(call.number)
            val displayName = renderData.contactNamesByNumber[key].orEmpty().ifBlank { call.displayName }
            binding.homeCallsContainer.addView(
                homeCallRowRenderer.compactCallRow(
                    call = call,
                    displayName = displayName,
                    contactNote = renderData.contactNotesByNumber[key],
                    callNote = ContactNoteReader.callNoteForPhone(this, call.number, call.startedAt, call.direction),
                )
            )
        }
    }

    private fun renderEmptyState() {
        binding.homeStatusText.text = when {
            activeSearchQuery.isNotBlank() -> "Няма резултати за „${activeSearchQuery.trim()}“."
            activePhoneFilter.isNotBlank() && pageIndex == 0 -> "${activePhoneFilter} • няма разговори"
            pageIndex == 0 -> "Няма намерени разговори."
            else -> "Няма повече разговори."
        }
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = false
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = View.VISIBLE
    }

    private fun renderStatusAndPagination(pageSize: Int) {
        val startNumber = pageIndex * pageSize + 1
        val endNumber = pageIndex * pageSize + currentCalls.size
        binding.homeStatusText.text = when {
            activeSearchQuery.isNotBlank() && activePhoneFilter.isNotBlank() -> "Търсене „${activeSearchQuery.trim()}“ • ${activePhoneFilter} • $startNumber–$endNumber"
            activeSearchQuery.isNotBlank() -> "Търсене „${activeSearchQuery.trim()}“ • $startNumber–$endNumber"
            activePhoneFilter.isNotBlank() -> "${activePhoneFilter} • $startNumber–$endNumber"
            else -> "Разговори $startNumber–$endNumber"
        }
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= pageSize
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = View.VISIBLE
    }

    private fun togglePhoneFilter(number: String) {
        val key = HomeCallPageLoader.noteKey(number)
        activePhoneFilter = if (activePhoneFilter.isNotBlank() && HomeCallPageLoader.noteKey(activePhoneFilter) == key) "" else number
        pageIndex = 0
        renderCalls()
    }

    private fun clearPhoneFilter() {
        if (activePhoneFilter.isBlank()) return
        activePhoneFilter = ""
        pageIndex = 0
        renderCalls()
    }

    private fun toggleSearchRow() {
        binding.searchRow.visibility = if (binding.searchRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (binding.searchRow.visibility == View.VISIBLE) {
            binding.searchInput.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            clearSearch()
        }
    }

    private fun clearSearch() {
        handler.removeCallbacks(searchRunnable)
        searchGeneration.incrementAndGet()
        if (binding.searchInput.text?.isNotEmpty() == true) binding.searchInput.setText("")
        activeSearchQuery = ""
        pageIndex = 0
        renderCalls()
    }

    private fun startTemporaryNoteRefresh() {
        noteRefreshUntilMs = System.currentTimeMillis() + NOTE_REFRESH_WINDOW_MS
        handler.removeCallbacks(noteRefreshRunnable)
        handler.postDelayed(noteRefreshRunnable, NOTE_REFRESH_INTERVAL_MS)
    }

    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_NOTE_PHONE = "phone"
        private const val NOTE_REFRESH_INTERVAL_MS = 1000L
        private const val NOTE_REFRESH_WINDOW_MS = 120_000L
        private const val SEARCH_DEBOUNCE_MS = 450L
    }
}