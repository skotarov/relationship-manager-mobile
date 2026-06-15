package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

class NoteEditorLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openEditorAndFinish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        openEditorAndFinish()
    }

    private fun openEditorAndFinish() {
        NotificationManagerCompat.from(this).cancel(CallReportNotifications.LOOKUP_NOTIFICATION_ID)
        NotificationManagerCompat.from(this).cancel(CallReportRuntime.POST_CALL_NOTIFICATION_ID)

        val mode = intent?.getStringExtra(PostCallOverlayService.EXTRA_MODE) ?: PostCallOverlayService.MODE_NOTE
        val phone = intent?.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        val direction = intent?.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        val title = intent?.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty()
        val callAt = intent?.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L) ?: 0L
        val duration = intent?.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L) ?: 0L
        val actionIssuedAt = intent?.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L) ?: 0L
        val target = CallNoteTargetResolver.resolve(this, phone, direction, callAt, duration, actionIssuedAt)
        val config = ConfigStore.load(this)

        if (config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(this)) {
            startService(
                CallNoteEditorLauncher.overlayIntent(
                    context = this,
                    mode = mode,
                    phone = phone,
                    title = title,
                    direction = target.direction,
                    callAt = target.callAt,
                    durationSeconds = target.durationSeconds,
                    actionIssuedAt = actionIssuedAt,
                )
            )
        } else {
            CallNoteEditorLauncher.startEditor(
                context = this,
                mode = mode,
                phone = phone,
                title = title,
                direction = target.direction,
                callAt = target.callAt,
                durationSeconds = target.durationSeconds,
                actionIssuedAt = actionIssuedAt,
            )
        }

        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}
