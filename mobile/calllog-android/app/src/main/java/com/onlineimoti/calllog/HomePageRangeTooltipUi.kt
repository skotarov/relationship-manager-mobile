package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Shows automatic paging progress above Home without taking any layout space. */
internal class HomePageRangeTooltipUi(
    private val binding: ActivityHomeBinding,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var popup: PopupWindow? = null
    private var lastShownPageCount = 0
    private var requestGeneration = 0
    private val dismissRunnable = Runnable { dismissPopup() }

    fun show(pageCount: Int) {
        if (pageCount <= 1 || pageCount == lastShownPageCount) return
        val expectedGeneration = requestGeneration
        binding.root.post {
            if (expectedGeneration != requestGeneration) return@post
            if (!binding.root.isAttachedToWindow || binding.root.windowToken == null) return@post
            if (pageCount == lastShownPageCount) return@post
            lastShownPageCount = pageCount
            dismissPopup()
            val context = binding.root.context
            val content = TextView(context).apply {
                text = HomePageRangeTooltipText.format(pageCount, AppLocaleText.isBulgarian())
                textSize = 11.5f
                includeFontPadding = false
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(11), 0, dp(11), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(51, 65, 85))
                    cornerRadius = dp(13).toFloat()
                }
            }
            popup = PopupWindow(
                content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(26),
                false,
            ).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = false
                isClippingEnabled = true
                elevation = dp(8).toFloat()
                showAtLocation(binding.root, Gravity.TOP or Gravity.END, dp(16), dp(72))
            }
            handler.removeCallbacks(dismissRunnable)
            handler.postDelayed(dismissRunnable, DISPLAY_MS)
        }
    }

    fun reset() {
        requestGeneration += 1
        lastShownPageCount = 0
        dismissPopup()
    }

    fun release() {
        reset()
        handler.removeCallbacksAndMessages(null)
    }

    private fun dismissPopup() {
        handler.removeCallbacks(dismissRunnable)
        popup?.dismiss()
        popup = null
    }

    private fun dp(value: Int): Int =
        (value * binding.root.resources.displayMetrics.density).toInt()

    private companion object {
        const val DISPLAY_MS = 1_500L
    }
}

internal object HomePageRangeTooltipText {
    fun format(pageCount: Int, bulgarian: Boolean): String =
        if (bulgarian) "Страници 1–$pageCount" else "Pages 1–$pageCount"
}
