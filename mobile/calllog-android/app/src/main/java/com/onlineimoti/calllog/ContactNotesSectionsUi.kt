package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout

internal class ContactNotesSectionsUi(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val cards: ContactNotesCards,
) {
    fun addGeneralNote(root: LinearLayout, phone: String, onEdit: () -> Unit) {
        val generalNote = ContactNoteReader.generalNoteForPhone(activity, phone)
        root.addView(headerUi.sectionTitleWithDrawable("Основна бележка", R.drawable.ic_note_lines))
        root.addView(
            cards.generalNoteCard(
                generalNote.ifBlank { "+ Добави" },
                muted = generalNote.isBlank(),
                onClick = onEdit,
            )
        )
    }

    fun addCallNotes(
        root: LinearLayout,
        phone: String,
        onAddLatestCallNote: (ContactCallNote) -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        root.addView(headerUi.sectionTitleWithDrawable("Бележки от разговори", R.drawable.ic_chat_note))

        val callNotes = ContactNoteReader.callNotesForPhone(phone)
        latestCallWithoutNote(phone, callNotes)?.let { latestCall ->
            root.addView(
                cards.addCallNoteButton(latestCall) {
                    onAddLatestCallNote(latestCall.toContactCallNote())
                }
            )
        }

        callNotes.forEach { note ->
            root.addView(cards.callNoteCard(note) { onEditCallNote(note) })
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
