package com.onlineimoti.calllog

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object CallReportNotifications {
    private const val CHANNEL_ID = "callreport_lookup"
    private const val PASSIVE_CHANNEL_ID = "callreport_lookup_passive"
    const val LOOKUP_NOTIFICATION_ID = 2001
    const val KEY_INLINE_NOTE_REPLY = "inline_note_reply"
    private const val LEGACY_LOOKUP_SHADE_NOTIFICATION_ID = 2004
    private const val POST_CALL_NOTIFICATION_ID = 2002
    private const val BRAND_BLUE = 0xFF0A84FF.toInt()
    private val lookupRenderTokens = ConcurrentHashMap<Int, Long>()
    private val lookupRenderSequence = AtomicLong()

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
                context.getString(R.string.notification_passive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_passive_channel_description) }
        )
    }

    fun showLoadingLookupNotification(
        context: Context,
        phone: String,
        direction: String,
        title: String = "",
        fullscreen: Boolean = false,
    ) {
        val loadingTitle = title.ifBlank { context.getString(R.string.notification_loading_title) }
        showLookupNotification(
            context = context,
            result = LookupResult(
                title = loadingTitle,
                subtitle = phone,
                lines = listOf(context.getString(R.string.notification_loading_line)),
                openFormUrl = "",
            ),
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
        val renderToken = beginLookupRender(LOOKUP_NOTIFICATION_ID)
        showLookupNotificationInternal(
            context = context,
            result = result,
            fullscreen = fullscreen,
            phone = phone,
            direction = direction,
            channelId = CHANNEL_ID,
            notificationId = LOOKUP_NOTIFICATION_ID,
            priority = NotificationCompat.PRIORITY_HIGH,
            markPopup = true,
            alertAgain = true,
            renderToken = renderToken,
            remoteRows = emptyList(),
            requestRemoteRows = true,
            preloadedLocalRows = null,
            skipDeviceLookups = false,
        )
    }

    fun showLookupShadeNotification(
        context: Context,
        result: LookupResult,
        phone: String = "",
        direction: String = "",
        incomingPopupDataIsPreloaded: Boolean = false,
    ) {
        val renderToken = beginLookupRender(LOOKUP_NOTIFICATION_ID)
        val cachedRemoteRows = if (incomingPopupDataIsPreloaded) {
            IncomingLookupPopupRowsCache.remoteRowsFor(phone)
        } else {
            emptyList()
        }
        val cachedLocalRows = if (incomingPopupDataIsPreloaded) {
            IncomingLookupPopupRowsCache.localRowsFor(phone).orEmpty()
        } else {
            null
        }
        showLookupNotificationInternal(
            context = context,
            result = result,
            fullscreen = false,
            phone = phone,
            direction = direction,
            channelId = PASSIVE_CHANNEL_ID,
            notificationId = LOOKUP_NOTIFICATION_ID,
            priority = NotificationCompat.PRIORITY_LOW,
            markPopup = false,
            alertAgain = false,
            renderToken = renderToken,
            remoteRows = cachedRemoteRows,
            requestRemoteRows = !incomingPopupDataIsPreloaded,
            preloadedLocalRows = cachedLocalRows,
            skipDeviceLookups = incomingPopupDataIsPreloaded,
        )
    }

    fun showImmediatePostCallPrompt(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String = "",
    ) {
        showPostCallPromptNotification(
            context,
            formUrl,
            phone,
            direction,
            title.ifBlank { context.getString(R.string.notification_post_call_note_title) },
        )
    }

    fun showPostCallPromptNotification(context: Context, formUrl: String, phone: String, direction: String, title: String) {
        cancelNotificationsIfAllowed(context, POST_CALL_NOTIFICATION_ID)
        PostCallActionRouter.route(context, phone, direction, title, formUrl)
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
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private fun inlineNoteAction(
        context: Context,
        phone: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        actionIssuedAt: Long,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_INLINE_NOTE_REPLY)
            .setLabel(context.getString(R.string.notification_inline_note_hint))
            .build()
        return NotificationCompat.Action.Builder(
            R.drawable.ic_chat_note,
            context.getString(R.string.notification_note_action),
            inlineNotePendingIntent(context, 1101, phone, direction, callAt, durationSeconds, actionIssuedAt),
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .build()
    }

    private fun contactNotesPendingIntent(context: Context, requestCode: Int, phone: String, title: String): PendingIntent {
        return PendingIntent.getActivity(
            context,
            requestCode,
            CallNoteEditorLauncher.historyIntent(context, phone, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        renderToken: Long,
        remoteRows: List<PostCallLookupRemoteRow>,
        requestRemoteRows: Boolean,
        preloadedLocalRows: List<String>?,
        skipDeviceLookups: Boolean,
    ) {
        if (!canPostNotifications(context)) return
        ensureNotificationChannel(context)
        cancelNotificationsIfAllowed(
            context,
            LEGACY_LOOKUP_SHADE_NOTIFICATION_ID,
            POST_CALL_NOTIFICATION_ID,
            *if (alertAgain) intArrayOf(notificationId) else intArrayOf(),
        )

        val actionIssuedAt = System.currentTimeMillis()
        val latestCall = if (skipDeviceLookups) null else PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull()
        val resolvedDirection = direction.ifBlank { latestCall?.direction.orEmpty() }
        val displayName = if (skipDeviceLookups) {
            result.title.takeIf { it.isNotBlank() && it != phone }.orEmpty()
        } else {
            ContactGroupFilter.resolveDisplayName(context, phone).orEmpty()
        }
        val unknownContactTitle = context.getString(R.string.notification_unknown_contact)
        val notificationIdentity = when {
            displayName.isNotBlank() && phone.isNotBlank() -> "$displayName • $phone"
            displayName.isNotBlank() -> displayName
            result.title.isNotBlank() && result.title != phone -> "${result.title} • $phone"
            phone.isNotBlank() -> phone
            else -> result.title.ifBlank { unknownContactTitle }
        }

        val editIntent = editorPendingIntent(context, 1001, PostCallOverlayService.MODE_NOTE, phone, resolvedDirection, result.title, actionIssuedAt = actionIssuedAt)
        val allNotesIntent = contactNotesPendingIntent(context, 1003, phone, notificationIdentity)
        val noteReplyAction = inlineNoteAction(context, phone, resolvedDirection, 0L, 0L, actionIssuedAt)
        val content = PostCallLookupDisplayRows.build(
            context = context,
            phone = phone,
            identity = notificationIdentity,
            remoteRows = remoteRows,
            lookupServerLines = result.lines,
            preloadedLocalRows = preloadedLocalRows,
        )
        val expandedRows = content.rows.map { row -> row.plainText() }
        val inboxStyle = NotificationCompat.InboxStyle().setBigContentTitle(content.header)
        expandedRows.forEach { row -> inboxStyle.addLine(row) }
        val customView = SystemLookupNotificationView.build(context, content, editIntent, allNotesIntent)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_transparent)
            .setColor(BRAND_BLUE)
            .setColorized(true)
            .setLargeIcon(SystemPopupLargeIcon.bitmap(context, phone, content.rows.isNotEmpty()))
            .setContentTitle(content.header)
            .setContentText(expandedRows.joinToString("\n").ifBlank { content.header })
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(!alertAgain)
            .setContentIntent(editIntent)
            .addAction(noteReplyAction)
            .addAction(0, context.getString(R.string.notification_history_action), allNotesIntent)
            .setStyle(inboxStyle)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setCustomHeadsUpContentView(customView)
        val useFullScreen = fullscreen && ConfigStore.load(context).useFullScreenPopup
        if (useFullScreen || alertAgain) builder.setFullScreenIntent(editIntent, useFullScreen)

        if (markPopup && phone.isNotBlank()) CallPopupTracker.markPopupOpened(context, phone, direction)
        notifyIfAllowed(context, notificationId, builder)

        if (requestRemoteRows && PostCallLookupRemoteRows.shouldLookup(context, phone)) {
            refreshRemoteRowsAsync(
                context = context.applicationContext,
                result = result,
                phone = phone,
                direction = direction,
                channelId = channelId,
                notificationId = notificationId,
                priority = priority,
                renderToken = renderToken,
            )
        }
    }

    private fun refreshRemoteRowsAsync(
        context: Context,
        result: LookupResult,
        phone: String,
        direction: String,
        channelId: String,
        notificationId: Int,
        priority: Int,
        renderToken: Long,
    ) {
        Thread {
            val remoteRows = runCatching {
                PostCallLookupRemoteRows.load(context, phone)
            }.getOrDefault(emptyList())
            if (remoteRows.isEmpty() || lookupRenderTokens[notificationId] != renderToken) return@Thread
            showLookupNotificationInternal(
                context = context,
                result = result,
                fullscreen = false,
                phone = phone,
                direction = direction,
                channelId = channelId,
                notificationId = notificationId,
                priority = priority,
                markPopup = false,
                alertAgain = false,
                renderToken = renderToken,
                remoteRows = remoteRows,
                requestRemoteRows = false,
                preloadedLocalRows = null,
                skipDeviceLookups = false,
            )
        }.start()
    }

    private fun beginLookupRender(notificationId: Int): Long {
        val token = lookupRenderSequence.incrementAndGet()
        lookupRenderTokens[notificationId] = token
        return token
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun notifyIfAllowed(context: Context, notificationId: Int, builder: NotificationCompat.Builder) {
        if (!canPostNotifications(context)) return
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun cancelNotificationsIfAllowed(context: Context, vararg notificationIds: Int) {
        if (!canPostNotifications(context)) return
        val notifications = NotificationManagerCompat.from(context)
        notificationIds.forEach(notifications::cancel)
    }
}
