package com.onlineimoti.calllog

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class PostCallOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ui by lazy { PostCallOverlayUi(this) }
    private val noteEditor by lazy {
        PostCallNoteEditor(
            service = this,
            ui = ui,
            handler = handler,
            phone = { phone },
            direction = { direction },
            callAt = { callAt },
            durationSeconds = { durationSeconds },
            callDirectionColor = ::callDirectionColor,
            setWindowManager = { windowManager = it },
            removeOverlay = ::removeOverlay,
            addDraggableOverlay = ::addDraggableOverlay,
            showGeneralNoteEditor = ::showGeneralNoteEditor,
            openCalendarEvent = ::openCalendarEvent,
            openContactNotesScreen = ::openContactNotesScreen,
            stopOverlay = { stopSelf() },
        )
    }
    private val generalNoteEditor by lazy {
        PostCallGeneralNoteEditor(
            service = this,
            ui = ui,
            handler = handler,
            phone = { phone },
            setWindowManager = { windowManager = it },
            removeOverlay = ::removeOverlay,
            addDraggableOverlay = ::addDraggableOverlay,
            showNoteEditor = ::showNoteEditor,
            openCalendarEvent = ::openCalendarEvent,
            openContactNotesScreen = ::openContactNotesScreen,
            stopOverlay = { stopSelf() },
        )
    }
    private val lookupPopup by lazy {
        PostCallLookupPopup(
            service = this,
            ui = ui,
            phone = { phone },
            title = { title },
            setWindowManager = { windowManager = it },
            removeOverlay = ::removeOverlay,
            addDraggableOverlay = ::addDraggableOverlay,
            showNoteEditor = ::showNoteEditor,
            timeoutMs = LOOKUP_POPUP_TIMEOUT_MS,
        )
    }
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var loadingAnimator: ObjectAnimator? = null
    private var formUrl: String = ""
    private var phone: String = ""
    private var direction: String = ""
    private var title: String = ""
    private var subtitle: String = ""
    private var lines: List<String> = emptyList()
    private var callAt: Long = 0L
    private var durationSeconds: Long = 0L
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
        callAt = intent?.getLongExtra(EXTRA_CALL_AT, 0L) ?: 0L
        durationSeconds = intent?.getLongExtra(EXTRA_DURATION, 0L) ?: 0L

        if (callAt <= 0L && phone.isNotBlank()) {
            PhoneCallReader.callsForPhone(this, phone, limit = 1).firstOrNull()?.let { call ->
                callAt = call.startedAt
                durationSeconds = call.durationSeconds
                if (direction.isBlank()) direction = call.direction
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.getStringExtra(EXTRA_MODE).orEmpty()) {
            MODE_LOADING -> showLoadingPopup()
            MODE_LOOKUP -> showLookupPopup()
            MODE_NOTE -> showNoteEditor()
            MODE_GENERAL_NOTE -> showGeneralNoteEditor()
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
        loadingAnimator?.cancel()
        loadingAnimator = null
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showLoadingPopup() {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            stylePopupCard()
        }
        val spinner = TextView(this).apply {
            text = "↻"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 65, 81))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
        }
        card.addView(spinner)
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = title.ifBlank { phone.ifBlank { "Call Report" } }
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(17, 24, 39))
        })
        textColumn.addView(TextView(this).apply {
            text = subtitle.ifBlank { "Зареждат се данни…" }
            textSize = 14f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(textColumn)
        loadingAnimator = ObjectAnimator.ofFloat(spinner, View.ROTATION, 0f, 360f).apply {
            duration = 850L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        addDraggableOverlay(shadowScroll(card), focusable = false, defaultY = dp(135), timeoutMs = LOADING_POPUP_TIMEOUT_MS)
    }

    private fun showLookupPopup() {
        lookupPopup.show()
    }

    private fun showNoteEditor() {
        noteEditor.show()
    }

    private fun showGeneralNoteEditor() {
        generalNoteEditor.show()
    }

    private fun openCalendarEvent(displayName: String) {
        val safeName = displayName.ifBlank { phone.ifBlank { "контакт" } }
        val eventTitle = "Среща с $safeName"
        val description = buildString {
            appendLine("Име: $safeName")
            if (phone.isNotBlank()) appendLine("Телефон: $phone")
        }.trim()
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, eventTitle)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(intent)
            removeOverlay()
            stopSelf()
        }.onFailure {
            Toast.makeText(this, "Няма намерено приложение Календар", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBubble() {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(46)
        val bubble = ImageButton(this).apply {
            setImageResource(R.drawable.ic_chat_note)
            contentDescription = "Бележка"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(75, 85, 99))
            }
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(6).toFloat()
            translationZ = dp(2).toFloat()
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
        val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val popupWidth = resources.displayMetrics.widthPixels - dp(32)
        val params = WindowManager.LayoutParams(
            popupWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = prefs.getInt(KEY_LOOKUP_POPUP_Y, defaultY)
            width = popupWidth
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
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) + kotlin.math.abs(event.rawY - initialTouchY)
                    if (moved >= dp(8)) {
                        if (shouldDismissDraggedOverlay(params)) {
                            stopSelf()
                        } else {
                            params.x = 0
                            params.y = params.y.coerceAtLeast(0)
                            windowManager?.updateViewLayout(view, params)
                            prefs.edit().putInt(KEY_LOOKUP_POPUP_Y, params.y).remove(KEY_LOOKUP_POPUP_X).apply()
                        }
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

    private fun shouldDismissDraggedOverlay(params: WindowManager.LayoutParams): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        return kotlin.math.abs(params.x) > screenWidth / 4 || params.y < -dp(110) || params.y > screenHeight - dp(80)
    }

    private fun noteRightAction(): ImageButton = ui.noteRightAction { handler.post { showNoteEditor() } }

    private fun notePreviewRow(noteText: String, textColor: Int, backgroundColor: Int, strokeColor: Int, topMargin: Int, iconRes: Int): LinearLayout {
        return ui.notePreviewRow(noteText, textColor, backgroundColor, strokeColor, topMargin, iconRes)
    }

    private fun callNoteEditText(value: String, hintText: String, minLineCount: Int, topMargin: Int): EditText {
        return ui.callNoteEditText(value, hintText, minLineCount, topMargin)
    }

    private fun noteEditText(value: String, hintText: String, minLineCount: Int, topMargin: Int): EditText {
        return ui.noteEditText(value, hintText, minLineCount, topMargin)
    }

    private fun callDirectionColor(directionValue: String): Int {
        return when (directionValue) {
            "out" -> Color.rgb(34, 197, 94)
            "in" -> Color.rgb(59, 130, 246)
            else -> Color.rgb(107, 114, 128)
        }
    }

    private fun iconAction(drawableRes: Int, action: () -> Unit): ImageButton = ui.iconAction(drawableRes, action)

    private fun textAction(textValue: String, action: () -> Unit): TextView = ui.textAction(textValue, action)

    private fun secondaryTextAction(textValue: String, action: () -> Unit): TextView = ui.secondaryTextAction(textValue, action)

    private fun secondaryIconAction(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ui.secondaryIconAction(drawableRes, description, action)
    }

    private fun openContactNotesScreen() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, ContactGroupFilter.resolveDisplayName(this, phone).orEmpty().ifBlank { title.ifBlank { phone } })
        )
        stopSelf()
    }

    private fun View.stylePopupCard() = ui.stylePopupCard(this)

    private fun shadowScroll(card: View): ScrollView = ui.shadowScroll(card)

    private fun removeOverlay() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return ui.roundedRect(color, radius, strokeColor, strokeWidth)
    }

    private fun dp(value: Int): Int = ui.dp(value)

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_LOADING = "loading"
        const val MODE_LOOKUP = "lookup"
        const val MODE_NOTE = "note"
        const val MODE_GENERAL_NOTE = "general_note"
        const val EXTRA_FORM_URL = "form_url"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_LINES = "lines"
        const val EXTRA_CALL_AT = "call_at"
        const val EXTRA_DURATION = "duration"
        private const val LOADING_POPUP_TIMEOUT_MS = 45_000L
        private const val LOOKUP_POPUP_TIMEOUT_MS = 30_000L
        private const val LOOKUP_POPUP_POSITION_PREFS = "lookup_popup_position"
        private const val KEY_LOOKUP_POPUP_X = "lookup_popup_x"
        private const val KEY_LOOKUP_POPUP_Y = "lookup_popup_y"
    }
}
