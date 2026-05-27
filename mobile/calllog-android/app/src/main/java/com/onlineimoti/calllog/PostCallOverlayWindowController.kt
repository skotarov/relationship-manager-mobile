package com.onlineimoti.calllog

import android.app.Service
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

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

    fun addDraggableOverlay(view: View, focusable: Boolean, defaultY: Int, timeoutMs: Long) {
        val prefs = service.getSharedPreferences(LOOKUP_POPUP_POSITION_PREFS, Service.MODE_PRIVATE)
        val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
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
                    windowManager()?.updateViewLayout(view, params)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - initialTouchX) + kotlin.math.abs(event.rawY - initialTouchY)
                    if (moved >= dp(8)) {
                        if (shouldDismissDraggedOverlay(params)) {
                            stopOverlay()
                        } else {
                            params.x = 0
                            params.y = params.y.coerceAtLeast(0)
                            windowManager()?.updateViewLayout(view, params)
                            prefs.edit().putInt(KEY_LOOKUP_POPUP_Y, params.y).remove(KEY_LOOKUP_POPUP_X).apply()
                        }
                    }
                    false
                }
                else -> false
            }
        }
        setOverlayView(view)
        windowManager()?.addView(view, params)
        if (timeoutMs > 0) handler.postDelayed({ stopOverlay() }, timeoutMs)
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
