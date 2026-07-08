package com.onlineimoti.calllog

import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

class NoteEditorLaunchActivity : FontScaledActivity() {
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

        if (mode == MODE_SMS_REPLY) {
            SmsComposeDialog(this, ::dp).show(
                phone = phone,
                title = title.ifBlank { phone },
                onDismiss = {
                    finish()
                    overridePendingTransition(0, 0)
                },
            )
            return
        }

        if (mode == MODE_SMS_VIEW) {
            SmsMessageViewDialog(this, ::dp).show(
                phone = phone,
                title = title.ifBlank { phone },
                body = source.getStringExtra(EXTRA_SMS_BODY).orEmpty(),
                receivedAtMs = source.getLongExtra(EXTRA_SMS_RECEIVED_AT, 0L),
                onDismiss = {
                    finish()
                    overridePendingTransition(0, 0)
                },
            )
            return
        }

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
                ),
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val MODE_SMS_REPLY = "sms_reply"
        const val MODE_SMS_VIEW = "sms_view"
        const val EXTRA_SMS_BODY = "sms_body"
        const val EXTRA_SMS_RECEIVED_AT = "sms_received_at"
        private const val LOOKUP_NOTIFICATION_ID = 2001
        private const val POST_CALL_NOTIFICATION_ID = 2002
    }
}
