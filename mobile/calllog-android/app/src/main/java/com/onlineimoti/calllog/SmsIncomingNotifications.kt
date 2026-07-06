package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Adapts an incoming device SMS to the existing SMS inbox and existing composer.
 * It deliberately does not create a second conversation interface.
 */
internal object SmsIncomingNotifications {
    private const val CHANNEL_ID = "relationship_manager_sms"
    private const val CHANNEL_NAME = "SMS"
    private const val ACTION_OPEN_SMS = "com.onlineimoti.calllog.OPEN_SMS"

    fun show(context: Context, phone: String, body: String) {
        if (!canPostNotifications(context) || phone.isBlank()) return
        ensureChannel(context)
        val displayName = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty()
        val title = displayName.ifBlank { phone }
        val notificationId = notificationId(phone, body)
        val openInbox = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, SmsHistoryActivity::class.java)
                .setAction(ACTION_OPEN_SMS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = PendingIntent.getActivity(
            context,
            notificationId xor 0x4F1BBCDC,
            Intent(context, NoteEditorLaunchActivity::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, NoteEditorLaunchActivity.MODE_SMS_REPLY)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val preview = body.trim().ifBlank { "Ново SMS" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openInbox)
            .addAction(R.drawable.ic_menu_sms, "Отговори", reply)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Получени SMS съобщения"
            },
        )
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(phone: String, body: String): Int =
        (phone + "\u0000" + body + "\u0000" + System.currentTimeMillis()).hashCode()
}
