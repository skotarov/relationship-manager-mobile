package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Compact Home row for CRM status and company scopes. */
internal class HomeCompanyScopeChipsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun create(
        labels: List<HomeCompanyScopeLabel>?,
        crmClient: Boolean,
        onClick: (() -> Unit)? = null,
    ): HorizontalScrollView {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        var hasPrevious = false
        if (crmClient) {
            row.addView(crmLabel())
            hasPrevious = true
        }
        labels.orEmpty().forEach { label ->
            row.addView(chip(label), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (hasPrevious) marginStart = dp(6)
            })
            hasPrevious = true
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                contentDescription = "Отвори историята на контакта"
                setOnClickListener { onClick() }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        }
    }

    private fun crmLabel(): TextView = TextView(activity).apply {
        text = "CRM"
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(7), dp(4), dp(7), dp(4))
        background = roundedRect(
            activity.getColor(R.color.callreport_icon_background),
            dp(9),
            Color.TRANSPARENT,
            0,
        )
        contentDescription = "CRM"
    }

    private fun chip(label: HomeCompanyScopeLabel): TextView {
        val hasPhase = label.phase in 1..4
        val colors = if (label.hasGeneralNote) {
            ChipColors(
                background = NoteUiStyle.General.background,
                border = NoteUiStyle.General.border,
                text = NoteUiStyle.General.text,
            )
        } else {
            GrayChipColors
        }
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
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
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

    private data class ChipColors(
        val background: Int,
        val border: Int,
        val text: Int,
    )

    private val GrayChipColors = ChipColors(
        background = Color.rgb(241, 245, 249),
        border = Color.TRANSPARENT,
        text = Color.rgb(71, 85, 105),
    )
}
