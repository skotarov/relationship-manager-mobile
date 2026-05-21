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
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
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
            MODE_NOTE -> showNoteEditor()
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
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = roundedRect(Color.WHITE, dp(22), Color.TRANSPARENT, 0)
            elevation = dp(22).toFloat()
            translationZ = dp(8).toFloat()
        }

        card.addView(TextView(this).apply {
            text = titleText
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(17, 24, 39))
        })

        val infoAndCloseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, 0)
        }
        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        infoColumn.addView(keyValueRow("Разговори", callsValue))
        infoColumn.addView(keyValueRow("Последно", lastValue, topPadding = dp(2)))
        infoColumn.addView(keyValueRow("Бележка", noteValue, topPadding = dp(2)))
        infoAndCloseRow.addView(infoColumn)
        infoAndCloseRow.addView(iconAction(R.drawable.ic_popup_close) { stopSelf() })
        card.addView(infoAndCloseRow)

        val extraLine = lines.firstOrNull { it.isMeaningfulPopupLine() } ?: subtitle.takeIf { it.isMeaningfulPopupLine() }
        if (!extraLine.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = extraLine
                textSize = 12f
                setTextColor(Color.rgb(75, 85, 99))
                setPadding(0, dp(2), 0, 0)
            })
        }

        addDraggableOverlay(ScrollView(this).apply { addView(card) }, focusable = false, defaultY = dp(135), timeoutMs = LOOKUP_POPUP_TIMEOUT_MS)
    }

    private fun showNoteEditor() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val displayName = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
        val titleText = displayName.ifBlank { phone.ifBlank { "Бележка" } }
        val currentNote = ContactNoteReader.noteForPhone(this, phone)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.WHITE, dp(22), Color.TRANSPARENT, 0)
            elevation = dp(22).toFloat()
            translationZ = dp(8).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = titleText
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(17, 24, 39))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(iconAction(R.drawable.ic_popup_close) { stopSelf() })
        card.addView(titleRow)

        val noteInput = EditText(this).apply {
            setText(currentNote)
            hint = "Бележка към контакта"
            minLines = 3
            maxLines = 7
            textSize = 16f
            setTextColor(Color.rgb(17, 24, 39))
            setHintTextColor(Color.rgb(107, 114, 128))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(Color.rgb(249, 250, 251), dp(12), Color.rgb(209, 213, 219), dp(1))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
        card.addView(noteInput)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(textAction("Запази") {
            val saved = ContactNoteReader.saveNoteForPhone(this, phone, noteInput.text?.toString().orEmpty())
            Toast.makeText(this, if (saved) "Бележката е записана" else "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
            stopSelf()
        })
        card.addView(actions)

        val scroll = ScrollView(this).apply { addView(card) }
        addDraggableOverlay(scroll, focusable = true, defaultY = dp(135), timeoutMs = 0L)
        noteInput.requestFocus()
        handler.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(noteInput, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
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
                    if (moved < dp(8)) showNoteEditor()
                    true
                }
                else -> false
            }
        }

        overlayView = bubble
        windowManager?.addView(bubble, params)
    }

    private fun addDraggableOverlay(view: View, focusable: Boolean, defaultY: Int, timeoutMs: Long) {
        val prefs = getSharedPreferences(LOOKUP_POPUP_POSITION_PREFS, MODE_PRIVATE)
        val flags = if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = prefs.getInt(KEY_LOOKUP_POPUP_X, 0)
            y = prefs.getInt(KEY_LOOKUP_POPUP_Y, defaultY)
            width = resources.displayMetrics.widthPixels - dp(20)
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceAtLeast(0)
                    windowManager?.updateViewLayout(view, params)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) + kotlin.math.abs(event.rawY - initialTouchY)
                    if (moved >= dp(8)) {
                        prefs.edit()
                            .putInt(KEY_LOOKUP_POPUP_X, params.x)
                            .putInt(KEY_LOOKUP_POPUP_Y, params.y)
                            .apply()
                    }
                    false
                }
                else -> false
            }
        }

        overlayView = view
        windowManager?.addView(view, params)
        if (timeoutMs > 0) handler.postDelayed({ stopSelf() }, timeoutMs)
    }

    private fun keyValueRow(label: String, value: String, topPadding: Int = 0): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, topPadding, 0, 0)
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

    private fun iconAction(drawableRes: Int, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            background = ContextCompat.getDrawable(this@PostCallOverlayService, R.drawable.popup_icon_circle_bg)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(6)
                marginEnd = dp(0)
            }
            setOnClickListener { action() }
        }
    }

    private fun textAction(textValue: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedRect(Color.rgb(55, 65, 81), dp(12), Color.TRANSPARENT, 0)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun openForm() {
        showNoteEditor()
    }

    private fun openFormOrWarn() {
        showNoteEditor()
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
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
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
        const val MODE_NOTE = "note"
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
