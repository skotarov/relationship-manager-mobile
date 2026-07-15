package com.onlineimoti.calllog

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

internal object CallReportNotificationActions {
    fun editorPendingIntent(
        context: Context,
        requestCode: Int,
        mode: String,
        phone: String,
        direction: String,
        title: String,
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        actionIssuedAt: Long = 0L,
    ): PendingIntent {
        val intent = Intent(context, NoteEditorLaunchActivity::class.java)
            .putExtra(PostCallOverlayService.EXTRA_MODE, mode)
            .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
            .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
            .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
            .putExtra(PostCallOverlayService.EXTRA_CALL_AT, callAt)
            .putExtra(PostCallOverlayService.EXTRA_DURATION, durationSeconds)
            .putExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, actionIssuedAt)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun inlineNoteAction(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(CallReportNotifications.KEY_INLINE_NOTE_REPLY)
            .setLabel(context.getString(R.string.notification_inline_note_hint))
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_chat_note,
            context.getString(R.string.notification_note_action),
            inlineNotePendingIntent(
                context,
                1101,
                phone,
                direction,
                callAt,
                durationSeconds,
                actionIssuedAt,
            ),
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .build()
    }

    fun contactNotesPendingIntent(
        context: Context,
        requestCode: Int,
        phone: String,
        title: String,
    ): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            CallNoteEditorLauncher.historyIntent(context, phone, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun inlineNotePendingIntent(
        context: Context,
        requestCode: Int,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
    ): PendingIntent {
        val intent = Intent(context, InlineNoteReplyReceiver::class.java)
            .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
            .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
            .putExtra(PostCallOverlayService.EXTRA_CALL_AT, callAt)
            .putExtra(PostCallOverlayService.EXTRA_DURATION, durationSeconds)
            .putExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, actionIssuedAt)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
