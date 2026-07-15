package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

internal class ContactNotesCrmHistoryCards(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun latestCallAction(
        call: PhoneCallRecord,
        onEditCallNote: (ContactCallNote) -> Unit,
    ): LinearLayout {
        val startedAtText = PhoneCallReader.formatStartedAt(call.startedAt)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onEditCallNote(call.toContactCallNote()) }
            layoutParams = cardLayoutParams()
            addView(TextView(activity).apply {
                text = if (startedAtText.isNotBlank()) {
                    "+ Добави бележка към $startedAtText"
                } else {
                    "+ Добави бележка към последния разговор"
                }
                textSize = 14.5f
                setTextColor(NoteUiStyle.Call.mutedText)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = listOf(
                    headerUi.directionArrowLabel(call.direction),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                    "последен разговор без бележка",
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    fun localNote(
        note: ContactCallNote,
        onEditCallNote: (ContactCallNote) -> Unit,
    ): LinearLayout {
        val colors = NoteUiStyle.Call
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(colors.background, dp(12), colors.border, dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { onEditCallNote(note) }
            layoutParams = cardLayoutParams()
            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(note.callAt.takeIf { it > 0L } ?: note.savedAt),
                    headerUi.directionArrowLabel(note.direction),
                    PhoneCallReader.formatDuration(note.durationSeconds),
                    "локална бележка",
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(colors.metaText)
            })
            addView(TextView(activity).apply {
                text = note.note
                textSize = 14.5f
                setTextColor(colors.text)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    fun smsMessage(sms: SmsMessageRecord): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.WHITE, dp(12), Color.rgb(226, 232, 240), dp(1))
            layoutParams = cardLayoutParams()
            addView(TextView(activity).apply {
                text = listOf(
                    "SMS",
                    PhoneCallReader.formatStartedAt(sms.timestampMs),
                    sms.directionLabel,
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(Color.rgb(71, 85, 105))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = sms.body.ifBlank { "(SMS без текст)" }
                textSize = 14.5f
                setTextColor(Color.rgb(30, 41, 59))
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    fun serverNote(note: CrmServerNote): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(
                Color.rgb(248, 250, 252),
                dp(12),
                Color.rgb(203, 213, 225),
                dp(1),
            )
            layoutParams = cardLayoutParams()
            addView(TextView(activity).apply {
                text = metaText(note)
                textSize = 12.5f
                setTextColor(Color.rgb(71, 85, 105))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = note.text
                textSize = 14.5f
                setTextColor(Color.rgb(51, 65, 85))
                setPadding(0, dp(5), 0, 0)
            })
            if (note.propertyTitle.isRealValue()) {
                addView(TextView(activity).apply {
                    text = "Обява: ${note.propertyTitle}"
                    textSize = 12.5f
                    setTextColor(Color.rgb(100, 116, 139))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun metaText(note: CrmServerNote): String {
        val author = note.authorName.ifBlank { note.authorLogin }.ifBlank { note.authorId }
        return listOf("CRM", author, note.createdAt)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
    }

    private fun PhoneCallRecord.toContactCallNote(): ContactCallNote {
        return ContactCallNote(
            note = "",
            callAt = startedAt,
            savedAt = startedAt,
            direction = direction,
            durationSeconds = durationSeconds,
            clientNoteId = LocalNotesFileStore.clientNoteIdForCall(number, startedAt, direction),
        )
    }

    private fun String.isRealValue(): Boolean {
        val value = trim()
        return value.isNotBlank() && !value.equals("null", ignoreCase = true)
    }
}
