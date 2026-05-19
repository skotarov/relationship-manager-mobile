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

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderCalls()
    }

    private fun renderCalls() {
        binding.homeCallsContainer.removeAllViews()
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.homeStatusText.text = "Липсва достъп до телефонния log. Отвори ⚙ Настройки и разреши Call log."
            return
        }

        val calls = PhoneCallReader.recentCalls(this, limit = 20)
        if (calls.isEmpty()) {
            binding.homeStatusText.text = "Няма намерени разговори."
            return
        }

        binding.homeStatusText.text = "Последни ${calls.size} разговора"
        calls.forEach { call ->
            binding.homeCallsContainer.addView(compactCallRow(call))
        }
    }

    private fun compactCallRow(call: PhoneCallRecord): MaterialCardView {
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
            setOnClickListener { openPromptForCall(call) }
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

    private fun openPromptForCall(call: PhoneCallRecord) {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank()) {
            binding.homeStatusText.text = "Първо попълни Base URL в Настройки."
            return
        }

        val formUrl = buildEndpoint(
            baseUrl = config.baseUrl,
            path = "/broker/callreport/form.php",
            params = linkedMapOf(
                "phone" to call.number,
                "direction" to call.direction,
                "call_at" to call.startedAt.toString(),
                "duration" to call.durationSeconds.toString(),
                "access_token" to config.accessToken,
            )
        )

        startActivity(
            Intent(this, PostCallPromptActivity::class.java)
                .putExtra(PostCallPromptActivity.EXTRA_FORM_URL, formUrl)
                .putExtra(PostCallPromptActivity.EXTRA_PHONE, call.number)
                .putExtra(PostCallPromptActivity.EXTRA_DIRECTION, call.direction)
                .putExtra(PostCallPromptActivity.EXTRA_TITLE, call.displayName)
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
