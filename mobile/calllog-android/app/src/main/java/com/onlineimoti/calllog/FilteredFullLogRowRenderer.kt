package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

internal data class FilteredFullLogEntry(
    val row: CallReportHistoryRow,
    val attachedNotes: List<CallReportHistoryRow> = emptyList(),
)

/** Renders local and server rows for one contact's filtered full history. */
internal class FilteredFullLogRowRenderer(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotes: (PhoneCallRecord, String) -> Unit,
    private val openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
) {
    private val metadataUi by lazy { FilteredFullLogMetadataUi(activity, dp) }

    fun rowView(phone: String, entry: FilteredFullLogEntry, remoteEnabled: Boolean): MaterialCardView {
        val row = entry.row
        if (row.kind == CallReportHistoryRowKind.SMS) return smsRowView(entry, remoteEnabled)
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val localCall = row.localCall
        val localNote = row.localNote
        val editableAttachedNote = if (foreignRecord) null else {
            entry.attachedNotes.firstOrNull { it.localNote != null && it.editable && !it.authorIsOtherBroker }
        }
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(when {
                foreignRecord -> FilteredFullLogStyle.foreignBorder
                row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.border
                else -> activity.getColor(R.color.calllog_border)
            })
            setCardBackgroundColor(when {
                foreignRecord -> FilteredFullLogStyle.foreignBackground
                row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.background
                else -> activity.getColor(R.color.calllog_surface)
            })
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val column = baseColumn(row, remoteEnabled, foreignRecord)
        entry.attachedNotes.forEach { note -> column.addView(attachedNoteView(phone, note, remoteEnabled)) }
        bindCardAction(card, phone, row, localCall, localNote, foreignRecord)
        if (!foreignRecord && row.kind == CallReportHistoryRowKind.PHONE && localCall != null) {
            card.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                column.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(column)
                addView(noteActionButton(localCall, editableAttachedNote))
            })
        } else {
            card.addView(column)
        }
        return card
    }

    private fun baseColumn(row: CallReportHistoryRow, remoteEnabled: Boolean, foreignRecord: Boolean): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(metadataUi.metaView(row, remoteEnabled))
            if (row.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = row.text
                    textSize = 14.5f
                    setTextColor(when {
                        foreignRecord -> FilteredFullLogStyle.foreignText
                        row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.text
                        else -> activity.getColor(R.color.calllog_text)
                    })
                    if (row.kind == CallReportHistoryRowKind.NOTE) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(5), 0, 0)
                })
            }
            if (remoteEnabled) {
                metadataUi.addServerAuthor(this, row)
                metadataUi.addServerVersionNotice(this, row)
            }
        }
    }

    private fun bindCardAction(
        card: MaterialCardView,
        phone: String,
        row: CallReportHistoryRow,
        localCall: PhoneCallRecord?,
        localNote: ContactCallNote?,
        foreignRecord: Boolean,
    ) {
        when {
            !foreignRecord && row.kind == CallReportHistoryRowKind.NOTE && localNote != null && row.editable -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openNoteEditor(phone, localNote.withServerClientEventId(row.serverEvent?.clientEventId.orEmpty())) }
            }
            !foreignRecord && row.kind == CallReportHistoryRowKind.PHONE && localCall != null -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openContactNotes(localCall, localCall.displayName) }
            }
        }
    }

    private fun smsRowView(entry: FilteredFullLogEntry, remoteEnabled: Boolean): MaterialCardView {
        val row = entry.row
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val displayName = ContactGroupFilter.resolveDisplayName(activity, row.phone).orEmpty()
        val sms = PhoneCallRecord(
            number = row.phone,
            name = displayName,
            direction = if (row.direction == "out") "sms_out" else "sms_in",
            startedAt = row.timeMs,
            durationSeconds = 0L,
            smsBody = row.text,
            providerId = row.localSms?.providerId.orEmpty(),
        )
        return SmsTimelineCard.create(
            activity = activity,
            dp = dp,
            message = sms,
            displayName = sms.displayName,
            colors = SmsTimelineCard.Colors(
                background = if (foreignRecord) FilteredFullLogStyle.foreignBackground else activity.getColor(R.color.calllog_surface),
                border = if (foreignRecord) FilteredFullLogStyle.foreignBorder else activity.getColor(R.color.calllog_border),
                title = if (foreignRecord) FilteredFullLogStyle.foreignText else activity.getColor(R.color.calllog_text),
                meta = if (foreignRecord) FilteredFullLogStyle.foreignText else activity.getColor(R.color.calllog_muted_text),
                body = if (foreignRecord) FilteredFullLogStyle.foreignText else activity.getColor(R.color.calllog_text),
            ),
            metaTrailingIconRes = if (remoteEnabled && row.hasServerCopy) R.drawable.ic_cloud_note else 0,
            afterBody = { column ->
                if (remoteEnabled) {
                    metadataUi.addServerAuthor(column, row)
                    metadataUi.addServerVersionNotice(column, row)
                }
            },
        )
    }

    private fun attachedNoteView(phone: String, note: CallReportHistoryRow, remoteEnabled: Boolean): LinearLayout {
        val foreignRecord = remoteEnabled && note.authorIsOtherBroker
        val localNote = note.localNote
        val backgroundColor = if (foreignRecord) FilteredFullLogStyle.foreignBackground else NoteUiStyle.Call.background
        val border = if (foreignRecord) FilteredFullLogStyle.foreignBorder else NoteUiStyle.Call.border
        val textColor = if (foreignRecord) FilteredFullLogStyle.foreignText else NoteUiStyle.Call.text
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(backgroundColor, dp(10), border, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            if (!foreignRecord && localNote != null && note.editable) {
                isClickable = true
                isFocusable = true
                setOnClickListener { openNoteEditor(phone, localNote.withServerClientEventId(note.serverEvent?.clientEventId.orEmpty())) }
            }
            addView(metadataUi.metaView(note, remoteEnabled))
            if (note.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = note.text
                    textSize = 14f
                    setTextColor(textColor)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                })
            }
            if (remoteEnabled) {
                metadataUi.addServerAuthor(this, note)
                metadataUi.addServerVersionNotice(this, note)
            }
        }
    }

    private fun noteActionButton(call: PhoneCallRecord, editableAttachedNote: CallReportHistoryRow?): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(R.drawable.ic_chat_note)
            contentDescription = if (editableAttachedNote == null) "Добави бележка" else "Редактирай бележката"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(8) }
            setOnClickListener {
                val existing = editableAttachedNote?.localNote?.withServerClientEventId(
                    editableAttachedNote.serverEvent?.clientEventId.orEmpty(),
                )
                if (existing != null) openNoteEditor(call.number, existing)
                else openCallNoteEditor(call, call.displayName, null)
            }
        }
    }

    private fun openNoteEditor(phone: String, note: ContactCallNote) {
        val displayName = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
        openCallNoteEditor(
            PhoneCallRecord(
                number = phone,
                name = displayName,
                direction = note.direction,
                startedAt = note.callAt,
                durationSeconds = note.durationSeconds,
            ),
            displayName,
            HomeCallNote(
                text = note.note,
                updatedAtMs = maxOf(note.savedAt, note.callAt),
                fromServer = note.serverClientEventId.isNotBlank(),
                companyId = note.companyId,
                serverClientEventId = note.serverClientEventId,
            ),
        )
    }

    private fun ContactCallNote.withServerClientEventId(serverClientEventId: String): ContactCallNote {
        val normalized = serverClientEventId.trim()
        return if (normalized.isBlank() || this.serverClientEventId == normalized) this
        else copy(serverClientEventId = normalized)
    }
}
