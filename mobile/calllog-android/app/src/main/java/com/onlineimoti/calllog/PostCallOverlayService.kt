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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.WHITE, dp(22), Color.rgb(55, 65, 81), dp(2))
            elevation = dp(16).toFloat()
        }

        card.addView(TextView(this).apply {
            text = titleText
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(17, 24, 39))
        })
        card.addView(keyValueRow("Разговори", callsValue, topPadding = dp(6)))
        card.addView(keyValueRow("Последно", lastValue, topPadding = dp(2)))
        card.addView(keyValueRow("Бележка", noteValue, topPadding = dp(2), bottomPadding = dp(6)))

        val extraLine = lines.firstOrNull { it.isMeaningfulPopupLine() } ?: subtitle.takeIf { it.isMeaningfulPopupLine() }
        if (!extraLine.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = extraLine
                textSize = 12f
                setTextColor(Color.rgb(75, 85, 99))
                setPadding(0, 0, 0, dp(4))
            })
        }

        card.addView(iconRow {
            addView(iconAction(R.drawable.ic_popup_close) { stopSelf() })
        })

        val prefs = getSharedPreferences(LOOKUP_POPUP_POSITION_PREFS, MODE_PRIVATE)
        val scroll = ScrollView(this).apply { addView(card) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = prefs.getInt(KEY_LOOKUP_POPUP_X, 0)
            y = prefs.getInt(KEY_LOOKUP_POPUP_Y, dp(135))
            width = resources.displayMetrics.widthPixels - dp(20)
        }

        scroll.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceAtLeast(0)
                    windowManager?.updateViewLayout(scroll, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) + kotlin.math.abs(event.rawY - initialTouchY)
                    if (moved >= dp(8)) {
                        prefs.edit()
                            .putInt(KEY_LOOKUP_POPUP_X, params.x)
                            .putInt(KEY_LOOKUP_POPUP_Y, params.y)
                            .apply()
                    }
                    true
                }
                else -> false
            }
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
                    if (moved < dp(8)) openForm()
                    true
                }
                else -> false
            }
        }

        overlayView = bubble
        windowManager?.addView(bubble, params)
    }

    private fun keyValueRow(label: String, value: String, topPadding: Int = 0, bottomPadding: Int = 0): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, topPadding, 0, bottomPadding)
            addView(TextView(this@PostCallOverlayService).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(107, 114, 128))
                layoutParams = LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(this@PostCallOverlayService).apply {
                text = value
                textSize = 14f
                setTextColor(Color.rgb(31, 41, 55))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun iconRow(block: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
            block()
        }
    }

    private fun iconAction(drawableRes: Int, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            background = ContextCompat.getDrawable(this@PostCallOverlayService, R.drawable.popup_icon_circle_bg)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            setOnClickListener { action() }
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
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) {
            showServerTokenRequired()
            return
        }
        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = config.historyPath,
            params = linkedMapOf("phone" to phone, "direction" to direction, "access_token" to config.accessToken),
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

    private fun String.isMeaningfulPopupLine(): Boolean {
        val value = trim()
        if (value.isBlank()) return false
        return !value.startsWith("Локален режим", ignoreCase = true)
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
        private const val LOOKUP_POPUP_POSITION_PREFS = "lookup_popup_position"
        private const val KEY_LOOKUP_POPUP_X = "lookup_popup_x"
        private const val KEY_LOOKUP_POPUP_Y = "lookup_popup_y"
    }
}
