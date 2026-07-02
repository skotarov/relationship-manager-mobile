package com.onlineimoti.calllog

import android.app.Service
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText

internal class PostCallOverlayWindowController(
    private val service: Service,
    private val handler: android.os.Handler,
    private val windowManager: () -> WindowManager?,
    private val setOverlayView: (View) -> Unit,
    private val stopOverlay: () -> Unit,
    private val dp: (Int) -> Int,
) {
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragAllowedForGesture = false
    private var timeoutRunnable: Runnable? = null

    fun addDraggableOverlay(
        view: View,
        focusable: Boolean,
        defaultY: Int,
        timeoutMs: Long,
        onTimeout: () -> Unit = stopOverlay,
    ) {
        cancelTimeout()
        val prefs = service.getSharedPreferences(LOOKUP_POPUP_POSITION_PREFS, Service.MODE_PRIVATE)
        val flags = if (focusable) {
            0
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        val popupWidth = service.resources.displayMetrics.widthPixels - dp(32)
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
            if (focusable) {
                softInputMode = LegacyPlatformCompat.resizeInputMode()
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragAllowedForGesture = !isTouchInsideEditableText(view, event.rawX, event.rawY)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragAllowedForGesture) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager()?.updateViewLayout(view, params)
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (dragAllowedForGesture) {
                        val moved = kotlin.math.abs(event.rawX - initialTouchX) + kotlin.math.abs(event.rawY - initialTouchY)
                        if (moved >= dp(8)) {
                            if (shouldDismissDraggedOverlay(params)) {
                                cancelTimeout()
                                onTimeout()
                            } else {
                                params.x = 0
                                params.y = params.y.coerceAtLeast(0)
                                windowManager()?.updateViewLayout(view, params)
                                prefs.edit().putInt(KEY_LOOKUP_POPUP_Y, params.y).remove(KEY_LOOKUP_POPUP_X).apply()
                            }
                        }
                    }
                    dragAllowedForGesture = false
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragAllowedForGesture = false
                    false
                }
                else -> false
            }
        }
        setOverlayView(view)
        windowManager()?.addView(view, params)
        if (timeoutMs > 0) {
            timeoutRunnable = Runnable {
                timeoutRunnable = null
                onTimeout()
            }.also { handler.postDelayed(it, timeoutMs) }
        }
    }

    fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun isTouchInsideEditableText(root: View, rawX: Float, rawY: Float): Boolean {
        if (!root.isShown) return false
        if (root is EditText && isPointInsideView(root, rawX, rawY)) return true
        if (root is ViewGroup) {
            for (index in root.childCount - 1 downTo 0) {
                if (isTouchInsideEditableText(root.getChildAt(index), rawX, rawY)) return true
            }
        }
        return false
    }

    private fun isPointInsideView(view: View, rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + view.width
        val bottom = top + view.height
        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }

    private fun shouldDismissDraggedOverlay(params: WindowManager.LayoutParams): Boolean {
        val screenWidth = service.resources.displayMetrics.widthPixels
        val screenHeight = service.resources.displayMetrics.heightPixels
        return kotlin.math.abs(params.x) > screenWidth / 4 || params.y < -dp(110) || params.y > screenHeight - dp(80)
    }

    companion object {
        private const val LOOKUP_POPUP_POSITION_PREFS = "lookup_popup_position"
        private const val KEY_LOOKUP_POPUP_X = "lookup_popup_x"
        private const val KEY_LOOKUP_POPUP_Y = "lookup_popup_y"
    }
}
