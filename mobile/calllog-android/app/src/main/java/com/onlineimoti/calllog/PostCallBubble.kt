package com.onlineimoti.calllog

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView

internal class PostCallBubble(
    private val service: Service,
    private val ui: PostCallOverlayUi,
    private val setWindowManager: (WindowManager) -> Unit,
    private val setOverlayView: (View) -> Unit,
    private val removeOverlay: () -> Unit,
    private val openConfiguredAction: () -> Unit,
) {
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var windowManager: WindowManager? = null

    fun show() {
        removeOverlay()
        windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setWindowManager(windowManager!!)
        val size = ui.dp(46)
        val bubble = ImageButton(service).apply {
            setImageResource(R.drawable.ic_chat_note)
            contentDescription = service.getString(R.string.overlay_post_call_action)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(ui.dp(1), Color.rgb(75, 85, 99))
            }
            scaleType = ImageView.ScaleType.CENTER
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            elevation = ui.dp(6).toFloat()
            translationZ = ui.dp(2).toFloat()
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = ui.dp(18)
            y = ui.dp(110)
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
                    if (moved < ui.dp(8)) openConfiguredAction()
                    true
                }
                else -> false
            }
        }
        setOverlayView(bubble)
        windowManager?.addView(bubble, params)
    }
}
