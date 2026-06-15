package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent

internal object CallNoteEditorLauncher {
    fun editorIntent(
        context: Context,
        mode: String,
        phone: String,
        title: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        actionIssuedAt: Long = 0L,
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
    ) {
        context.startActivity(editorIntent(context, mode, phone, title, direction, callAt, durationSeconds, actionIssuedAt))
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
