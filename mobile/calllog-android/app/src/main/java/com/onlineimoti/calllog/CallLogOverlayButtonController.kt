package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import kotlin.math.abs

internal data class CallLogOverlayTarget(
    val phone: String = "",
    val title: String = "",
)

internal class CallLogOverlayButtonController(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var buttonView: ImageButton? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var shownPosition: String = ""
    private var currentTarget: CallLogOverlayTarget = CallLogOverlayTarget()

    fun show(position: String, target: CallLogOverlayTarget = CallLogOverlayTarget()) {
        currentTarget = target
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        val normalizedPosition = CallLogOverlaySettings.normalizePosition(position)
        buttonView?.let { existingButton ->
            existingButton.setImageResource(iconForTarget(target))
            existingButton.contentDescription = descriptionForTarget(target)
            return
        }
        hide()

        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager
        shownPosition = normalizedPosition

        val size = dp(48)
        val button = ImageButton(context).apply {
            setImageResource(iconForTarget(target))
            contentDescription = descriptionForTarget(target)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(75, 85, 99))
            }
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(8).toFloat()
            translationZ = dp(2).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = CallLogOverlaySettings.loadSavedPosition(context)
            if (saved != null) {
                x = saved.x
                y = saved.y
            } else {
                val fallback = fallbackPoint(normalizedPosition, size)
                x = fallback.x
                y = fallback.y
            }
        }

        button.setOnTouchListener(DragTouchListener(params, size))
        buttonView = button
        layoutParams = params
        runCatching { manager.addView(button, params) }
            .onFailure {
                buttonView = null
                layoutParams = null
            }
    }

    fun hide() {
        val view = buttonView ?: return
        runCatching { windowManager?.removeView(view) }
        buttonView = null
        layoutParams = null
        shownPosition = ""
        currentTarget = CallLogOverlayTarget()
    }

    private fun openTarget() {
        val target = currentTarget
        if (target.phone.isNotBlank()) {
            context.startActivity(
                ExternalLaunchNavigation.apply(
                    Intent(context, ContactNotesActivity::class.java)
                        .putExtra(ContactNotesActivity.EXTRA_PHONE, target.phone)
                        .putExtra(ContactNotesActivity.EXTRA_TITLE, target.title.ifBlank { target.phone })
                )
            )
            return
        }
        context.startActivity(
            ExternalLaunchNavigation.apply(Intent(context, HomeActivity::class.java))
        )
    }

    private fun iconForTarget(target: CallLogOverlayTarget): Int {
        return if (target.phone.isBlank()) R.drawable.ic_call_log_bubbles else R.drawable.ic_chat_note
    }

    private fun descriptionForTarget(target: CallLogOverlayTarget): String {
        return if (target.phone.isBlank()) "Call Log" else "История"
    }

    private inner class DragTouchListener(
        private val params: WindowManager.LayoutParams,
        private val viewSize: Int,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragged = true
                    params.x = (startX + dx.toInt()).coerceIn(0, maxX(viewSize))
                    params.y = (startY + dy.toInt()).coerceIn(0, maxY(viewSize))
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        params.x = params.x.coerceIn(0, maxX(viewSize))
                        params.y = params.y.coerceIn(0, maxY(viewSize))
                        CallLogOverlaySettings.saveDraggedPosition(context, params.x, params.y)
                    } else {
                        view.performClick()
                        openTarget()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun fallbackPoint(position: String, size: Int): Point {
        val margin = dp(16)
        val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dp(104) else dp(92)
        val bottom = dp(96)
        val x = if (position == CallLogOverlaySettings.POSITION_TOP_START || position == CallLogOverlaySettings.POSITION_BOTTOM_START) {
            margin
        } else {
            maxX(size) - margin
        }
        val y = if (position == CallLogOverlaySettings.POSITION_BOTTOM_END || position == CallLogOverlaySettings.POSITION_BOTTOM_START) {
            maxY(size) - bottom
        } else {
            top
        }
        return Point(x.coerceIn(0, maxX(size)), y.coerceIn(0, maxY(size)))
    }

    private fun screenSize(): Point {
        val manager = windowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = manager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            Point().also { manager.defaultDisplay.getSize(it) }
        }
    }

    private fun maxX(viewSize: Int): Int = (screenSize().x - viewSize).coerceAtLeast(0)
    private fun maxY(viewSize: Int): Int = (screenSize().y - viewSize).coerceAtLeast(0)
    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
