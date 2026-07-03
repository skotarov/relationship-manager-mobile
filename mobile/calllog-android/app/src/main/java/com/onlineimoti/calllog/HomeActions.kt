package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

class HomeActions(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val startTemporaryNoteRefresh: () -> Unit,
    private val isUnfilteredHome: () -> Boolean = { false },
) {
    fun openSettings() {
        activity.startActivity(Intent(activity, MainActivity::class.java))
    }

    fun openDialer(number: String) {
        if (number.isBlank()) return
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    fun openContactNotesScreen(call: PhoneCallRecord, displayName: String) {
        activity.startActivity(
            Intent(activity, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, call.number)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, displayName.ifBlank { call.number })
                .putExtra(ContactNotesActivity.EXTRA_BACK_TARGETS_UNFILTERED_HOME, isUnfilteredHome())
        )
    }

    fun openContactNotePopupForCall(call: PhoneCallRecord, displayName: String) {
        val config = ConfigStore.load(activity)
        val existingNote = existingCallNote(call)
        val companyId = existingNote?.companyId.orEmpty().trim()
        val noteText = existingNote?.note.orEmpty()
        if (!config.useOverlayPopups) {
            openContactNoteEditorForCall(call, displayName, companyId, noteText)
            return
        }

        if (!Settings.canDrawOverlays(activity)) {
            binding.homeStatusText.text = activity.getString(R.string.dynamic_home_overlay_note_permission)
            return
        }
        activity.startService(
            Intent(activity, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, call.number)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, call.direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, displayName)
                .putExtra(PostCallOverlayService.EXTRA_CALL_AT, call.startedAt)
                .putExtra(PostCallOverlayService.EXTRA_DURATION, call.durationSeconds)
                .putExtra(CompanyMainNoteEditorLauncher.EXTRA_COMPANY_ID, companyId)
                .putExtra(CallNoteEditorLauncher.EXTRA_INITIAL_NOTE_TEXT, noteText)
        )
        startTemporaryNoteRefresh()
    }

    private fun openContactNoteEditorForCall(
        call: PhoneCallRecord,
        displayName: String,
        companyId: String,
        initialNoteText: String,
    ) {
        activity.startActivity(
            CallNoteEditorLauncher.editorIntent(
                context = activity,
                mode = PostCallOverlayService.MODE_NOTE,
                phone = call.number,
                title = displayName,
                direction = call.direction,
                callAt = call.startedAt,
                durationSeconds = call.durationSeconds,
                companyId = companyId,
                initialNoteText = initialNoteText,
            )
        )
    }

    /** Returns the existing local note attached to this exact Call Log row. */
    private fun existingCallNote(call: PhoneCallRecord): ContactCallNote? {
        if (call.startedAt <= 0L) return null
        return ContactNoteReader.callNotesForPhone(activity.applicationContext, call.number)
            .asSequence()
            .filter { note ->
                note.callAt == call.startedAt &&
                    (call.direction.isBlank() || note.direction.isBlank() || note.direction == call.direction)
            }
            .maxByOrNull { note -> maxOf(note.savedAt, note.callAt) }
    }
}
