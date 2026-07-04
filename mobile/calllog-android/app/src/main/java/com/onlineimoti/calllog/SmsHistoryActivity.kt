package com.onlineimoti.calllog

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.Executors

/** Device-wide chronological SMS list opened from the Call Log overflow menu. */
class SmsHistoryActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var listContainer: LinearLayout
    private lateinit var previousButton: MaterialButton
    private lateinit var nextButton: MaterialButton
    private lateinit var pageText: TextView
    private var pageIndex = 0
    private var loading = false

    private val readSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        renderPage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        renderPage()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized && !loading) renderPage()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.calllog_bg))
        }
        root.addView(header())
        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.calllog_muted_text))
            setPadding(dp(18), 0, dp(18), dp(6))
        }
        root.addView(statusText)
        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(6)
        })
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        root.addView(ScrollView(this).apply {
            addView(listContainer)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        root.addView(pagination())
        return root
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(12), dp(16), dp(8))
        addView(ImageButton(this@SmsHistoryActivity).apply {
            setImageResource(R.drawable.ic_settings_back)
            contentDescription = "Назад"
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(TextView(this@SmsHistoryActivity).apply {
            text = "SMS"
            textSize = 24f
            setTextColor(getColor(R.color.calllog_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun pagination(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(8), dp(12), dp(12))
        previousButton = pageButton("Предишни") {
            if (pageIndex > 0) {
                pageIndex--
                renderPage()
            }
        }
        nextButton = pageButton("Следващи") {
            pageIndex++
            renderPage()
        }
        pageText = TextView(this@SmsHistoryActivity).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(getColor(R.color.calllog_muted_text))
        }
        addView(previousButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        addView(pageText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
        addView(nextButton, LinearLayout.LayoutParams(0, dp(42), 1f))
    }

    private fun pageButton(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun renderPage() {
        if (!hasSmsPermission()) {
            renderPermissionRequired()
            return
        }
        if (loading) return
        loading = true
        progress.visibility = View.VISIBLE
        statusText.text = "Зареждане на SMS…"
        previousButton.isEnabled = false
        nextButton.isEnabled = false
        val requestedPage = pageIndex
        val pageSize = pageSize()
        executor.execute {
            val loaded = SmsMessageReader.recentMessages(
                context = applicationContext,
                offset = requestedPage * pageSize,
                limit = pageSize + 1,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || requestedPage != pageIndex) return@runOnUiThread
                loading = false
                progress.visibility = View.GONE
                val hasNext = loaded.size > pageSize
                val messages = loaded.take(pageSize)
                renderRows(messages)
                val first = requestedPage * pageSize + 1
                val last = requestedPage * pageSize + messages.size
                statusText.text = when {
                    messages.isEmpty() && requestedPage == 0 -> "Няма SMS в телефона"
                    messages.isEmpty() -> "Няма повече SMS"
                    else -> "SMS по дата: $first–$last"
                }
                pageText.text = "Страница ${requestedPage + 1}"
                previousButton.text = "Предишни $pageSize"
                nextButton.text = "Следващи $pageSize"
                previousButton.isEnabled = requestedPage > 0
                nextButton.isEnabled = hasNext
            }
        }
    }

    private fun renderPermissionRequired() {
        loading = false
        progress.visibility = View.GONE
        listContainer.removeAllViews()
        statusText.text = "Нужно е разрешение за четене на SMS"
        pageText.text = ""
        previousButton.isEnabled = false
        nextButton.isEnabled = false
        listContainer.addView(TextView(this).apply {
            text = "За да видиш хронологичния списък със SMS-и, разреши достъп до SMS на приложението."
            textSize = 15f
            setTextColor(getColor(R.color.calllog_text))
            setPadding(dp(4), dp(12), dp(4), dp(12))
        })
        listContainer.addView(MaterialButton(this).apply {
            text = "Разреши достъп до SMS"
            isAllCaps = false
            setOnClickListener { readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS) }
        })
    }

    private fun renderRows(messages: List<SmsTimelineMessage>) {
        listContainer.removeAllViews()
        messages.forEach { message -> listContainer.addView(smsRow(message)) }
    }

    private fun smsRow(message: SmsTimelineMessage): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(getColor(R.color.calllog_border))
            setCardBackgroundColor(Color.rgb(248, 250, 252))
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(LinearLayout(this@SmsHistoryActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                addView(ImageView(this@SmsHistoryActivity).apply {
                    setImageResource(if (message.isOutgoing) R.drawable.ic_sms_bubble_left else R.drawable.ic_sms_bubble_right)
                    contentDescription = message.directionLabel
                    scaleType = ImageView.ScaleType.CENTER
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(6) })
                addView(TextView(this@SmsHistoryActivity).apply {
                    text = "${message.directionLabel.replaceFirstChar { it.titlecase() }} • ${PhoneCallReader.formatStartedAt(message.timestampMs)}"
                    textSize = 12.5f
                    setTextColor(getColor(R.color.calllog_muted_text))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(TextView(this@SmsHistoryActivity).apply {
                text = message.address.ifBlank { "Неизвестен номер" }
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.calllog_text))
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@SmsHistoryActivity).apply {
                text = message.body.ifBlank { "(празно SMS)" }
                textSize = 14f
                setTextColor(getColor(R.color.calllog_text))
                setPadding(0, dp(5), 0, 0)
            })
        })
        return card
    }

    private fun pageSize(): Int = ConfigStore.load(this).homeCallPageSize.coerceIn(5, 100)

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
