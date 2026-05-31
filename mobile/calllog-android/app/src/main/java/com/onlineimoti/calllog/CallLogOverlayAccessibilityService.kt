package com.onlineimoti.calllog

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent

class CallLogOverlayAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controller by lazy { CallLogOverlayButtonController(this) }
    private val refreshRunnable = Runnable { refreshOverlay() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshOverlaySoon()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshOverlaySoon()
        }
    }

    override fun onInterrupt() {
        controller.hide()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller.hide()
        super.onDestroy()
    }

    private fun refreshOverlaySoon() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 120L)
    }

    private fun refreshOverlay() {
        val settings = CallLogOverlaySettings.load(this)
        if (!settings.enabled || !CallLogOverlaySettings.isExpectedCallLogWindow(this)) {
            controller.hide()
            return
        }

        if (isDefaultDialerWindowInFront()) {
            controller.show(settings.position)
        } else {
            controller.hide()
        }
    }

    private fun isDefaultDialerWindowInFront(): Boolean {
        val defaultDialerPackage = (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage.orEmpty()
        if (defaultDialerPackage.isBlank()) return false
        return rootInActiveWindow?.packageName?.toString() == defaultDialerPackage
    }
}
