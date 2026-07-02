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

internal object BulkContactsProgressNotification {
    private const val CHANNEL_ID = "callreport_bulk_contacts"
    private const val NOTIFICATION_ID = 2105

    fun showRunning(
        context: Context,
        action: BulkContactsTaskAction,
        progress: BulkContactRegistrationProgress,
        status: String,
        stopping: Boolean = false,
    ) {
        if (!canShowBulkContactSyncNotifications(context)) {
            cancel(context)
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            cancel(context)
            return
        }
        ensureChannel(context)
        val title = runningTitle(context, action, stopping)
        val content = when {
            progress.total > 0 -> "${progress.percent}% • ${progress.processed}/${progress.total}"
            else -> status.ifBlank { context.getString(R.string.contacts_sync_panel_prepare) }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status.ifBlank { content }))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(settingsPendingIntent(context))
            .addAction(
                0,
                context.getString(if (stopping) R.string.contacts_sync_stopping else R.string.contacts_sync_stop),
                cancelPendingIntent(context),
            )

        if (progress.total > 0) {
            builder.setProgress(progress.total, progress.processed.coerceAtMost(progress.total), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    fun showFinished(context: Context, action: BulkContactsTaskAction, status: String) {
        if (!canShowBulkContactSyncNotifications(context)) {
            cancel(context)
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            cancel(context)
            return
        }
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setContentTitle(finishedTitle(context, action))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .setContentIntent(settingsPendingIntent(context))

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.contacts_sync_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.contacts_sync_notification_channel_description)
            }
        )
    }

    private fun runningTitle(context: Context, action: BulkContactsTaskAction, stopping: Boolean): String {
        val titleRes = when (action) {
            BulkContactsTaskAction.REGISTER -> if (stopping) R.string.contacts_sync_notification_stopping_register else R.string.contacts_sync_notification_register
            BulkContactsTaskAction.REPAIR -> if (stopping) R.string.contacts_sync_notification_stopping_repair else R.string.contacts_sync_notification_repair
            BulkContactsTaskAction.CLEANUP_ORPHANS -> if (stopping) R.string.contacts_sync_notification_stopping_orphans else R.string.contacts_sync_notification_orphans
            BulkContactsTaskAction.CLEANUP -> if (stopping) R.string.contacts_sync_notification_stopping_cleanup else R.string.contacts_sync_notification_cleanup
            BulkContactsTaskAction.IDLE -> R.string.contacts_sync_notification_idle
        }
        return context.getString(titleRes)
    }

    private fun finishedTitle(context: Context, action: BulkContactsTaskAction): String {
        val titleRes = when (action) {
            BulkContactsTaskAction.REGISTER -> R.string.contacts_sync_notification_finished_register
            BulkContactsTaskAction.REPAIR -> R.string.contacts_sync_notification_finished_repair
            BulkContactsTaskAction.CLEANUP_ORPHANS -> R.string.contacts_sync_notification_finished_orphans
            BulkContactsTaskAction.CLEANUP -> R.string.contacts_sync_notification_finished_cleanup
            BulkContactsTaskAction.IDLE -> R.string.contacts_sync_notification_finished_idle
        }
        return context.getString(titleRes)
    }

    private fun settingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            2105,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BulkContactsCancelReceiver::class.java).apply {
            action = BulkContactsCancelReceiver.ACTION_CANCEL_BULK_CONTACTS
        }
        return PendingIntent.getBroadcast(
            context,
            2106,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canShowBulkContactSyncNotifications(context: Context): Boolean {
        return ConfigStore.load(context).showBulkContactSyncNotifications && canShowNotifications(context)
    }

    private fun canShowNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
