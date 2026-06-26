package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

internal class ContactNotesSectionsUi(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val cards: ContactNotesCards,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun addGeneralNote(root: LinearLayout, phone: String, onEdit: () -> Unit) {
        val section = sectionContainer()
        root.addView(section)
        renderLocalGeneralNote(section, phone, onEdit)

        val config = ConfigStore.load(activity)
        if (!CallReportRemoteAccess.isReady(config) || phone.isBlank()) return
        CompanyGeneralNotesAsync.load(activity, config, phone) { companyNotes ->
            if (companyNotes.isEmpty()) return@load
            section.removeAllViews()
            section.addView(headerUi.sectionTitleWithDrawable(activity.getString(R.string.dynamic_note_general_title), R.drawable.ic_note_lines))
            companyNotes.forEach { companyNote ->
                section.addView(companyNameLabel(companyNote.companyName))
                section.addView(
                    cards.generalNoteCard(
                        textValue = companyNote.note.ifBlank { activity.getString(R.string.dynamic_notes_add_general) },
                        muted = companyNote.note.isBlank(),
                        serverConfirmed = companyNote.confirmedByServer,
                        syncStatusText = if (companyNote.pending) activity.getString(R.string.history_pending_server_sync) else "",
                        onClick = onEdit,
                    )
                )
            }
        }
    }

    private fun renderLocalGeneralNote(section: LinearLayout, phone: String, onEdit: () -> Unit) {
        val generalNote = ContactNoteReader.generalNoteForPhone(activity, phone)
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val waitingForCurrentVersion = remoteEnabled && CallReportNoteOutbox.isGeneralPending(activity, phone)
        val failure = if (remoteEnabled) CallReportNoteOutbox.lastFailure(activity) else ""
        val serverConfirmed = ServerRecordIndex.isGeneralNoteConfirmed(activity, phone) ||
            (remoteEnabled && CallReportHistoryLookupClient.hasGeneralNoteOnServer(phone))
        val syncStatusText = when {
            !remoteEnabled || generalNote.isBlank() || !waitingForCurrentVersion -> ""
            failure.isNotBlank() -> "Синхронизацията не е потвърдена: $failure"
            else -> "Чака сървърна синхронизация"
        }
        section.addView(headerUi.sectionTitleWithDrawable(activity.getString(R.string.dynamic_note_general_title), R.drawable.ic_note_lines))
        section.addView(
            cards.generalNoteCard(
                textValue = generalNote.ifBlank { activity.getString(R.string.dynamic_notes_add_general) },
                muted = generalNote.isBlank(),
                serverConfirmed = generalNote.isNotBlank() && serverConfirmed,
                syncStatusText = syncStatusText,
                onClick = onEdit,
            )
        )
    }

    private fun companyNameLabel(companyName: String): TextView = TextView(activity).apply {
        text = companyName
        textSize = 12.5f
        setTextColor(Color.rgb(71, 85, 105))
        setPadding(dp(2), dp(8), dp(2), dp(3))
    }

    fun addCallNotes(
        root: LinearLayout,
        phone: String,
        onAddLatestCallNote: (ContactCallNote) -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        val callNotes = ContactNoteReader.callNotesForPhone(activity, phone)
        root.addView(sectionContainer().apply {
            addView(headerUi.sectionTitleWithDrawable(activity.getString(R.string.dynamic_notes_call_section), R.drawable.ic_chat_note))

            latestCallWithoutNote(phone, callNotes)?.let { latestCall ->
                addView(cards.addCallNoteButton(latestCall) { onAddLatestCallNote(latestCall.toContactCallNote()) })
            }

            callNotes.forEach { note ->
                addView(cards.callNoteCard(note, ServerRecordIndex.isCallNoteConfirmed(activity, phone, note)) { onEditCallNote(note) })
            }
        })
    }

    private fun sectionContainer(): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun latestCallWithoutNote(phone: String, callNotes: List<ContactCallNote>): PhoneCallRecord? {
        val latestCall = PhoneCallReader.callsForPhone(activity, phone, limit = 1).firstOrNull() ?: return null
        val alreadyHasNote = callNotes.any { note ->
            note.callAt == latestCall.startedAt && (note.direction.isBlank() || latestCall.direction.isBlank() || note.direction == latestCall.direction)
        }
        return latestCall.takeUnless { alreadyHasNote }
    }

    private fun PhoneCallRecord.toContactCallNote(): ContactCallNote {
        return ContactCallNote(
            note = "",
            callAt = startedAt,
            savedAt = startedAt,
            direction = direction,
            durationSeconds = durationSeconds,
        )
    }
}
