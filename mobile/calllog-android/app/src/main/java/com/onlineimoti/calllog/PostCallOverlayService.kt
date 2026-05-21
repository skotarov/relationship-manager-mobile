package com.onlineimoti.calllog

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class PostCallOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var formUrl: String = ""
    private var phone: String = ""
    private var direction: String = ""
    private var title: String = ""
    private var subtitle: String = ""
    private var lines: List<String> = emptyList()
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        formUrl = intent?.getStringExtra(EXTRA_FORM_URL).orEmpty()
        phone = intent?.getStringExtra(EXTRA_PHONE).orEmpty()
        direction = intent?.getStringExtra(EXTRA_DIRECTION).orEmpty()
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        subtitle = intent?.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        lines = intent?.getStringArrayListExtra(EXTRA_LINES).orEmpty()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.getStringExtra(EXTRA_MODE).orEmpty()) {
            MODE_LOOKUP -> showLookupPopup()
            else -> {
                showBubble()
                val timeout = ConfigStore.load(this).postCallPromptTimeoutSeconds.coerceIn(3, 120)
                handler.postDelayed({ stopSelf() }, timeout * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showLookupPopup() {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayName = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
        val titleText = when {
            displayName.isNotBlank() && phone.isNotBlank() -> "$displayName • $phone"
            displayName.isNotBlank() -> displayName
            title.isNotBlank() && title != phone -> "$title • $phone"
            else -> phone.ifBlank { title.ifBlank { "Call Report" } }
        }
        val summary = LocalCallStatsProvider.summarize(this, phone)
        val contactNote = ContactNoteReader.noteForPhone(this, phone)
        val callsValue = summary?.let { if (it.count <= 0) "няма предишни разговори" else it.count.toString() }.orEmpty().ifBlank { "няма данни" }
        val lastValue = summary?.let { if (it.count <= 0) "няма предишно обаждане" else it.lastCallAgo.ifBlank { "няма данни" } }.orEmpty().ifBlank { "няма данни" }
        val noteValue = contactNote.ifBlank { "няма" }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedRect(Color.WHITE, dp(22), Color.rgb(55, 65, 81), dp(2))
            elevation = dp(16).toFloat()
        }

        card.addView(TextView(this).apply {
            text = titleText
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(17, 24, 39))
        })

        card.addView(TextView(this).apply {
            text = labelValue("Разговори", callsValue)
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(0, dp(10), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = labelValue("Последно", lastValue)
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = labelValue("Бележка", noteValue)
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            setPadding(0, dp(4), 0, dp(10))
        })

        val extraLine = lines.firstOrNull { it.isNotBlank() } ?: subtitle
        if (extraLine.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = extraLine
                textSize = 13f
                setTextColor(Color.rgb(75, 85, 99))
                setPadding(0, 0, 0, dp(8))
            })
        }

        card.addView(buttonRow {
            addView(actionButton("CRM лог") { openCrmLog() })
            addView(actionButton("Бележка") { openFormOrWarn() })
        })
        card.addView(buttonRow {
            addView(actionButton("Тел. история") { openSystemHistory(SystemCallHistoryActivity.MODE_GENERAL) })
            addView(actionButton("История номер") { openSystemHistory(SystemCallHistoryActivity.MODE_NUMBER) })
        })
        card.addView(buttonRow {
            addView(actionButton("Локален лог") { openLocalNumberLog() })
            addView(actionButton("Затвори") { stopSelf() })
        })

        val scroll = ScrollView(this).apply { addView(card) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(135)
            width = resources.displayMetrics.widthPixels - dp(20)
        }

        overlayView = scroll
        windowManager?.addView(scroll, params)
        handler.postDelayed({ stopSelf() }, LOOKUP_POPUP_TIMEOUT_MS)
    }

    private fun showBubble() {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val size = dp(58)
        val bubble = TextView(this).apply {
            text = "✎"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(55, 65, 81))
            }
            elevation = dp(8).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(18)
            y = dp(110)
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) + kotlin.math.abs(event.rawY - initialTouchY)
                    if (moved < dp(8)) {
                        openForm()
                    }
                    true
                }
                else -> false
            }
        }

        overlayView = bubble
        windowManager?.addView(bubble, params)
    }

    private fun buttonRow(block: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
            block()
        }
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
                marginStart = dp(4)
            }
        }
    }

    private fun openForm() {
        if (formUrl.isBlank()) {
            showServerTokenRequired()
            return
        }
        startActivity(
            Intent(this, WebViewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(WebViewActivity.EXTRA_URL, formUrl)
                .putExtra(WebViewActivity.EXTRA_PHONE, phone)
                .putExtra(WebViewActivity.EXTRA_DIRECTION, direction)
        )
        stopSelf()
    }

    private fun openFormOrWarn() {
        if (formUrl.isNotBlank()) openForm() else showServerTokenRequired()
    }

    private fun openCrmLog() {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            showServerTokenRequired()
            return
        }
        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = config.historyPath,
            params = linkedMapOf(
                "phone" to phone,
                "direction" to direction,
                "access_token" to config.accessToken,
            )
        )
        startActivity(
            Intent(this, WebViewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(WebViewActivity.EXTRA_URL, url)
                .putExtra(WebViewActivity.EXTRA_PHONE, phone)
                .putExtra(WebViewActivity.EXTRA_DIRECTION, direction)
        )
        stopSelf()
    }

    private fun openSystemHistory(mode: String) {
        startActivity(
            Intent(this, SystemCallHistoryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SystemCallHistoryActivity.EXTRA_PHONE, phone)
                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, mode)
        )
        stopSelf()
    }

    private fun openLocalNumberLog() {
        startActivity(
            Intent(this, RecentCallsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RecentCallsActivity.EXTRA_PHONE_FILTER, phone)
        )
        stopSelf()
    }

    private fun showServerTokenRequired() {
        Toast.makeText(this, "За CRM/бележка е нужен access token", Toast.LENGTH_SHORT).show()
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun labelValue(label: String, value: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val start = builder.length
        builder.append(label)
        builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(": ")
        builder.append(value)
        return builder
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LOOKUP = "lookup"
        const val EXTRA_FORM_URL = "form_url"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_LINES = "lines"
        private const val LOOKUP_POPUP_TIMEOUT_MS = 30_000L
    }
}
