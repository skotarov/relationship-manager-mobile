package com.onlineimoti.calllog

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

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
            pendingCallNote = { pendingCallNote },
            setPendingCallNote = { pendingCallNote = it },
            savePendingNoteChangesBeforeHistory = ::savePendingNoteChangesBeforeHistory,
            notifyNotesChanged = ::notifyNotesChanged,
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
            pendingGeneralNote = { pendingGeneralNote },
            setPendingGeneralNote = { pendingGeneralNote = it },
            savePendingNoteChangesBeforeHistory = ::savePendingNoteChangesBeforeHistory,
            notifyNotesChanged = ::notifyNotesChanged,
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
    private val loadingPopup by lazy {
        PostCallLoadingPopup(
            service = this,
            ui = ui,
            phone = { phone },
            title = { title },
            subtitle = { subtitle },
            setWindowManager = { windowManager = it },
            setLoadingAnimator = { loadingAnimator = it },
            removeOverlay = ::removeOverlay,
            addDraggableOverlay = ::addDraggableOverlay,
            timeoutMs = LOADING_POPUP_TIMEOUT_MS,
        )
    }
    private val bubble by lazy {
        PostCallBubble(
            service = this,
            ui = ui,
            setWindowManager = { windowManager = it },
            setOverlayView = { overlayView = it },
            removeOverlay = ::removeOverlay,
            showNoteEditor = ::showNoteEditor,
        )
    }
    private val calendarActions by lazy {
        PostCallCalendarActions(
            service = this,
            phone = { phone },
            removeOverlay = ::removeOverlay,
            stopOverlay = { stopSelf() },
        )
    }
    private val navigationActions by lazy {
        PostCallNavigationActions(
            service = this,
            handler = handler,
            phone = { phone },
            title = { title },
            removeOverlay = ::removeOverlay,
            stopOverlay = { stopSelf() },
        )
    }
    private val windowController by lazy {
        PostCallOverlayWindowController(
            service = this,
            handler = handler,
            windowManager = { windowManager },
            setOverlayView = { overlayView = it },
            stopOverlay = { stopSelf() },
            dp = ::dp,
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
    private var pendingGeneralNote: String? = null
    private var pendingCallNote: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        readExtras(intent)
        hydrateLatestCallIfNeeded()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.getStringExtra(EXTRA_MODE).orEmpty()) {
            MODE_LOADING -> loadingPopup.show()
            MODE_LOOKUP -> lookupPopup.show()
            MODE_NOTE -> noteEditor.show()
            MODE_GENERAL_NOTE -> generalNoteEditor.show()
            else -> showBubbleWithTimeout()
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

    private fun readExtras(intent: Intent?) {
        formUrl = intent?.getStringExtra(EXTRA_FORM_URL).orEmpty()
        phone = intent?.getStringExtra(EXTRA_PHONE).orEmpty()
        direction = intent?.getStringExtra(EXTRA_DIRECTION).orEmpty()
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        subtitle = intent?.getStringExtra(EXTRA_SUBTITLE).orEmpty()
        lines = intent?.getStringArrayListExtra(EXTRA_LINES).orEmpty()
        callAt = intent?.getLongExtra(EXTRA_CALL_AT, 0L) ?: 0L
        durationSeconds = intent?.getLongExtra(EXTRA_DURATION, 0L) ?: 0L
    }

    private fun hydrateLatestCallIfNeeded() {
        if (callAt > 0L || phone.isBlank()) return
        PhoneCallReader.callsForPhone(this, phone, limit = 1).firstOrNull()?.let { call ->
            callAt = call.startedAt
            durationSeconds = call.durationSeconds
            if (direction.isBlank()) direction = call.direction
        }
    }

    private fun showBubbleWithTimeout() {
        bubble.show()
        val timeout = ConfigStore.load(this).postCallPromptTimeoutSeconds.coerceIn(3, 120)
        handler.postDelayed({ stopSelf() }, timeout * 1000L)
    }

    private fun showNoteEditor() {
        noteEditor.show()
    }

    private fun showGeneralNoteEditor() {
        generalNoteEditor.show()
    }

    private fun openCalendarEvent(displayName: String) {
        calendarActions.openCalendarEvent(displayName)
    }

    private fun addDraggableOverlay(view: View, focusable: Boolean, defaultY: Int, timeoutMs: Long) {
        windowController.addDraggableOverlay(view, focusable, defaultY, timeoutMs)
    }

    private fun callDirectionColor(directionValue: String): Int {
        return when (directionValue) {
            "out" -> Color.rgb(34, 197, 94)
            "in" -> Color.rgb(59, 130, 246)
            else -> Color.rgb(107, 114, 128)
        }
    }

    private fun openContactNotesScreen() {
        navigationActions.openContactNotesScreen()
    }

    private fun savePendingNoteChangesBeforeHistory(): Boolean {
        var saved = true

        pendingGeneralNote?.let { note ->
            saved = NotePersistence.saveOrDeleteGeneralNote(this, phone, note) && saved
        }

        pendingCallNote?.let { note ->
            saved = NotePersistence.saveOrDeleteCallNote(
                context = this,
                phoneNumber = phone,
                note = note,
                direction = direction,
                callAt = callAt,
                durationSeconds = durationSeconds,
            ) && saved
        }

        if (saved) {
            pendingGeneralNote = null
            pendingCallNote = null
            notifyNotesChanged()
        }

        return saved
    }

    private fun notifyNotesChanged() {
        sendBroadcast(Intent(ACTION_NOTES_CHANGED).setPackage(packageName))
    }

    private fun removeOverlay() {
        loadingAnimator?.cancel()
        loadingAnimator = null
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        overlayView = null
    }

    private fun dp(value: Int): Int = ui.dp(value)

    companion object {
        const val ACTION_NOTES_CHANGED = "com.onlineimoti.calllog.NOTES_CHANGED"
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
    }
}
