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
    fun addGeneralNote(
        root: LinearLayout,
        phone: String,
        companyNotes: List<CallReportCompanyMainNote>,
        useCompanyScope: Boolean,
        onEditCompany: (String) -> Unit,
    ) {
        val section = sectionContainer()
        root.addView(section)
        section.addView(headerUi.sectionTitleWithDrawable(activity.getString(R.string.dynamic_note_general_title), R.drawable.ic_note_lines))
        renderLocalGeneralNote(section, phone, onEditCompany)
        if (useCompanyScope && CrmContactSyncStore.isEnabled(activity, phone)) {
            renderCompanyGeneralNotes(section, companyNotes, onEditCompany)
        }
    }

    private fun renderCompanyGeneralNotes(
        section: LinearLayout,
        companyNotes: List<CallReportCompanyMainNote>,
        onEditCompany: (String) -> Unit,
    ) {
        companyNotes.forEach { companyNote ->
            section.addView(companyNameLabel(companyNote.companyName))
            section.addView(
                cards.generalNoteCard(
                    textValue = companyNote.note.ifBlank { activity.getString(R.string.dynamic_notes_add_general) },
                    muted = companyNote.note.isBlank(),
                    serverConfirmed = companyNote.confirmedByServer,
                    syncStatusText = if (companyNote.pending) activity.getString(R.string.history_pending_server_sync) else "",
                    onClick = { onEditCompany(companyNote.companyId) },
                )
            )
        }
    }

    private fun renderLocalGeneralNote(
        section: LinearLayout,
        phone: String,
        onEditCompany: (String) -> Unit,
    ) {
        val generalNote = ContactNoteReader.generalNoteForPhone(activity, phone)
        section.addView(companyNameLabel(activity.getString(R.string.note_local_company)))
        section.addView(
            cards.generalNoteCard(
                textValue = generalNote.ifBlank { activity.getString(R.string.dynamic_notes_add_general) },
                muted = generalNote.isBlank(),
                serverConfirmed = false,
                syncStatusText = "",
                onClick = { onEditCompany(ContactNoteTopicState.LOCAL_COMPANY_ID) },
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

    private fun sectionContainer(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) }
    }

    private fun latestCallWithoutNote(phone: String, callNotes: List<ContactCallNote>): PhoneCallRecord? {
        val latestCall = PhoneCallReader.callsForPhone(activity, phone, limit = 1).firstOrNull() ?: return null
        val alreadyHasNote = callNotes.any { note ->
            note.callAt == latestCall.startedAt && (note.direction.isBlank() || latestCall.direction.isBlank() || note.direction == latestCall.direction)
        }
        return latestCall.takeUnless { alreadyHasNote }
    }

    private fun PhoneCallRecord.toContactCallNote(): ContactCallNote = ContactCallNote(
        note = "",
        callAt = startedAt,
        savedAt = startedAt,
        direction = direction,
        durationSeconds = durationSeconds,
    )
}
