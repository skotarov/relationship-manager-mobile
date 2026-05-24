package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val handler = Handler(Looper.getMainLooper())
    private var pageIndex = 0
    private var currentCalls: List<PhoneCallRecord> = emptyList()
    private var noteSavedReceiverRegistered = false
    private var noteRefreshUntilMs = 0L

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

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.previousCallsButton.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex -= 1
                renderCalls()
            }
        }
        binding.nextCallsButton.setOnClickListener {
            if (currentCalls.size >= PAGE_SIZE) {
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
        binding.homeCallsContainer.removeAllViews()
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.homeStatusText.text = "Липсва достъп до телефонния log. Отвори ⚙ Настройки и разреши Call log."
            binding.paginationContainer.visibility = android.view.View.GONE
            return
        }

        currentCalls = PhoneCallReader.recentCalls(
            context = this,
            limit = PAGE_SIZE,
            offset = pageIndex * PAGE_SIZE,
        )
        if (currentCalls.isEmpty()) {
            binding.homeStatusText.text = if (pageIndex == 0) "Няма намерени разговори." else "Няма повече разговори."
            binding.previousCallsButton.isEnabled = pageIndex > 0
            binding.nextCallsButton.isEnabled = false
            binding.pageText.text = "Стр. ${pageIndex + 1}"
            binding.paginationContainer.visibility = android.view.View.VISIBLE
            return
        }

        val contactNotesByNumber = loadContactNotesForCurrentPage()
        val contactNamesByNumber = loadContactNamesForCurrentPage()

        binding.homeStatusText.text = "Разговори ${pageIndex * PAGE_SIZE + 1}–${pageIndex * PAGE_SIZE + currentCalls.size}"
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= PAGE_SIZE
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = android.view.View.VISIBLE
        currentCalls.forEach { call ->
            val displayName = contactNamesByNumber[noteKey(call.number)].orEmpty().ifBlank { call.displayName }
            binding.homeCallsContainer.addView(
                compactCallRow(
                    call = call,
                    displayName = displayName,
                    contactNote = contactNotesByNumber[noteKey(call.number)],
                )
            )
        }
    }

    private fun loadContactNotesForCurrentPage(): Map<String, String> {
        val notes = linkedMapOf<String, String>()
        currentCalls.map { it.number }.distinctBy { noteKey(it) }.forEach { number ->
            ContactNoteReader.generalNoteForPhone(this, number).takeIf { it.isNotBlank() }?.let { note ->
                notes[noteKey(number)] = note
            }
        }
        return notes
    }

    private fun loadContactNamesForCurrentPage(): Map<String, String> {
        val names = linkedMapOf<String, String>()
        currentCalls.map { it.number }.distinctBy { noteKey(it) }.forEach { number ->
            ContactGroupFilter.resolveDisplayName(this, number).orEmpty().takeIf { it.isNotBlank() }?.let { name ->
                names[noteKey(number)] = name
            }
        }
        return names
    }

    private fun compactCallRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String? = null,
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.calllog_border))
            setCardBackgroundColor(getColor(R.color.calllog_surface))
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        row.addView(TextView(this).apply {
            text = callIcon(call)
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = listOf(
                call.number,
                PhoneCallReader.formatStartedAt(call.startedAt),
                PhoneCallReader.formatDuration(call.durationSeconds),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            setTextColor(getColor(R.color.calllog_muted_text))
            textSize = 12.5f
            maxLines = 1
        })
        textColumn.addView(TextView(this).apply {
            text = displayName
            setTextColor(getColor(R.color.calllog_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        })
        if (!contactNote.isNullOrBlank()) {
            textColumn.addView(TextView(this).apply {
                text = "📝 $contactNote"
                setTextColor(getColor(R.color.calllog_muted_text))
                textSize = 12.5f
                maxLines = 2
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(textColumn)

        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_note_lines)
            contentDescription = "Бележка"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = dp(8)
            }
            setOnClickListener { openContactNotePopupForCall(call, displayName) }
        })

        card.addView(row)
        return card
    }

    private fun callIcon(call: PhoneCallRecord): String {
        val completed = call.durationSeconds > 0
        return when {
            completed && call.direction == "in" -> "🟢↙"
            completed && call.direction == "out" -> "🟢↗"
            !completed && call.direction == "in" -> "🔴↙"
            !completed && call.direction == "out" -> "🔴↗"
            else -> "⚪"
        }
    }

    private fun openContactNotePopupForCall(call: PhoneCallRecord, displayName: String) {
        if (!Settings.canDrawOverlays(this)) {
            binding.homeStatusText.text = "За popup бележка разреши 'Показване върху други приложения' от Настройки."
            return
        }

        startService(
            Intent(this, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, call.number)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, call.direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, displayName)
                .putExtra(PostCallOverlayService.EXTRA_CALL_AT, call.startedAt)
                .putExtra(PostCallOverlayService.EXTRA_DURATION, call.durationSeconds)
        )
        startTemporaryNoteRefresh()
    }

    private fun startTemporaryNoteRefresh() {
        noteRefreshUntilMs = System.currentTimeMillis() + NOTE_REFRESH_WINDOW_MS
        handler.removeCallbacks(noteRefreshRunnable)
        handler.postDelayed(noteRefreshRunnable, NOTE_REFRESH_INTERVAL_MS)
    }

    private fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_NOTE_PHONE = "phone"
        private const val PAGE_SIZE = 20
        private const val NOTE_REFRESH_INTERVAL_MS = 1000L
        private const val NOTE_REFRESH_WINDOW_MS = 120_000L
    }
}
