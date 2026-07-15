package com.onlineimoti.calllog

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object CallReportLookupNotificationRenderer {
    private const val LEGACY_LOOKUP_SHADE_NOTIFICATION_ID = 2004
    private const val POST_CALL_NOTIFICATION_ID = 2002
    private const val BRAND_BLUE = 0xFF0A84FF.toInt()
    private val lookupRenderTokens = ConcurrentHashMap<Int, Long>()
    private val lookupRenderSequence = AtomicLong()

    fun beginRender(notificationId: Int): Long {
        val token = lookupRenderSequence.incrementAndGet()
        lookupRenderTokens[notificationId] = token
        return token
    }

    fun show(
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
        ensureChannel: (Context) -> Unit,
    ) {
        if (!canPostNotifications(context)) return
        ensureChannel(context)
        cancel(
            context,
            LEGACY_LOOKUP_SHADE_NOTIFICATION_ID,
            POST_CALL_NOTIFICATION_ID,
            *if (alertAgain) intArrayOf(notificationId) else intArrayOf(),
        )

        val actionIssuedAt = System.currentTimeMillis()
        val latestCall = if (skipDeviceLookups) {
            null
        } else {
            PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull()
        }
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

        val editIntent = CallReportNotificationActions.editorPendingIntent(
            context = context,
            requestCode = 1001,
            mode = PostCallOverlayService.MODE_NOTE,
            phone = phone,
            direction = resolvedDirection,
            title = result.title,
            actionIssuedAt = actionIssuedAt,
        )
        val allNotesIntent = CallReportNotificationActions.contactNotesPendingIntent(
            context,
            1003,
            phone,
            notificationIdentity,
        )
        val noteReplyAction = CallReportNotificationActions.inlineNoteAction(
            context,
            phone,
            resolvedDirection,
            0L,
            0L,
            actionIssuedAt,
        )
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
        val customView = SystemLookupNotificationView.build(
            context,
            content,
            editIntent,
            allNotesIntent,
        )

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

        if (markPopup && phone.isNotBlank()) {
            CallPopupTracker.markPopupOpened(context, phone, direction)
        }
        notify(context, notificationId, builder)

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
                ensureChannel = ensureChannel,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun cancel(context: Context, vararg notificationIds: Int) {
        if (!canPostNotifications(context)) return
        val notifications = NotificationManagerCompat.from(context)
        notificationIds.forEach(notifications::cancel)
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
        ensureChannel: (Context) -> Unit,
    ) {
        Thread {
            val remoteRows = runCatching {
                PostCallLookupRemoteRows.load(context, phone)
            }.getOrDefault(emptyList())
            if (
                remoteRows.isEmpty() ||
                lookupRenderTokens[notificationId] != renderToken
            ) {
                return@Thread
            }
            show(
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
                ensureChannel = ensureChannel,
            )
        }.start()
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        context: Context,
        notificationId: Int,
        builder: NotificationCompat.Builder,
    ) {
        if (!canPostNotifications(context)) return
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
}
