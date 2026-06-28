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
    private var phoneFilter: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        binding = ActivityRecentCallsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        phoneFilter = intent.getStringExtra(EXTRA_PHONE_FILTER).orEmpty()
    }

    override fun onResume() {
        super.onResume()
        renderCalls()
    }

    private fun renderCalls() {
        binding.recentCallsContainer.removeAllViews()
        val isFiltered = phoneFilter.isNotBlank()
        binding.recentCallsTitleText.text = getString(
            if (isFiltered) R.string.recent_calls_title_filtered else R.string.recent_calls_title,
        )

        if (!PhoneCallReader.hasCallLogPermission(this)) {
            binding.recentCallsStatusText.text = getString(R.string.recent_calls_permission_missing)
            return
        }

        val calls = if (isFiltered) {
            // A filtered contact timeline contains both phone calls and SMS messages, ordered together by date.
            HomeCallPageLoader.calls(
                context = this,
                activePhoneFilter = phoneFilter,
                searchQuery = "",
                pageIndex = 0,
                pageSize = 50,
            )
        } else {
            PhoneCallReader.recentCalls(this, limit = 20)
        }
        if (calls.isEmpty()) {
            binding.recentCallsStatusText.text = if (isFiltered) {
                getString(R.string.recent_calls_empty_filtered, phoneFilter)
            } else {
                getString(R.string.recent_calls_empty)
            }
            return
        }

        binding.recentCallsStatusText.text = if (isFiltered) {
            getString(R.string.recent_calls_showing_filtered, calls.size, phoneFilter)
        } else {
            getString(R.string.recent_calls_showing, calls.size)
        }
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
            text = if (call.isSms) {
                listOf(
                    call.number,
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    call.smsDirectionLabel,
                ).filter { it.isNotBlank() }.joinToString(" • ")
            } else {
                listOf(
                    call.number,
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    PhoneCallReader.directionLabel(call.direction),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
            }
            setTextColor(getColor(R.color.calllog_muted_text))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        })
        if (call.isSms) {
            content.addView(TextView(this).apply {
                text = call.smsBody.ifBlank { getString(R.string.dynamic_sms_empty_body) }
                setTextColor(getColor(R.color.calllog_text))
                textSize = 14f
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            })
        } else {
            content.addView(MaterialButton(this).apply {
                text = getString(R.string.recent_calls_write_note)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                }
                setOnClickListener { openPromptForCall(call) }
            })
        }

        card.addView(content)
        return card
    }

    private fun openPromptForCall(call: PhoneCallRecord) {
        val config = ConfigStore.load(this)
        val formUrl = buildEndpoint(
            baseUrl = config.baseUrl,
            path = "/relationship-manager/form.php",
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

    companion object {
        const val EXTRA_PHONE_FILTER = "phone_filter"
    }
}
