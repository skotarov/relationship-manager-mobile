package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal sealed class ContactNotesTimelineItem(val timeMs: Long) {
    class LatestCallAction(val call: PhoneCallRecord) : ContactNotesTimelineItem(call.startedAt)
    class LocalNote(val note: ContactCallNote) :
        ContactNotesTimelineItem(note.callAt.takeIf { it > 0L } ?: note.savedAt)
    class SmsMessage(val sms: SmsMessageRecord) : ContactNotesTimelineItem(sms.timestampMs)
    class ServerNote(val note: CrmServerNote, timeMs: Long) : ContactNotesTimelineItem(timeMs)
}

internal data class ContactNotesHistoryUiState(
    val localLoaded: Boolean,
    val localLoading: Boolean,
    val serverLoading: Boolean,
    val error: Boolean,
    val skippedReason: String,
    val serverNotesEmpty: Boolean,
)

internal class ContactNotesCrmHistoryUi(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun addSection(
        root: LinearLayout,
        timeline: List<ContactNotesTimelineItem>,
        hiddenCallsWithoutNotes: Int,
        state: ContactNotesHistoryUiState,
        openFilteredLog: () -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }

            addView(historyTitleRow(openFilteredLog))
            timeline.forEach { item -> addTimelineCard(item, onEditCallNote) }
            addStatusIfNeeded(this, timeline, hiddenCallsWithoutNotes.coerceAtLeast(0), state)
        })
    }

    private fun historyTitleRow(openFilteredLog: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(8))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_system_call_log)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            })
            addView(TextView(activity).apply {
                text = "Хронология"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(30, 41, 59))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(filteredLogTitleAction(openFilteredLog))
        }
    }

    private fun filteredLogTitleAction(openFilteredLog: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, 0, 0)
            setOnClickListener { openFilteredLog() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_call_log_filter)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(21), dp(21)).apply { marginEnd = dp(4) }
            })
            addView(TextView(activity).apply {
                text = "пълен лог"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(30, 64, 175))
                maxLines = 1
            })
        }
    }

    private fun LinearLayout.addTimelineCard(
        item: ContactNotesTimelineItem,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        when (item) {
            is ContactNotesTimelineItem.LatestCallAction ->
                addView(latestCallActionCard(item.call, onEditCallNote))
            is ContactNotesTimelineItem.LocalNote -> addView(localNoteCard(item.note, onEditCallNote))
            is ContactNotesTimelineItem.SmsMessage -> addView(smsMessageCard(item.sms))
            is ContactNotesTimelineItem.ServerNote -> addView(serverNoteCard(item.note))
        }
    }

    private fun addStatusIfNeeded(
        container: LinearLayout,
        timeline: List<ContactNotesTimelineItem>,
        hiddenCallsWithoutNotes: Int,
        state: ContactNotesHistoryUiState,
    ) {
        if (!state.localLoaded) {
            container.addView(statusText("Зареждам разговори и SMS…"))
        } else if (state.localLoading) {
            container.addView(statusText("Обновявам разговори и SMS…"))
        }
        if (state.serverLoading) container.addView(statusText("Зареждам CRM история…"))
        if (state.error) container.addView(statusText("CRM историята не е заредена"))
        if (state.skippedReason.isNotBlank()) container.addView(statusText(state.skippedReason))
        if (
            state.localLoaded &&
            timeline.isEmpty() &&
            hiddenCallsWithoutNotes <= 0 &&
            !state.serverLoading &&
            !state.error &&
            state.skippedReason.isBlank()
        ) {
            container.addView(statusText("Няма бележки, SMS или CRM записи за този номер"))
        } else if (
            state.localLoaded &&
            state.serverNotesEmpty &&
            !state.serverLoading &&
            !state.error &&
            state.skippedReason.isBlank()
        ) {
            container.addView(statusText("Няма CRM записи от сървъра за този номер"))
        }
        if (hiddenCallsWithoutNotes > 0) {
            container.addView(statusText(
                "Скрити са $hiddenCallsWithoutNotes по-стари разговора без бележка. " +
                    "Всички позвънявания се виждат на началния екран.",
            ))
        }
    }

    private fun latestCallActionCard(
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

    private fun localNoteCard(
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

    private fun smsMessageCard(sms: SmsMessageRecord): LinearLayout {
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

    private fun serverNoteCard(note: CrmServerNote): LinearLayout {
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

    private fun statusText(value: String): TextView {
        return TextView(activity).apply {
            text = value
            textSize = 13.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(12), dp(10), dp(12), dp(10))
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
