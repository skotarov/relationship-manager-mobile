package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** Shared destination field used by full-screen and overlay note editors. */
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

        val field = LinearLayout(context).apply {
            tag = FIELD_TAG
            orientation = LinearLayout.VERTICAL
            background = roundedTopicSectionBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
        }
        val spinner = Spinner(context)
        field.addView(TextView(context).apply {
            text = if (state.localOnly) "Бележката се пази само локално:" else "Къде да се запише бележката:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(55, 65, 81))
        })
        field.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(5) })

        // Bind only after the spinner has a tagged parent, so the shared selector
        // can update the validation border immediately on every choice.
        ContactNoteTopicSelector.bind(context, spinner, state, onSelected)
        onSpinnerReady(spinner)
        return field
    }

    private fun roundedTopicSectionBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.rgb(209, 213, 219))
    }

    companion object {
        const val FIELD_TAG = "callreport_topic_field"
    }
}
