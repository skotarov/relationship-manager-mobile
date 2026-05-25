package com.onlineimoti.calllog

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Date

class PostCallOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
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
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayName = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
        val titleText = when {
            displayName.isNotBlank() && phone.isNotBlank() -> "$displayName • $phone"
            displayName.isNotBlank() -> displayName
            title.isNotBlank() && title != phone -> "$title • $phone"
            else -> phone.ifBlank { title.ifBlank { "Call Report" } }
        }
        val infoRows = LocalCallStatsProvider.buildPopupInfoRows(this, phone)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(24), dp(18))
            stylePopupCard()
        }
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        contentRow.addView(notificationIcon())

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = titleText
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(17, 24, 39))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = currentTimeText()
            textSize = 12f
            setTextColor(Color.rgb(156, 163, 175))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        })
        contentColumn.addView(titleRow)

        if (infoRows.isNotEmpty()) {
            val dataColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            infoRows.forEachIndexed { index, line ->
                dataColumn.addView(TextView(this).apply {
                    text = line
                    textSize = 14f
                    setTextColor(Color.rgb(75, 85, 99))
                    setPadding(0, if (index == 0) 0 else dp(2), 0, 0)
                    maxLines = if (line.startsWith("✎")) 2 else 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            contentColumn.addView(dataColumn)
        }

        contentRow.addView(contentColumn)
        contentRow.addView(lookupRightIcon(infoRows.isNotEmpty()))
        card.addView(contentRow)

        val editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(52), dp(12), 0, 0)
        }
        editRow.addView(notificationEditAction("💬") { showNoteEditor() })
        editRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        editRow.addView(notificationEditAction("История") { openContactNotesScreen() })
        card.addView(editRow)

        addDraggableOverlay(shadowScroll(card), focusable = false, defaultY = dp(74), timeoutMs = LOOKUP_POPUP_TIMEOUT_MS)
    }

    private fun showNoteEditor() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val displayName = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
        val titleText = displayName.ifBlank { phone.ifBlank { "Бележка към обаждане" } }
        val callNote = ContactNoteReader.callNoteForPhone(phone, callAt, direction)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            stylePopupCard()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@PostCallOverlayService).apply {
                text = "Бележка от разговора"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(17, 24, 39))
            })
            addView(TextView(this@PostCallOverlayService).apply {
                text = titleText
                textSize = 13f
                setTextColor(Color.rgb(107, 114, 128))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })
        titleRow.addView(iconAction(R.drawable.ic_calendar_event) { openCalendarEvent(titleText) })
        titleRow.addView(iconAction(R.drawable.ic_popup_close) { stopSelf() })
        card.addView(titleRow)

        if (callAt > 0L) {
            card.addView(TextView(this).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(callAt),
                    PhoneCallReader.formatDuration(durationSeconds),
                    PhoneCallReader.directionLabel(direction),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 13f
                setTextColor(Color.rgb(107, 114, 128))
                setPadding(0, dp(6), 0, 0)
            })
        }

        val callNoteInput = callNoteEditText(
            value = callNote,
            hintText = "Бележка към това обаждане",
            minLineCount = 3,
            topMargin = dp(12),
        )
        card.addView(callNoteInput)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(secondaryTextAction("Основна") { showGeneralNoteEditor() })
        actions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        actions.addView(secondaryTextAction("История") { openContactNotesScreen() })
        actions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        actions.addView(textAction("Запази") {
            val callText = callNoteInput.text?.toString().orEmpty()
            val callSaved = callText.isBlank() || ContactNoteReader.saveCallNoteForPhone(
                context = this,
                phoneNumber = phone,
                note = callText,
                direction = direction,
                callAt = callAt,
                durationSeconds = durationSeconds,
            )
            Toast.makeText(this, if (callSaved) "Бележката към обаждането е записана" else "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
            stopSelf()
        })
        card.addView(actions)

        addDraggableOverlay(shadowScroll(card), focusable = true, defaultY = dp(135), timeoutMs = 0L)
        callNoteInput.requestFocus()
        handler.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(callNoteInput, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
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

    private fun showGeneralNoteEditor() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val displayName = ContactGroupFilter.resolveDisplayName(this, phone).orEmpty()
        val titleText = displayName.ifBlank { phone.ifBlank { "Основна бележка" } }
        val generalNote = ContactNoteReader.generalNoteForPhone(this, phone)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            stylePopupCard()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_note_lines)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = roundedRect(Color.rgb(243, 244, 246), dp(18), Color.TRANSPARENT, 0)
            layoutParams = LinearLayout.LayoutParams(dp(35), dp(35)).apply { marginEnd = dp(8) }
        })
        titleRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@PostCallOverlayService).apply {
                text = "Основна бележка"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(17, 24, 39))
            })
            addView(TextView(this@PostCallOverlayService).apply {
                text = titleText
                textSize = 13f
                setTextColor(Color.rgb(107, 114, 128))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })
        titleRow.addView(iconAction(R.drawable.ic_calendar_event) { openCalendarEvent(titleText) })
        titleRow.addView(iconAction(R.drawable.ic_popup_close) { stopSelf() })
        card.addView(titleRow)

        val generalNoteInput = noteEditText(
            value = generalNote,
            hintText = "Основна бележка към контакта/номера",
            minLineCount = 4,
            topMargin = dp(12),
        )
        card.addView(generalNoteInput)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(secondaryTextAction("Към разговора") { showNoteEditor() })
        actions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        actions.addView(secondaryTextAction("История") { openContactNotesScreen() })
        actions.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        actions.addView(textAction("Запази") {
            val saved = ContactNoteReader.saveGeneralNoteForPhone(this, phone, generalNoteInput.text?.toString().orEmpty())
            Toast.makeText(this, if (saved) "Основната бележка е записана" else "Не успях да запиша основната бележка", Toast.LENGTH_SHORT).show()
            stopSelf()
        })
        card.addView(actions)

        addDraggableOverlay(shadowScroll(card), focusable = true, defaultY = dp(135), timeoutMs = 0L)
        generalNoteInput.requestFocus()
        handler.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(generalNoteInput, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun showBubble() {
        removeOverlay()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(46)
        val bubble = TextView(this).apply {
            text = "✎"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(55, 65, 81))
            }
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
            width = resources.displayMetrics.widthPixels - dp(4)
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
                            params.y = params.y.coerceAtLeast(0)
                            windowManager?.updateViewLayout(view, params)
                            prefs.edit().putInt(KEY_LOOKUP_POPUP_X, params.x).putInt(KEY_LOOKUP_POPUP_Y, params.y).apply()
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
        return kotlin.math.abs(params.x) > screenWidth / 3 || params.y < -dp(110) || params.y > screenHeight - dp(80)
    }

    private fun notificationIcon(): ImageView {
        return ImageView(this).apply {
            setImageResource(R.drawable.callreport_popup_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(10)
                topMargin = dp(2)
            }
        }
    }

    private fun lookupRightIcon(hasLoggedData: Boolean): View {
        val photoUri = contactPhotoUri(phone)
        if (!photoUri.isNullOrBlank()) {
            return ImageView(this).apply {
                setImageURI(Uri.parse(photoUri))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedRect(Color.rgb(229, 231, 235), dp(22), Color.TRANSPARENT, 0)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(dp(45), dp(45)).apply {
                    marginStart = dp(10)
                    topMargin = dp(2)
                }
                setOnClickListener { openContactNotesScreen() }
            }
        }
        return TextView(this).apply {
            text = if (hasLoggedData) "i" else ""
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedRect(if (hasLoggedData) Color.rgb(14, 165, 233) else Color.TRANSPARENT, dp(22), Color.TRANSPARENT, 0)
            visibility = if (hasLoggedData) View.VISIBLE else View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(dp(45), dp(45)).apply {
                marginStart = dp(10)
                topMargin = dp(2)
            }
            if (hasLoggedData) setOnClickListener { openContactNotesScreen() }
        }
    }

    private fun contactPhotoUri(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return contentResolver.query(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phoneNumber).build(),
            arrayOf(ContactsContract.PhoneLookup.PHOTO_URI, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0).orEmpty().ifBlank { cursor.getString(1).orEmpty() }.ifBlank { null }
        }
    }

    private fun generalNoteInfo(note: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            background = roundedRect(Color.rgb(249, 250, 251), dp(12), Color.rgb(226, 232, 240), dp(1))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            addView(TextView(this@PostCallOverlayService).apply {
                text = "Обща бележка — само за информация"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(100, 116, 139))
            })
            addView(TextView(this@PostCallOverlayService).apply {
                text = note
                textSize = 14f
                setTextColor(Color.rgb(51, 65, 85))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun callNoteEditText(value: String, hintText: String, minLineCount: Int, topMargin: Int): EditText {
        return noteEditText(value, hintText, minLineCount, topMargin).apply {
            setTextColor(Color.rgb(8, 47, 73))
            setHintTextColor(Color.rgb(14, 116, 144))
            background = roundedRect(Color.rgb(224, 246, 255), dp(12), Color.rgb(125, 211, 252), dp(1))
        }
    }

    private fun noteEditText(value: String, hintText: String, minLineCount: Int, topMargin: Int): EditText {
        return EditText(this).apply {
            setText(value)
            hint = hintText
            minLines = minLineCount
            maxLines = 5
            textSize = 16f
            setTextColor(Color.rgb(17, 24, 39))
            setHintTextColor(Color.rgb(107, 114, 128))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(249, 250, 251), dp(12), Color.rgb(209, 213, 219), dp(1))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin }
        }
    }

    private fun notificationEditAction(textValue: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(75, 85, 99))
            background = roundedRect(Color.rgb(243, 244, 246), dp(22), Color.TRANSPARENT, 0)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minWidth = dp(64)
            setOnClickListener { action() }
        }
    }

    private fun currentTimeText(): String = android.text.format.DateFormat.getTimeFormat(this).format(Date())

    private fun iconAction(drawableRes: Int, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            background = ContextCompat.getDrawable(this@PostCallOverlayService, R.drawable.popup_icon_circle_bg)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(35), dp(35)).apply {
                marginStart = dp(5)
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
            clipToOutline = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { action() }
        }
    }

    private fun secondaryTextAction(textValue: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 65, 81))
            background = roundedRect(Color.rgb(243, 244, 246), dp(12), Color.TRANSPARENT, 0)
            clipToOutline = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { action() }
        }
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

    private fun View.stylePopupCard() {
        background = roundedRect(Color.WHITE, dp(24), Color.TRANSPARENT, 0)
        clipToOutline = true
        elevation = dp(22).toFloat()
        translationZ = dp(6).toFloat()
    }

    private fun shadowScroll(card: View): ScrollView {
        return ScrollView(this).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            clipToPadding = false
            clipChildren = false
            addView(card)
        }
    }

    private fun showServerTokenRequired() {
        Toast.makeText(this, "За CRM/бележка е нужен access token", Toast.LENGTH_SHORT).show()
    }

    private fun removeOverlay() {
        loadingAnimator?.cancel()
        loadingAnimator = null
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
