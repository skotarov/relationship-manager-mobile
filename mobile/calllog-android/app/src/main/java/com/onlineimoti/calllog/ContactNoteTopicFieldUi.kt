package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Shared topic field used by full-screen and overlay note editors. */
internal class ContactNoteTopicFieldUi(
    private val context: Context,
    private val dp: (Int) -> Int,
) {
    fun create(
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
        onSpinnerReady: (Spinner) -> Unit,
    ): LinearLayout? {
        if (!state.visible) return null
        val spinner = Spinner(context)
        ContactNoteTopicSelector.bind(context, spinner, state, onSelected)
        onSpinnerReady(spinner)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedTopicSectionBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
            addView(TextView(context).apply {
                text = "Повод:"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(55, 65, 81))
            })
            addView(spinner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) })
        }
    }

    private fun roundedTopicSectionBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.rgb(209, 213, 219))
    }
}
