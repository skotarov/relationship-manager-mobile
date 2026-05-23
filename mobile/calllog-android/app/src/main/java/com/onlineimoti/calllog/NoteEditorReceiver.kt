package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

class NoteEditorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationManagerCompat.from(context).cancel(CallReportRuntime.POST_CALL_NOTIFICATION_ID)

        if (!Settings.canDrawOverlays(context)) return

        context.startService(
            Intent(context, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, intent.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty())
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, intent.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty())
                .putExtra(PostCallOverlayService.EXTRA_TITLE, intent.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty())
        )
    }
}
