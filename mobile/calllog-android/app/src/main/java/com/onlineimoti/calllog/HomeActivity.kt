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
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsButton.setOnClickListener { homeActions.openSettings() }
        binding.clearFilterButton.setOnClickListener { clearPhoneFilter() }
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
        binding.clearFilterButton.visibility = if (activePhoneFilter.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.homeStatusText.text = "Липсва достъп до телефонния log. Отвори ⚙ Настройки и разреши Call log."
            binding.paginationContainer.visibility = android.view.View.GONE
            return
        }

        currentCalls = HomeCallPageLoader.calls(this, activePhoneFilter, pageIndex, pageSize)
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
            activePhoneFilter.isNotBlank() && pageIndex == 0 -> "${activePhoneFilter} • няма разговори"
            pageIndex == 0 -> "Няма намерени разговори."
            else -> "Няма повече разговори."
        }
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = false
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = android.view.View.VISIBLE
    }

    private fun renderStatusAndPagination(pageSize: Int) {
        val startNumber = pageIndex * pageSize + 1
        val endNumber = pageIndex * pageSize + currentCalls.size
        binding.homeStatusText.text = if (activePhoneFilter.isBlank()) {
            "Разговори $startNumber–$endNumber"
        } else {
            "${activePhoneFilter} • $startNumber–$endNumber"
        }
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= pageSize
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = android.view.View.VISIBLE
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