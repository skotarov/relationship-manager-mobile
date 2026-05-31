package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView

internal class CallLogOverlayButtonController(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var buttonView: View? = null
    private var shownPosition: String = ""

    fun show(position: String) {
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        val normalizedPosition = CallLogOverlaySettings.normalizePosition(position)
        if (buttonView != null && shownPosition == normalizedPosition) return
        hide()

        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager
        shownPosition = normalizedPosition

        val size = dp(48)
        val button = ImageButton(context).apply {
            setImageResource(R.drawable.ic_chat_note)
            contentDescription = "Call Report"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(75, 85, 99))
            }
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(8).toFloat()
            translationZ = dp(2).toFloat()
            setOnClickListener { openAppHome() }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = gravityFor(normalizedPosition)
            x = dp(16)
            y = yOffsetFor(normalizedPosition)
        }

        buttonView = button
        runCatching { manager.addView(button, params) }
            .onFailure { buttonView = null }
    }

    fun hide() {
        val view = buttonView ?: return
        runCatching { windowManager?.removeView(view) }
        buttonView = null
        shownPosition = ""
    }

    private fun openAppHome() {
        context.startActivity(
            Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun gravityFor(position: String): Int {
        val vertical = if (position == CallLogOverlaySettings.POSITION_BOTTOM_END || position == CallLogOverlaySettings.POSITION_BOTTOM_START) {
            Gravity.BOTTOM
        } else {
            Gravity.TOP
        }
        val horizontal = if (position == CallLogOverlaySettings.POSITION_TOP_START || position == CallLogOverlaySettings.POSITION_BOTTOM_START) {
            Gravity.START
        } else {
            Gravity.END
        }
        return vertical or horizontal
    }

    private fun yOffsetFor(position: String): Int {
        return if (position == CallLogOverlaySettings.POSITION_BOTTOM_END || position == CallLogOverlaySettings.POSITION_BOTTOM_START) {
            dp(96)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) dp(104) else dp(92)
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
