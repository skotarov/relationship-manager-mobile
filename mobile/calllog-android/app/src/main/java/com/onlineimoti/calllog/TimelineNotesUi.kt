package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.LinearLayout
import android.widget.TextView

/** Shared note cards for phone-call and SMS timeline rows. */
internal class TimelineNotesUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun addGeneralContactNote(
        column: LinearLayout,
        contactNote: String?,
        highlightQuery: String,
        visible: Boolean,
    ) {
        if (!visible || contactNote.isNullOrBlank()) return
        val colors = NoteUiStyle.General
        column.addView(TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(contactNote, highlightQuery, colors.text)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_note_lines, 0, 0, 0)
            compoundDrawablePadding = dp(4)
            setTextColor(colors.text)
            textSize = 12.5f
            maxLines = 2
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        })
    }

    /** Shows each yellow company-scoped main note after the ordinary local main note. */
    fun addCompanyGeneralNotes(
        column: LinearLayout,
        labels: List<HomeCompanyScopeLabel>?,
        highlightQuery: String,
        visible: Boolean,
    ) {
        if (!visible) return
        labels.orEmpty()
            .filter { it.generalNote.isNotBlank() }
            .forEach { label ->
                val colors = NoteUiStyle.General
                val companyName = label.companyName.ifBlank { label.companyId }
                val prefix = "$companyName: "
                val rawText = prefix + label.generalNote.trim()
                val styledText = SpannableString(
                    SearchTextHighlighter.highlightedText(rawText, highlightQuery, colors.text),
                ).apply {
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        prefix.length.coerceAtMost(length),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                column.addView(TextView(activity).apply {
                    text = styledText
                    val icon = activity.getDrawable(R.drawable.ic_cloud_note)?.apply {
                        setBounds(0, 0, dp(NOTE_ICON_SIZE_DP), dp(NOTE_ICON_SIZE_DP))
                    }
                    setCompoundDrawables(icon, null, null, null)
                    compoundDrawablePadding = dp(5)
                    setTextColor(colors.text)
                    textSize = 12.5f
                    maxLines = 3
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = roundedRect(colors.background, dp(9), colors.border, dp(1))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(5) }
                })
            }
    }

    fun addCallNote(
        column: LinearLayout,
        call: PhoneCallRecord,
        callNote: HomeCallNote?,
        highlightQuery: String,
        statusForCall: (PhoneCallRecord) -> String?,
    ) {
        val note = callNote?.takeIf { it.text.isNotBlank() } ?: return
        val colors = NoteUiStyle.Call
        column.addView(TextView(activity).apply {
            text = SearchTextHighlighter.highlightedText(note.text, highlightQuery, colors.text)
            val iconRes = if (ServerRecordIndex.isCallNoteConfirmed(activity, call.number, call.startedAt, call.direction)) {
                R.drawable.ic_cloud_note
            } else {
                R.drawable.ic_chat_note
            }
            val icon = activity.getDrawable(iconRes)?.apply {
                setBounds(0, 0, dp(NOTE_ICON_SIZE_DP), dp(NOTE_ICON_SIZE_DP))
            }
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = dp(5)
            setTextColor(colors.text)
            textSize = 12.5f
            maxLines = 3
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = roundedRect(colors.background, dp(9), colors.border, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
        })
        statusForCall(call)?.let { status ->
            column.addView(TextView(activity).apply {
                text = status
                textSize = 11.5f
                setTextColor(Color.rgb(146, 64, 14))
                setPadding(dp(8), dp(4), dp(8), 0)
            })
        }
    }

    private companion object {
        const val NOTE_ICON_SIZE_DP = 18
    }
}
