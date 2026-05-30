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

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
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

    private val noteSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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
                activeSearchQuery = s?.toString().orEmpty()
                pageIndex = 0
                renderCalls()
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
        super.onPause()
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

        currentCalls = HomeCallPageLoader.calls(this, activePhoneFilter, activeSearchQuery, pageIndex, pageSize)
        if (currentCalls.isEmpty()) {
            renderEmptyState()
            return
        }

        renderStatusAndPagination(pageSize)
        val contactNotesByNumber = HomeCallPageLoader.contactNotes(this, currentCalls)
        val contactNamesByNumber = HomeCallPageLoader.contactNames(this, currentCalls)
        currentCalls.forEach { call ->
            val displayName = contactNamesByNumber[HomeCallPageLoader.noteKey(call.number)].orEmpty().ifBlank { call.displayName }
            binding.homeCallsContainer.addView(
                homeCallRowRenderer.compactCallRow(
                    call = call,
                    displayName = displayName,
                    contactNote = contactNotesByNumber[HomeCallPageLoader.noteKey(call.number)],
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
    }
}