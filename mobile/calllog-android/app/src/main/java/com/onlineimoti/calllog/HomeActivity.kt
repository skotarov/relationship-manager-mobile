package com.onlineimoti.calllog

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var pageIndex = 0
    private var currentCalls: List<PhoneCallRecord> = emptyList()
    private var serverNotesByNumber: Map<String, String> = emptyMap()

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
        renderCalls()
    }

    private fun renderCalls() {
        binding.homeCallsContainer.removeAllViews()
        serverNotesByNumber = emptyMap()
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.homeStatusText.text = "Липсва достъп до телефонния log. Отвори Настройки и разреши Call log."
            binding.paginationContainer.visibility = View.GONE
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
            binding.paginationContainer.visibility = View.VISIBLE
            return
        }

        binding.homeStatusText.text = statusTextForPage(currentCalls)
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= PAGE_SIZE
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = View.VISIBLE
        renderCurrentPageWithNotes()
        loadServerNotesForCurrentPage()
    }

    private fun renderCurrentPageWithNotes() {
        binding.homeCallsContainer.removeAllViews()
        currentCalls
            .groupBy { dayKey(it.startedAt) }
            .values
            .forEach { dayCalls ->
                if (dayCalls.isNotEmpty()) {
                    binding.homeCallsContainer.addView(dayHeader(dayCalls.first().startedAt))
                    binding.homeCallsContainer.addView(dayGroupCard(dayCalls))
                }
            }
    }

    private fun loadServerNotesForCurrentPage() {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return
        }

        val pageSnapshot = pageIndex
        val callsSnapshot = currentCalls
        val visibleNumbers = callsSnapshot.map { it.number }.distinctBy { noteKey(it) }
        if (visibleNumbers.isEmpty()) {
            return
        }

        binding.homeStatusText.text = "${statusTextForPage(callsSnapshot)} • зареждам бележки..."
        executor.execute {
            val notes = linkedMapOf<String, String>()
            visibleNumbers.forEach { number ->
                runCatching {
                    CallReportRuntime.fetchLookup(config, number, "").lines
                        .firstOrNull { line -> isUsefulServerNote(line) }
                        .orEmpty()
                }.getOrDefault("").takeIf { it.isNotBlank() }?.let { note ->
                    notes[noteKey(number)] = note
                }
            }
            runOnUiThread {
                if (pageIndex != pageSnapshot || currentCalls.map { noteKey(it.number) } != callsSnapshot.map { noteKey(it.number) }) {
                    return@runOnUiThread
                }
                serverNotesByNumber = notes
                binding.homeStatusText.text = statusTextForPage(currentCalls)
                renderCurrentPageWithNotes()
            }
        }
    }

    private fun statusTextForPage(calls: List<PhoneCallRecord>): String {
        return "Последни разговори ${pageIndex * PAGE_SIZE + 1}-${pageIndex * PAGE_SIZE + calls.size}"
    }

    private fun dayHeader(startedAt: Long): TextView {
        return TextView(this).apply {
            text = dayTitle(startedAt)
            setTextColor(getColor(R.color.calllog_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(18), dp(6), dp(8))
        }
    }

    private fun dayGroupCard(dayCalls: List<PhoneCallRecord>): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(getColor(R.color.calllog_surface))
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        dayCalls.forEachIndexed { index, call ->
            content.addView(callRow(call, serverNotesByNumber[noteKey(call.number)]))
            if (index < dayCalls.lastIndex) {
                content.addView(divider())
            }
        }

        card.addView(content)
        return card
    }

    private fun callRow(call: PhoneCallRecord, serverNote: String? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(13), dp(8), dp(13))
            minimumHeight = dp(if (serverNote.isNullOrBlank()) 82 else 104)
        }

        row.addView(TextView(this).apply {
            text = callIcon(call)
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(callIconColor(call))
            layoutParams = LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT)
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = callMainLine(call)
            setTextColor(getColor(R.color.calllog_text))
            textSize = 20f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textColumn.addView(TextView(this).apply {
            text = callSubLine(call)
            setTextColor(getColor(R.color.calllog_muted_text))
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        if (!serverNote.isNullOrBlank()) {
            textColumn.addView(TextView(this).apply {
                text = "Бележка: ${serverNote.trim()}"
                setTextColor(getColor(R.color.calllog_text))
                textSize = 13.5f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(7), 0, 0)
            })
        }
        row.addView(textColumn)

        row.addView(noteIconButton(call))
        return row
    }

    private fun noteIconButton(call: PhoneCallRecord): MaterialButton {
        return MaterialButton(this).apply {
            text = "≡"
            textSize = 26f
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            setTextColor(getColor(R.color.calllog_muted_text))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            rippleColor = ColorStateList.valueOf(getColor(R.color.calllog_border))
            strokeWidth = 0
            elevation = 0f
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = dp(6)
            }
            contentDescription = "Бележка"
            setOnClickListener { openFormForCall(call) }
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(getColor(R.color.calllog_bg))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                leftMargin = dp(66)
            }
        }
    }

    private fun callMainLine(call: PhoneCallRecord): String {
        return listOf(
            timeFormat.format(Date(call.startedAt)),
            PhoneCallReader.formatDuration(call.durationSeconds).takeIf { call.durationSeconds > 0 },
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" • ")
    }

    private fun callSubLine(call: PhoneCallRecord): String {
        val contact = if (call.name.isNotBlank()) "${call.name} • ${call.number}" else call.number
        return listOf(contact, PhoneCallReader.directionLabel(call.direction))
            .filter { it.isNotBlank() }
            .joinToString(" • ")
    }

    private fun callIcon(call: PhoneCallRecord): String {
        return when (call.direction) {
            "out" -> "↗"
            else -> "↙"
        }
    }

    private fun callIconColor(call: PhoneCallRecord): Int {
        return when {
            call.direction == "out" -> Color.rgb(76, 175, 80)
            call.durationSeconds <= 0L && call.direction == "in" -> Color.rgb(244, 67, 54)
            else -> Color.rgb(33, 150, 243)
        }
    }

    private fun openFormForCall(call: PhoneCallRecord) {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank()) {
            binding.homeStatusText.text = "Първо попълни Base URL в Настройки."
            return
        }

        val formUrl = buildEndpoint(
            baseUrl = config.baseUrl,
            path = config.formPath,
            params = linkedMapOf(
                "phone" to call.number,
                "direction" to call.direction,
                "call_at" to call.startedAt.toString(),
                "duration" to call.durationSeconds.toString(),
                "access_token" to config.accessToken,
            )
        )

        startActivity(
            Intent(this, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, formUrl)
                .putExtra(WebViewActivity.EXTRA_PHONE, call.number)
                .putExtra(WebViewActivity.EXTRA_DIRECTION, call.direction)
        )
    }

    private fun isUsefulServerNote(line: String): Boolean {
        val normalized = line.trim()
        if (normalized.isBlank()) {
            return false
        }
        return !normalized.startsWith("В Call Report:") &&
            !normalized.startsWith("Предишни разговори:") &&
            !normalized.startsWith("В телефона:")
    }

    private fun dayTitle(startedAt: Long): String {
        val date = Date(startedAt)
        return "${dateFormat.format(date)} • ${weekdayName(startedAt)} • ${relativeDayLabel(startedAt)}"
    }

    private fun weekdayName(startedAt: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = startedAt }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "понеделник"
            Calendar.TUESDAY -> "вторник"
            Calendar.WEDNESDAY -> "сряда"
            Calendar.THURSDAY -> "четвъртък"
            Calendar.FRIDAY -> "петък"
            Calendar.SATURDAY -> "събота"
            else -> "неделя"
        }
    }

    private fun relativeDayLabel(startedAt: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val callDay = startOfDay(startedAt)
        val diffDays = ((today - callDay) / DAY_MS).toInt()
        return when (diffDays) {
            0 -> "днес"
            1 -> "вчера"
            else -> "преди $diffDays дни"
        }
    }

    private fun dayKey(startedAt: Long): Long {
        return startOfDay(startedAt)
    }

    private fun startOfDay(timeMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
