package com.onlineimoti.calllog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

internal object CallReportNotifications {
    private const val CHANNEL_ID = "callreport_lookup"
    private const val PASSIVE_CHANNEL_ID = "callreport_lookup_passive"
    const val LOOKUP_NOTIFICATION_ID = 2001
    const val KEY_INLINE_NOTE_REPLY = "inline_note_reply"
    private const val POST_CALL_NOTIFICATION_ID = 2002

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notification_channel_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                PASSIVE_CHANNEL_ID,
                context.getString(R.string.notification_passive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notification_passive_channel_description) },
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
        val renderToken = CallReportLookupNotificationRenderer.beginRender(LOOKUP_NOTIFICATION_ID)
        CallReportLookupNotificationRenderer.show(
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
            ensureChannel = ::ensureNotificationChannel,
        )
    }

    fun showLookupShadeNotification(
        context: Context,
        result: LookupResult,
        phone: String = "",
        direction: String = "",
        incomingPopupDataIsPreloaded: Boolean = false,
    ) {
        val renderToken = CallReportLookupNotificationRenderer.beginRender(LOOKUP_NOTIFICATION_ID)
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
        CallReportLookupNotificationRenderer.show(
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
            ensureChannel = ::ensureNotificationChannel,
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

    fun showPostCallPromptNotification(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String,
    ) {
        CallReportLookupNotificationRenderer.cancel(context, POST_CALL_NOTIFICATION_ID)
        PostCallActionRouter.route(context, phone, direction, title, formUrl)
    }
}
