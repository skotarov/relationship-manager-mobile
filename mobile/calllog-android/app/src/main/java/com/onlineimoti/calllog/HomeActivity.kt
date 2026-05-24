package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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

        binding.settingsButton.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
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

        currentCalls = if (activePhoneFilter.isBlank()) {
            PhoneCallReader.recentCalls(this, limit = PAGE_SIZE, offset = pageIndex * PAGE_SIZE)
        } else {
            PhoneCallReader.callsForPhone(this, activePhoneFilter, limit = PAGE_SIZE, offset = pageIndex * PAGE_SIZE)
        }

        if (currentCalls.isEmpty()) {
            binding.homeStatusText.text = when {
                activePhoneFilter.isNotBlank() && pageIndex == 0 -> "Няма разговори за ${activePhoneFilter}."
                pageIndex == 0 -> "Няма намерени разговори."
                else -> "Няма повече разговори."
            }
            binding.previousCallsButton.isEnabled = pageIndex > 0
            binding.nextCallsButton.isEnabled = false
            binding.pageText.text = "Стр. ${pageIndex + 1}"
            binding.paginationContainer.visibility = android.view.View.VISIBLE
            return
        }

        val contactNotesByNumber = loadContactNotesForCurrentPage()
        val contactNamesByNumber = loadContactNamesForCurrentPage()
        val startNumber = pageIndex * PAGE_SIZE + 1
        val endNumber = pageIndex * PAGE_SIZE + currentCalls.size
        binding.homeStatusText.text = if (activePhoneFilter.isBlank()) {
            "Разговори $startNumber–$endNumber"
        } else {
            "Филтър: ${activePhoneFilter} • $startNumber–$endNumber"
        }
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= PAGE_SIZE
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = android.view.View.VISIBLE

        currentCalls.forEach { call ->
            val displayName = contactNamesByNumber[noteKey(call.number)].orEmpty().ifBlank { call.displayName }
            val callNote = ContactNoteReader.callNoteForPhone(call.number, call.startedAt, call.direction)
            binding.homeCallsContainer.addView(
                compactCallRow(
                    call = call,
                    displayName = displayName,
                    contactNote = contactNotesByNumber[noteKey(call.number)],
                    callNote = callNote,
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

    private fun compactCallRow(call: PhoneCallRecord, displayName: String, contactNote: String? = null, callNote: String? = null): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.calllog_border))
            setCardBackgroundColor(getColor(R.color.calllog_surface))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { openContactNotesScreen(call, displayName) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        row.addView(TextView(this).apply {
            text = callIcon(call)
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = listOf(call.number, PhoneCallReader.formatStartedAt(call.startedAt), PhoneCallReader.formatDuration(call.durationSeconds)).filter { it.isNotBlank() }.joinToString(" • ")
            setTextColor(getColor(R.color.calllog_muted_text))
            textSize = 12.5f
            maxLines = 1
            isClickable = true
            isFocusable = true
            setOnClickListener { openDialer(call.number) }
        })
        textColumn.addView(TextView(this).apply {
            text = displayName
            setTextColor(getColor(R.color.calllog_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { openDialer(call.number) }
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
        if (!callNote.isNullOrBlank()) {
            textColumn.addView(TextView(this).apply {
                text = "💬 $callNote"
                setTextColor(Color.rgb(7, 89, 133))
                textSize = 12.5f
                maxLines = 3
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = roundedRect(Color.rgb(224, 246, 255), dp(9), Color.rgb(125, 211, 252), dp(1))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
        }
        row.addView(textColumn)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(3) }
        }
        actions.addView(iconButton(R.drawable.ic_filter_calls, "Филтър") { togglePhoneFilter(call.number) })
        actions.addView(iconButton(R.drawable.ic_note_lines, "Бележка") { openContactNotePopupForCall(call, displayName) })
        row.addView(actions)

        card.addView(row)
        return card
    }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
            setOnClickListener { action() }
        }
    }

    private fun togglePhoneFilter(number: String) {
        val key = noteKey(number)
        activePhoneFilter = if (activePhoneFilter.isNotBlank() && noteKey(activePhoneFilter) == key) "" else number
        pageIndex = 0
        renderCalls()
    }

    private fun openDialer(number: String) {
        if (number.isBlank()) return
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
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

    private fun openContactNotesScreen(call: PhoneCallRecord, displayName: String) {
        startActivity(Intent(this, ContactNotesActivity::class.java).putExtra(ContactNotesActivity.EXTRA_PHONE, call.number).putExtra(ContactNotesActivity.EXTRA_TITLE, displayName.ifBlank { call.number }))
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

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_CONTACT_NOTE_SAVED = "com.onlineimoti.calllog.CONTACT_NOTE_SAVED"
        const val EXTRA_NOTE_PHONE = "phone"
        private const val PAGE_SIZE = 20
        private const val NOTE_REFRESH_INTERVAL_MS = 1000L
        private const val NOTE_REFRESH_WINDOW_MS = 120_000L
    }
}
