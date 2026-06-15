package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

class NoteEditorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationManagerCompat.from(context).cancel(CallReportRuntime.POST_CALL_NOTIFICATION_ID)

        val mode = intent.getStringExtra(PostCallOverlayService.EXTRA_MODE)
            ?: PostCallOverlayService.MODE_NOTE
        val phone = intent.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        val direction = intent.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        val title = intent.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty()
        val callAt = intent.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L)
        val duration = intent.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L)
        val actionIssuedAt = intent.getLongExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, 0L)
        val target = CallNoteTargetResolver.resolve(context, phone, direction, callAt, duration, actionIssuedAt)
        val config = ConfigStore.load(context)

        if (config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(context)) {
            context.startService(
                CallNoteEditorLauncher.overlayIntent(
                    context = context,
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
                context = context,
                mode = mode,
                phone = phone,
                title = title,
                direction = target.direction,
                callAt = target.callAt,
                durationSeconds = target.durationSeconds,
                actionIssuedAt = actionIssuedAt,
            )
        }
    }
}
