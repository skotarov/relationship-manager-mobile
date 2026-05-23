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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CallReportRuntime {
    private const val CHANNEL_ID = "callreport_lookup"
    private const val PASSIVE_CHANNEL_ID = "callreport_lookup_passive"
    private const val LOOKUP_NOTIFICATION_ID = 2001
    private const val LEGACY_LOOKUP_SHADE_NOTIFICATION_ID = 2004
    const val POST_CALL_NOTIFICATION_ID = 2002
    private const val HISTORY_LIMIT = 5

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        val passiveChannel = NotificationChannel(
            PASSIVE_CHANNEL_ID,
            "Call Report в панела",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Тиха информация в панела с известия, когато custom popup-ът е активен."
        }
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(passiveChannel)
    }

    fun fetchLookup(config: AppConfig, phone: String, direction: String): LookupResult {
        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = config.lookupPath,
            params = linkedMapOf(
                "phone" to phone,
                "direction" to direction,
                "history_limit" to HISTORY_LIMIT.toString(),
                "access_token" to config.accessToken,
            )
        )

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/json")
        if (config.accessToken.isNotBlank()) {
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.use { input -> BufferedReader(InputStreamReader(input)).readText() }
        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP $responseCode: $body")
        }

        val json = org.json.JSONObject(body)
        val linesJson = json.optJSONArray("lines")
        val serverLines = buildList {
            if (linesJson != null) {
                for (index in 0 until linesJson.length()) add(linesJson.optString(index))
            }
        }
        val previousCallCount = json.optInt("previous_call_count", -1)
        val recentLinesJson = json.optJSONArray("recent_call_lines")
        val recentLines = buildList {
            if (recentLinesJson != null) {
                for (index in 0 until recentLinesJson.length()) add(recentLinesJson.optString(index))
            }
        }
        val lines = buildList {
            if (previousCallCount >= 0) add("В Call Report: $previousCallCount записани")
            addAll(serverLines)
            addAll(recentLines.take(HISTORY_LIMIT))
        }

        val openFormUrl = json.optString("open_form_url")
        val resolvedFormUrl = if (openFormUrl.startsWith("http")) {
            openFormUrl
        } else {
            config.baseUrl.trim().trimEnd('/') + openFormUrl
        }

        return LookupResult(
            title = json.optString("title", phone),
            subtitle = json.optString("subtitle", ""),
            lines = lines,
            openFormUrl = resolvedFormUrl,
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
            result = LookupResult(
                title = title,
                subtitle = phone,
                lines = listOf("Зарежда се информация от Call Report…"),
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
        )
    }

    fun showLookupShadeNotification(
        context: Context,
        result: LookupResult,
        phone: String = "",
        direction: String = "",
    ) {
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
        )
    }

    private fun noteEditorPendingIntent(
        context: Context,
        requestCode: Int,
        phone: String,
        direction: String,
        title: String,
    ): PendingIntent {
        val intent = Intent(context, NoteEditorLaunchActivity::class.java)
            .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
            .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
            .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissPendingIntent(context: Context, notificationId: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            1006,
            Intent(context, NotificationDismissReceiver::class.java)
                .putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, notificationId),
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
    ) {
        ensureNotificationChannel(context)
        NotificationManagerCompat.from(context).apply {
            cancel(LEGACY_LOOKUP_SHADE_NOTIFICATION_ID)
            cancel(POST_CALL_NOTIFICATION_ID)
            if (alertAgain) cancel(notificationId)
        }
        val notePendingIntent = noteEditorPendingIntent(context, 1001, phone, direction, result.title)
        val closePendingIntent = dismissPendingIntent(context, notificationId)

        val displayName = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty()
        val notificationTitle = when {
            displayName.isNotBlank() && phone.isNotBlank() -> "$displayName • $phone"
            displayName.isNotBlank() -> displayName
            result.title.isNotBlank() && result.title != phone -> "${result.title} • $phone"
            else -> phone.ifBlank { result.title.ifBlank { "Call Report" } }
        }
        val summary = LocalCallStatsProvider.summarize(context, phone)
        val contactNote = ContactNoteReader.noteForPhone(context, phone)
        val callsValue = summary?.let { if (it.count <= 0) "няма предишни разговори" else it.count.toString() }.orEmpty().ifBlank { "няма данни" }
        val lastValue = summary?.let { if (it.count <= 0) "няма предишно обаждане" else it.lastCallAgo.ifBlank { "няма данни" } }.orEmpty().ifBlank { "няма данни" }
        val noteValue = contactNote.ifBlank { "няма" }
        val notificationRows = listOf(
            formatNotificationRow("Разговори", callsValue),
            formatNotificationRow("Последно", lastValue),
            formatNotificationRow("Бележка", noteValue),
        )
        val rowsText = notificationRows.joinToString("\n")
        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(notificationTitle)
        notificationRows.forEach { inboxStyle.addLine(it) }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_callreport_notification)
            .setContentTitle(notificationTitle)
            .setContentText(rowsText)
            .setStyle(inboxStyle)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(!alertAgain)
            .setContentIntent(notePendingIntent)
            .addAction(android.R.drawable.ic_menu_edit, "Edit", notePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closePendingIntent)
        if (fullscreen || alertAgain) builder.setFullScreenIntent(notePendingIntent, fullscreen)

        if (markPopup && phone.isNotBlank()) CallPopupTracker.markPopupOpened(context, phone, direction)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    private fun formatNotificationRow(label: String, value: String): String {
        return "$label: $value"
    }

    fun showImmediatePostCallPrompt(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String = "Бележка след разговора",
    ) {
        showPostCallPromptNotification(context, formUrl, phone, direction, title)
    }

    fun showPostCallPromptNotification(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String,
    ) {
        NotificationManagerCompat.from(context).cancel(POST_CALL_NOTIFICATION_ID)
        val config = ConfigStore.load(context)
        if (config.useCustomEndPopup && Settings.canDrawOverlays(context)) {
            context.startService(
                Intent(context, PostCallOverlayService::class.java)
                    .putExtra(PostCallOverlayService.EXTRA_FORM_URL, formUrl)
                    .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                    .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
                    .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
                    .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, if (formUrl.isBlank()) "Локален режим — без сървърна бележка" else "")
            )
        }
    }
}
