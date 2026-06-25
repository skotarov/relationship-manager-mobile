package com.onlineimoti.calllog

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeSummaryViewRenderer(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
) {
    fun render(title: String, detail: String) {
        val container = binding.filteredContactSummaryContainer
        container.removeAllViews()
        if (title.isBlank() && detail.isBlank()) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        if (title.isNotBlank()) {
            container.addView(TextView(activity).apply {
                text = title
                setTextColor(activity.getColor(R.color.calllog_text))
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(2), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(4) }
            })
        }
        if (detail.isNotBlank()) {
            val colors = NoteUiStyle.General
            container.addView(TextView(activity).apply {
                text = detail
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0)
                compoundDrawablePadding = dp(5)
                setTextColor(colors.text)
                textSize = 13f
                maxLines = 4
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = homeRoundedRect(colors.background, dp(10), colors.border, dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
        }
    }
}
