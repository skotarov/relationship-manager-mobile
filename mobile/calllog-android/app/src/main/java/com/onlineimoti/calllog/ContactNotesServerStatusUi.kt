package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal object ContactNotesServerStatusUi {
    /**
     * A tooltip-like loading chip that floats between the contact header and the
     * first section. Its negative margins keep the content position unchanged when
     * the chip appears or disappears.
     */
    fun create(activity: ContactNotesActivity, dp: (Int) -> Int, textValue: String): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (textValue.isBlank()) View.INVISIBLE else View.VISIBLE
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.rgb(51, 65, 85))
                cornerRadius = dp(13).toFloat()
            }
            setPadding(dp(9), 0, dp(11), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26),
            ).apply {
                gravity = Gravity.END
                topMargin = -dp(13)
                bottomMargin = -dp(13)
                marginEnd = dp(4)
            }
            addView(
                ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                },
                LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    marginEnd = dp(7)
                },
            )
            addView(TextView(activity).apply {
                text = textValue
                textSize = 11.5f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.WHITE)
            })
        }
}
