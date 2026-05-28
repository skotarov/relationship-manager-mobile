package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

internal class ContactNotesCards(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> android.graphics.drawable.GradientDrawable,
    private val directionArrowLabel: (String) -> String,
) {
    fun generalNoteCard(textValue: String, muted: Boolean, onClick: () -> Unit): TextView {
        val colors = NoteUiStyle.General
        val backgroundColor = if (muted) Color.WHITE else colors.background
        val borderColor = if (muted) Color.rgb(226, 232, 240) else colors.border
        return TextView(activity).apply {
            text = textValue
            textSize = 14.5f
            setTextColor(if (muted) colors.mutedText else colors.text)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(backgroundColor, dp(12), borderColor, dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    fun callNoteCard(note: ContactCallNote, onClick: () -> Unit): LinearLayout {
        val colors = NoteUiStyle.Call
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(colors.background, dp(12), colors.border, dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(note.callAt.takeIf { it > 0L } ?: note.savedAt),
                    directionArrowLabel(note.direction),
                    PhoneCallReader.formatDuration(note.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(colors.metaText)
            })
            addView(TextView(activity).apply {
                text = note.note
                textSize = 14.5f
                setTextColor(colors.text)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }
}
