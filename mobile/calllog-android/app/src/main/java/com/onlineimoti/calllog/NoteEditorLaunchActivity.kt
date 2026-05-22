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
        NotificationManagerCompat.from(this).cancel(CallReportRuntime.POST_CALL_NOTIFICATION_ID)

        if (Settings.canDrawOverlays(this)) {
            startService(
                Intent(this, PostCallOverlayService::class.java)
                    .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                    .putExtra(PostCallOverlayService.EXTRA_PHONE, intent?.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty())
                    .putExtra(PostCallOverlayService.EXTRA_DIRECTION, intent?.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty())
                    .putExtra(PostCallOverlayService.EXTRA_TITLE, intent?.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty())
            )
        }
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}
