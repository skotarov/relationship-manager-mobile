package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

internal class HomeCallRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val noteKey: (String) -> String,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotesScreen: (PhoneCallRecord, String) -> Unit,
    private val openDialer: (String) -> Unit,
    private val togglePhoneFilter: (String) -> Unit,
    private val openContactNotePopupForCall: (PhoneCallRecord, String) -> Unit,
) {
    fun compactCallRow(
        call: PhoneCallRecord,
        displayName: String,
        contactNote: String? = null,
        callNote: String? = null,
    ): MaterialCardView {
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(activity.getColor(R.color.calllog_border))
            setCardBackgroundColor(activity.getColor(R.color.calllog_surface))
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { openContactNotesScreen(call, displayName) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        row.addView(TextView(activity).apply {
            text = callIcon(call)
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(callIconColor(call))
            layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        })

        val textColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(activity).apply {
            val hasContactName = displayName.isNotBlank() && noteKey(displayName) != noteKey(call.number)
            text = listOf(
                PhoneCallReader.formatStartedAt(call.startedAt),
                PhoneCallReader.formatDuration(call.durationSeconds),
                call.number.takeIf { hasContactName },
            ).filter { !it.isNullOrBlank() }.joinToString(" • ")
            setTextColor(activity.getColor(R.color.calllog_muted_text))
            textSize = 12.5f
            maxLines = 1
        })
        textColumn.addView(TextView(activity).apply {
            text = displayName
            setTextColor(activity.getColor(R.color.calllog_text))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        })
        if (!contactNote.isNullOrBlank()) {
            val colors = NoteUiStyle.General
            textColumn.addView(TextView(activity).apply {
                text = contactNote
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0)
                compoundDrawablePadding = dp(4)
                setTextColor(colors.text)
                textSize = 12.5f
                maxLines = 2
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = roundedRect(colors.background, dp(9), colors.border, dp(1))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
        }
        if (!callNote.isNullOrBlank()) {
            textColumn.addView(TextView(activity).apply {
                text = callNote
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_chat_note, 0, 0, 0)
                compoundDrawablePadding = dp(5)
                setTextColor(Color.rgb(7, 89, 133))
                textSize = 12.5f
                maxLines = 3
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = roundedRect(Color.rgb(224, 246, 255), dp(9), Color.rgb(125, 211, 252), dp(1))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) }
            })
        }
        row.addView(textColumn)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(3) }
        }
        actions.addView(iconButton(R.drawable.ic_phone_call, "Обади се") { openDialer(call.number) })
        actions.addView(iconButton(R.drawable.ic_filter_calls, "Филтър") { togglePhoneFilter(call.number) })
        actions.addView(iconButton(R.drawable.ic_chat_note, "Бележка") { openContactNotePopupForCall(call, displayName) })
        row.addView(actions)

        card.addView(row)
        return card
    }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
            setOnClickListener { action() }
        }
    }

    private fun callIcon(call: PhoneCallRecord): String {
        return if (call.direction == "out") "↗" else "↙"
    }

    private fun callIconColor(call: PhoneCallRecord): Int {
        if (call.durationSeconds <= 0) return Color.rgb(239, 68, 68)
        return when (call.direction) {
            "out" -> Color.rgb(34, 197, 94)
            else -> Color.rgb(59, 130, 246)
        }
    }
}
