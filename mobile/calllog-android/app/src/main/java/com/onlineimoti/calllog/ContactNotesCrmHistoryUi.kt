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
    headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    private val cards = ContactNotesCrmHistoryCards(activity, headerUi, dp, roundedRect)

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
                addView(cards.latestCallAction(item.call, onEditCallNote))
            is ContactNotesTimelineItem.LocalNote -> addView(cards.localNote(item.note, onEditCallNote))
            is ContactNotesTimelineItem.SmsMessage -> addView(cards.smsMessage(item.sms))
            is ContactNotesTimelineItem.ServerNote -> addView(cards.serverNote(item.note))
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

    private fun statusText(value: String): TextView {
        return TextView(activity).apply {
            text = value
            textSize = 13.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }
}
