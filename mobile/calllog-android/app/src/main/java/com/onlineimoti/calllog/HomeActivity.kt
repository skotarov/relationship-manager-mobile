package com.onlineimoti.calllog

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private val executor = Executors.newSingleThreadExecutor()
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

        binding.homeStatusText.text = "Разговори ${pageIndex * PAGE_SIZE + 1}–${pageIndex * PAGE_SIZE + currentCalls.size}"
        binding.previousCallsButton.isEnabled = pageIndex > 0
        binding.nextCallsButton.isEnabled = currentCalls.size >= PAGE_SIZE
        binding.pageText.text = "Стр. ${pageIndex + 1}"
        binding.paginationContainer.visibility = android.view.View.VISIBLE
        currentCalls.forEach { call ->
            binding.homeCallsContainer.addView(compactCallRow(call, serverNotesByNumber[noteKey(call.number)]))
        }
        loadServerNotesForCurrentPage()
    }

    private fun renderCurrentPageWithNotes() {
        binding.homeCallsContainer.removeAllViews()
        currentCalls.forEach { call ->
            binding.homeCallsContainer.addView(compactCallRow(call, serverNotesByNumber[noteKey(call.number)]))
        }
    }

    private fun loadServerNotesForCurrentPage() {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            return
        }

        val visibleNumbers = currentCalls.map { it.number }.distinctBy { noteKey(it) }
        if (visibleNumbers.isEmpty()) {
            return
        }

        binding.homeStatusText.text = "${binding.homeStatusText.text} • зареждам бележки…"
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
                serverNotesByNumber = notes
                binding.homeStatusText.text = "Разговори ${pageIndex * PAGE_SIZE + 1}–${pageIndex * PAGE_SIZE + currentCalls.size}"
                renderCurrentPageWithNotes()
            }
        }
    }

    private fun compactCallRow(call: PhoneCallRecord, serverNote: String? = null): MaterialCardView {
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
            text = call.displayName
            setTextColor(getColor(R.color.calllog_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
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
        if (!serverNote.isNullOrBlank()) {
            textColumn.addView(TextView(this).apply {
                text = "📝 $serverNote"
                setTextColor(getColor(R.color.calllog_muted_text))
                textSize = 12.5f
                maxLines = 2
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(textColumn)

        row.addView(MaterialButton(this).apply {
            text = "Бележка"
            minWidth = 0
            minHeight = dp(36)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
            ).apply {
                leftMargin = dp(8)
            }
            setOnClickListener { openFormForCall(call) }
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

    private fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}
