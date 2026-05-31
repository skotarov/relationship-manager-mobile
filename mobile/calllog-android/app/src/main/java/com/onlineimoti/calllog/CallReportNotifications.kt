package com.onlineimoti.calllog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

internal object CallReportNotifications {
    private const val CHANNEL_ID = "callreport_lookup"
    private const val PASSIVE_CHANNEL_ID = "callreport_lookup_passive"
    const val LOOKUP_NOTIFICATION_ID = 2001
    const val KEY_INLINE_NOTE_REPLY = "inline_note_reply"
    private const val LEGACY_LOOKUP_SHADE_NOTIFICATION_ID = 2004
    private const val POST_CALL_NOTIFICATION_ID = 2002
    private const val BRAND_BLUE = 0xFF0A84FF.toInt()
    private const val UNKNOWN_CONTACT_TITLE = "Непознат"

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_description) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PASSIVE_CHANNEL_ID,
                "Call Report в панела",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Тиха информация в панела с известия, когато custom popup-ът е активен." }
        )
    }

    fun showLoadingLookupNotification(
        context: Context,
        phone: String,
        direction: String,
        title: String = "Зарежда се информация…",
        fullscreen: Boolean = false,
    ) {
        showLookupNotification(
            context = context,
            result = LookupResult(title = title, subtitle = phone, lines = listOf("Зарежда се информация от Call Report…"), openFormUrl = ""),
            fullscreen = fullscreen,
            phone = phone,
            direction = direction,
        )
    }

    fun showLookupNotification(
        context: Context,
        result: LookupResult,
        fullscreen: Boolean = false,
        phone: String = "",
        direction: String = "",
    ) {
        showLookupNotificationInternal(context, result, fullscreen, phone, direction, CHANNEL_ID, LOOKUP_NOTIFICATION_ID, NotificationCompat.PRIORITY_HIGH, true, true)
    }

    fun showLookupShadeNotification(context: Context, result: LookupResult, phone: String = "", direction: String = "") {
        showLookupNotificationInternal(context, result, false, phone, direction, PASSIVE_CHANNEL_ID, LOOKUP_NOTIFICATION_ID, NotificationCompat.PRIORITY_LOW, false, false)
    }

    fun showImmediatePostCallPrompt(context: Context, formUrl: String, phone: String, direction: String, title: String = "Бележка след разговора") {
        showPostCallPromptNotification(context, formUrl, phone, direction, title)
    }

    fun showPostCallPromptNotification(context: Context, formUrl: String, phone: String, direction: String, title: String) {
        NotificationManagerCompat.from(context).cancel(POST_CALL_NOTIFICATION_ID)
        val config = ConfigStore.load(context)
        when (config.postCallEndAction) {
            ConfigStore.POST_CALL_END_ACTION_NOTHING -> return
            ConfigStore.POST_CALL_END_ACTION_HISTORY -> {
                if (config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(context)) {
                    startPostCallOverlay(context, formUrl, phone, direction, title)
                } else {
                    openContactNotesScreen(context, phone, title)
                }
            }
            else -> {
                if (config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(context)) {
                    startPostCallOverlay(context, formUrl, phone, direction, title)
                } else {
                    showFullScreenNoteEditorPrompt(context, phone, direction, title)
                }
            }
        }
    }

    private fun startPostCallOverlay(context: Context, formUrl: String, phone: String, direction: String, title: String) {
        context.startService(
            Intent(context, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_FORM_URL, formUrl)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
                .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, if (formUrl.isBlank()) "Локален режим — без сървърна бележка" else "")
        )
    }

    private fun showFullScreenNoteEditorPrompt(context: Context, phone: String, direction: String, title: String) {
        val latestCall = PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull()
        context.startActivity(
            ExternalLaunchNavigation.apply(
                Intent(context, ContactNoteEditActivity::class.java)
                    .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                    .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                    .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction.ifBlank { latestCall?.direction.orEmpty() })
                    .putExtra(PostCallOverlayService.EXTRA_TITLE, title.ifBlank { phone.ifBlank { "Бележка от разговора" } })
                    .putExtra(PostCallOverlayService.EXTRA_CALL_AT, latestCall?.startedAt ?: 0L)
                    .putExtra(PostCallOverlayService.EXTRA_DURATION, latestCall?.durationSeconds ?: 0L)
            )
        )
    }

    private fun openContactNotesScreen(context: Context, phone: String, title: String) {
        context.startActivity(
            ExternalLaunchNavigation.apply(
                Intent(context, ContactNotesActivity::class.java)
                    .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                    .putExtra(ContactNotesActivity.EXTRA_TITLE, title.ifBlank { phone.ifBlank { "История" } })
            )
        )
    }

    private fun editorPendingIntent(
        context: Context,
        requestCode: Int,
        mode: String,
        phone: String,
        direction: String,
        title: String,
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
    ): PendingIntent {
        val intent = Intent(context, NoteEditorReceiver::class.java)
            .putExtra(PostCallOverlayService.EXTRA_MODE, mode)
            .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
            .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
            .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
            .putExtra(PostCallOverlayService.EXTRA_CALL_AT, callAt)
            .putExtra(PostCallOverlayService.EXTRA_DURATION, durationSeconds)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun inlineNotePendingIntent(
        context: Context,
        requestCode: Int,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
    ): PendingIntent {
        val intent = Intent(context, InlineNoteReplyReceiver::class.java)
            .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
            .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
            .putExtra(PostCallOverlayService.EXTRA_CALL_AT, callAt)
            .putExtra(PostCallOverlayService.EXTRA_DURATION, durationSeconds)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private fun inlineNoteAction(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_INLINE_NOTE_REPLY)
            .setLabel("Напиши бележка…")
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_chat_note,
            "Бележка",
            inlineNotePendingIntent(context, 1101, phone, direction, callAt, durationSeconds),
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .build()
    }

    private fun contactNotesPendingIntent(context: Context, requestCode: Int, phone: String, title: String): PendingIntent {
        val intent = ExternalLaunchNavigation.apply(
            Intent(context, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title)
        )
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun showLookupNotificationInternal(
        context: Context,
        result: LookupResult,
        fullscreen: Boolean,
        phone: String,
        direction: String,
        channelId: String,
        notificationId: Int,
        priority: Int,
        markPopup: Boolean,
        alertAgain: Boolean,
    ) {
        ensureNotificationChannel(context)
        NotificationManagerCompat.from(context).apply {
            cancel(LEGACY_LOOKUP_SHADE_NOTIFICATION_ID)
            cancel(POST_CALL_NOTIFICATION_ID)
            if (alertAgain) cancel(notificationId)
        }

        val latestCall = PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull()
        val resolvedDirection = direction.ifBlank { latestCall?.direction.orEmpty() }
        val callAt = latestCall?.startedAt ?: 0L
        val duration = latestCall?.durationSeconds ?: 0L
        val displayName = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty()
        val notificationTitle = when {
            displayName.isNotBlank() && phone.isNotBlank() -> "$displayName • $phone"
            displayName.isNotBlank() -> displayName
            result.title.isNotBlank() && result.title != phone -> "${result.title} • $phone"
            phone.isNotBlank() -> UNKNOWN_CONTACT_TITLE
            else -> result.title.ifBlank { UNKNOWN_CONTACT_TITLE }
        }

        val editIntent = editorPendingIntent(context, 1001, PostCallOverlayService.MODE_NOTE, phone, resolvedDirection, result.title, callAt, duration)
        val allNotesIntent = contactNotesPendingIntent(context, 1003, phone, notificationTitle)
        val noteReplyAction = inlineNoteAction(context, phone, resolvedDirection, callAt, duration)
        val notificationRows = LocalCallStatsProvider.buildPopupInfoRows(context, phone)
        val firstCallInfoRow = notificationRows.firstOrNull().orEmpty()
        val displayTitle = firstCallInfoRow.ifBlank { notificationTitle }
        val displayRows = when {
            firstCallInfoRow.isNotBlank() -> listOf(notificationTitle) + notificationRows.drop(1)
            notificationRows.isNotEmpty() -> notificationRows
            else -> listOf(fallbackLookupRow(result, resolvedDirection))
        }
        val rowsText = displayRows.joinToString("\n")
        val inboxStyle = NotificationCompat.InboxStyle().setBigContentTitle(displayTitle)
        displayRows.forEach { inboxStyle.addLine(it) }
        val customView = SystemLookupNotificationView.build(context, displayTitle, displayRows, editIntent, allNotesIntent)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setColor(BRAND_BLUE)
            .setColorized(true)
            .setLargeIcon(SystemPopupLargeIcon.bitmap(context, phone, notificationRows.isNotEmpty()))
            .setContentTitle(displayTitle)
            .setContentText(rowsText.ifBlank { displayTitle })
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(!alertAgain)
            .setContentIntent(editIntent)
            .addAction(noteReplyAction)
            .addAction(0, "История", allNotesIntent)
            .setStyle(inboxStyle)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setCustomHeadsUpContentView(customView)
        if (fullscreen || alertAgain) builder.setFullScreenIntent(editIntent, fullscreen)

        if (markPopup && phone.isNotBlank()) CallPopupTracker.markPopupOpened(context, phone, direction)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    private fun fallbackLookupRow(result: LookupResult, direction: String): String {
        return result.subtitle.ifBlank {
            when (direction) {
                "out" -> "Изходящ разговор"
                "in" -> "Входящ разговор"
                else -> "Разговор"
            }
        }
    }
}
