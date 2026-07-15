package com.onlineimoti.calllog

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

internal object ContactNotesServerStatusUi {
    /**
     * Occupies the header's existing lower 12dp padding through a negative margin.
     * It is always present, including when hidden, so refreshes never shift sections.
     */
    fun create(activity: ContactNotesActivity, dp: (Int) -> Int, textValue: String): TextView =
        TextView(activity).apply {
            text = textValue
            textSize = 11f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(100, 116, 139))
            visibility = if (textValue.isBlank()) View.INVISIBLE else View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(12),
            ).apply {
                topMargin = -dp(12)
            }
        }
}
