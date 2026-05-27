package com.onlineimoti.calllog

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

internal class PostCallLoadingPopup(
    private val service: Service,
    private val ui: PostCallOverlayUi,
    private val phone: () -> String,
    private val title: () -> String,
    private val subtitle: () -> String,
    private val setWindowManager: (WindowManager) -> Unit,
    private val setLoadingAnimator: (ObjectAnimator?) -> Unit,
    private val removeOverlay: () -> Unit,
    private val addDraggableOverlay: (View, Boolean, Int, Long) -> Unit,
    private val timeoutMs: Long,
) {
    fun show() {
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            ui.stylePopupCard(this)
        }
        val spinner = TextView(service).apply {
            text = "↻"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 65, 81))
            layoutParams = LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { marginEnd = ui.dp(10) }
        }
        card.addView(spinner)
        val textColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(service).apply {
            text = title().ifBlank { phone().ifBlank { "Call Report" } }
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(17, 24, 39))
        })
        textColumn.addView(TextView(service).apply {
            text = subtitle().ifBlank { "Зареждат се данни…" }
            textSize = 14f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, ui.dp(2), 0, 0)
        })
        card.addView(textColumn)

        setLoadingAnimator(
            ObjectAnimator.ofFloat(spinner, View.ROTATION, 0f, 360f).apply {
                duration = 850L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        )
        addDraggableOverlay(ui.shadowScroll(card), false, ui.dp(135), timeoutMs)
    }
}
