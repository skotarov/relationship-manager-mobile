package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent

internal object CallNoteEditorLauncher {
    const val EXTRA_INITIAL_NOTE_TEXT = "initial_note_text"

    fun editorIntent(
        context: Context,
        mode: String,
        phone: String,
        title: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        actionIssuedAt: Long = 0L,
        companyId: String = "",
        initialNoteText: String = "",
    ): Intent {
        return ExternalLaunchNavigation.apply(
            Intent(context, ContactNoteEditActivity::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, mode)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
                .putExtra(PostCallOverlayService.EXTRA_CALL_AT, callAt)
                .putExtra(PostCallOverlayService.EXTRA_DURATION, durationSeconds)
                .putExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, actionIssuedAt)
                .putExtra(CompanyMainNoteEditorLauncher.EXTRA_COMPANY_ID, companyId)
                .putExtra(EXTRA_INITIAL_NOTE_TEXT, initialNoteText)
        )
    }

    fun startEditor(
        context: Context,
        mode: String,
        phone: String,
        title: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        actionIssuedAt: Long = 0L,
        companyId: String = "",
        initialNoteText: String = "",
    ) {
        context.startActivity(
            editorIntent(
                context,
                mode,
                phone,
                title,
                direction,
                callAt,
                durationSeconds,
                actionIssuedAt,
                companyId,
                initialNoteText,
            )
        )
    }

    fun historyIntent(context: Context, phone: String, title: String): Intent {
        return ExternalLaunchNavigation.apply(
            Intent(context, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title.ifBlank { phone.ifBlank { "История" } })
        )
    }

    fun startHistory(context: Context, phone: String, title: String) {
        context.startActivity(historyIntent(context, phone, title))
    }

    fun overlayIntent(
        context: Context,
        mode: String,
        phone: String,
        title: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        actionIssuedAt: Long = 0L,
    ): Intent {
        return Intent(context, PostCallOverlayService::class.java)
            .putExtra(PostCallOverlayService.EXTRA_MODE, mode)
            .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
            .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
            .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
            .putExtra(PostCallOverlayService.EXTRA_CALL_AT, callAt)
            .putExtra(PostCallOverlayService.EXTRA_DURATION, durationSeconds)
            .putExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, actionIssuedAt)
    }
}
