package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.onlineimoti.calllog.databinding.ActivityRecentCallsBinding

class RecentCallsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecentCallsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentCallsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        renderCalls()
    }

    private fun renderCalls() {
        binding.recentCallsContainer.removeAllViews()
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.recentCallsStatusText.text = "Липсва достъп до телефонния log. Отвори Настройки и разреши Call log."
            return
        }

        val calls = PhoneCallReader.recentCalls(this, limit = 20)
        if (calls.isEmpty()) {
            binding.recentCallsStatusText.text = "Няма намерени разговори."
            return
        }

        binding.recentCallsStatusText.text = "Показвам последните ${calls.size} разговора."
        calls.forEach { call ->
            binding.recentCallsContainer.addView(callCard(call))
        }
    }

    private fun callCard(call: PhoneCallRecord): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.calllog_border))
            setCardBackgroundColor(getColor(R.color.calllog_surface))
            cardElevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }

        content.addView(TextView(this).apply {
            text = call.displayName
            setTextColor(getColor(R.color.calllog_text))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = listOf(
                call.number,
                PhoneCallReader.formatStartedAt(call.startedAt),
                PhoneCallReader.directionLabel(call.direction),
                PhoneCallReader.formatDuration(call.durationSeconds),
            ).filter { it.isNotBlank() }.joinToString(" • ")
            setTextColor(getColor(R.color.calllog_muted_text))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        })
        content.addView(MaterialButton(this).apply {
            text = "Запиши бележка"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
            setOnClickListener { openPromptForCall(call) }
        })

        card.addView(content)
        return card
    }

    private fun openPromptForCall(call: PhoneCallRecord) {
        val config = ConfigStore.load(this)
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
