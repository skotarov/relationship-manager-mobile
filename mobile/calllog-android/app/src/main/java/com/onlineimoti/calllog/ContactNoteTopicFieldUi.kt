package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
            setPadding(0, dp(12), 0, 0)
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
}
