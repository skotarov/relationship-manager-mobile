package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Compact Home chips: yellow when a company note exists, gray when only a phase exists. */
internal class HomeCompanyScopeChipsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun create(labels: List<HomeCompanyScopeLabel>): HorizontalScrollView {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        labels.forEachIndexed { index, label ->
            row.addView(chip(label), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) marginStart = dp(4)
            })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = HorizontalScrollView.OVER_SCROLL_NEVER
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        }
    }

    private fun chip(label: HomeCompanyScopeLabel): TextView {
        val hasPhase = label.phase in 1..4
        val colors = if (label.hasGeneralNote) NoteUiStyle.General else GrayChipColors
        val textValue = buildString {
            append("[ ")
            if (hasPhase) append("● ")
            append(label.companyName)
            append(" ]")
        }
        val styledText = SpannableString(textValue).apply {
            if (hasPhase) {
                val dotIndex = textValue.indexOf('●')
                if (dotIndex >= 0) setSpan(
                    ForegroundColorSpan(phaseColor(label.phase)),
                    dotIndex,
                    dotIndex + 1,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return TextView(activity).apply {
            text = styledText
            contentDescription = label.companyName
            setTextColor(colors.text)
            textSize = 12f
            maxLines = 1
            setPadding(dp(7), dp(4), dp(7), dp(4))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
        }
    }

    private fun phaseColor(phase: Int): Int = when (phase) {
        1 -> Color.rgb(22, 163, 74)
        2 -> Color.rgb(37, 99, 235)
        3 -> Color.rgb(250, 204, 21)
        4 -> Color.rgb(220, 38, 38)
        else -> GrayChipColors.text
    }

    private object GrayChipColors {
        val background: Int = Color.rgb(241, 245, 249)
        val border: Int = Color.rgb(203, 213, 225)
        val text: Int = Color.rgb(71, 85, 105)
    }
}
