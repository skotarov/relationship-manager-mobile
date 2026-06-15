package com.onlineimoti.calllog

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

class NoteEditorLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openEditorAndFinish()
    }

    private fun openEditorAndFinish() {
        NotificationManagerCompat.from(this).apply {
            cancel(LOOKUP_NOTIFICATION_ID)
            cancel(POST_CALL_NOTIFICATION_ID)
        }

        val source = intent
        val mode = source.getStringExtra(PostCallOverlayService.EXTRA_MODE) ?: PostCallOverlayService.MODE_NOTE
        val phone = source.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        val direction = source.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        val title = source.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty()
        val callAt = source.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L)
        val duration = source.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L)
        val actionIssuedAt = source.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L)
        val config = ConfigStore.load(this)

        if (config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(this)) {
            startService(
                CallNoteEditorLauncher.overlayIntent(
                    context = this,
                    mode = mode,
                    phone = phone,
                    title = title,
                    direction = direction,
                    callAt = callAt,
                    durationSeconds = duration,
                    actionIssuedAt = actionIssuedAt,
                )
            )
        } else {
            CallNoteEditorLauncher.startEditor(
                context = this,
                mode = mode,
                phone = phone,
                title = title,
                direction = direction,
                callAt = callAt,
                durationSeconds = duration,
                actionIssuedAt = actionIssuedAt,
            )
        }

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val LOOKUP_NOTIFICATION_ID = 2001
        private const val POST_CALL_NOTIFICATION_ID = 2002
    }
}
