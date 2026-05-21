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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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
                if (formUrl.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
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

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(Color.rgb(249, 250, 251), dp(22), Color.rgb(209, 213, 219), dp(1))
            elevation = dp(10).toFloat()
        }

        card.addView(TextView(this).apply {
            text = title.ifBlank { phone.ifBlank { "Call Report" } }
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(17, 24, 39))
        })

        val localLine = LocalCallStatsProvider.buildLine(this, phone)
        val infoLines = buildList {
            if (localLine.isNotBlank()) add(localLine)
            if (subtitle.isNotBlank()) add(subtitle)
            addAll(lines.filter { it.isNotBlank() }.take(6))
        }
        if (infoLines.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = infoLines.joinToString("\n")
                textSize = 14f
                setTextColor(Color.rgb(75, 85, 99))
                setPadding(0, dp(8), 0, dp(10))
            })
        }

        card.addView(buttonRow("CRM лог", "Бележка") {
            addView(actionButton("CRM лог") { openCrmLog() })
            addView(actionButton("Бележка") { openFormOrCrm() })
        })
        card.addView(buttonRow("Тел. история", "История номер") {
            addView(actionButton("Тел. история") { openSystemHistory(SystemCallHistoryActivity.MODE_GENERAL) })
            addView(actionButton("История номер") { openSystemHistory(SystemCallHistoryActivity.MODE_NUMBER) })
        })
        card.addView(buttonRow("Сърч деф.", "Сърч Google") {
            addView(actionButton("Сърч деф.") { openSystemHistory(SystemCallHistoryActivity.MODE_SEARCH_DEFAULT) })
            addView(actionButton("Сърч Google") { openSystemHistory(SystemCallHistoryActivity.MODE_SEARCH_GOOGLE) })
        })
        card.addView(buttonRow("Локален лог", "Затвори") {
            addView(actionButton("Локален лог") { openLocalNumberLog() })
            addView(actionButton("Затвори") { stopSelf() })
        })

        val scroll = ScrollView(this).apply {
            addView(card)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(80)
            width = resources.displayMetrics.widthPixels - dp(24)
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
            text = "≡"
            textSize = 28f
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

    private fun buttonRow(left: String, right: String, block: LinearLayout.() -> Unit): LinearLayout {
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
        startActivity(
            Intent(this, WebViewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(WebViewActivity.EXTRA_URL, formUrl)
                .putExtra(WebViewActivity.EXTRA_PHONE, phone)
                .putExtra(WebViewActivity.EXTRA_DIRECTION, direction)
        )
        stopSelf()
    }

    private fun openFormOrCrm() {
        if (formUrl.isNotBlank()) {
            openForm()
        } else {
            openCrmLog()
        }
    }

    private fun openCrmLog() {
        val config = ConfigStore.load(this)
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