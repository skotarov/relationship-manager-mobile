package com.onlineimoti.calllog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CallReportRuntime {
    private const val CHANNEL_ID = "callreport_lookup"
    private const val LOOKUP_NOTIFICATION_ID = 2001
    const val POST_CALL_NOTIFICATION_ID = 2002
    private const val HISTORY_LIMIT = 5

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
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
        val config = ConfigStore.load(context)
        val fullLogUrl = buildEndpoint(
            baseUrl = config.baseUrl,
            path = config.historyPath,
            params = linkedMapOf(
                "phone" to phone,
                "direction" to direction,
                "access_token" to config.accessToken,
            )
        )
        val openIntent = Intent(context, WebViewActivity::class.java)
            .putExtra(WebViewActivity.EXTRA_URL, fullLogUrl)
            .putExtra(WebViewActivity.EXTRA_PHONE, phone)
            .putExtra(WebViewActivity.EXTRA_DIRECTION, direction)
        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val systemHistoryPendingIntent = PendingIntent.getActivity(
            context,
            1004,
            Intent(context, SystemCallHistoryActivity::class.java)
                .putExtra(SystemCallHistoryActivity.EXTRA_PHONE, phone)
                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val filteredHistoryPendingIntent = PendingIntent.getActivity(
            context,
            1005,
            Intent(context, SystemCallHistoryActivity::class.java)
                .putExtra(SystemCallHistoryActivity.EXTRA_PHONE, phone)
                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_NUMBER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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

        val contentViews = RemoteViews(context.packageName, R.layout.notification_lookup_system).apply {
            setTextViewText(R.id.lookupTitleText, notificationTitle)
            setTextViewText(R.id.lookupCallsText, "Разговори: $callsValue")
            setTextViewText(R.id.lookupLastText, "Последно: $lastValue")
            setTextViewText(R.id.lookupNoteText, "Бележка: $noteValue")
            setOnClickPendingIntent(R.id.lookupTitleText, pendingIntent)
            setOnClickPendingIntent(R.id.lookupCallsText, pendingIntent)
            setOnClickPendingIntent(R.id.lookupLastText, pendingIntent)
            setOnClickPendingIntent(R.id.lookupNoteText, pendingIntent)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(notificationTitle)
            .setContentText("Разговори: $callsValue • Последно: $lastValue • Бележка: $noteValue")
            .setCustomContentView(contentViews)
            .setCustomBigContentView(contentViews)
            .setCustomHeadsUpContentView(contentViews)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.open_full_log), pendingIntent)
            .addAction(0, "Тел. история", systemHistoryPendingIntent)
            .addAction(0, "История номер", filteredHistoryPendingIntent)
        if (fullscreen) builder.setFullScreenIntent(pendingIntent, true)

        if (phone.isNotBlank()) CallPopupTracker.markPopupOpened(context, phone, direction)
        NotificationManagerCompat.from(context).notify(LOOKUP_NOTIFICATION_ID, builder.build())
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
            return
        }
        showPostCallSystemNotification(context, formUrl, phone, direction, title)
    }

    private fun showPostCallSystemNotification(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String,
    ) {
        ensureNotificationChannel(context)

        val openIntent = if (formUrl.isNotBlank()) {
            Intent(context, WebViewActivity::class.java)
                .putExtra(WebViewActivity.EXTRA_URL, formUrl)
                .putExtra(WebViewActivity.EXTRA_PHONE, phone)
                .putExtra(WebViewActivity.EXTRA_DIRECTION, direction)
        } else {
            Intent(context, RecentCallsActivity::class.java)
                .putExtra(RecentCallsActivity.EXTRA_PHONE_FILTER, phone)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            2002,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            2003,
            Intent(context, NotificationDismissReceiver::class.java)
                .putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, POST_CALL_NOTIFICATION_ID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val displayName = ContactGroupFilter.resolveDisplayName(context, phone).orEmpty()
        val titleText = title.ifBlank { displayName.ifBlank { "Бележка след разговора" } }
        val contentViews = RemoteViews(context.packageName, R.layout.notification_post_call_black).apply {
            setTextViewText(R.id.postCallIconText, "✎")
            setTextViewText(R.id.postCallTitleText, titleText)
            setTextViewText(R.id.postCallPhoneText, phone)
            setOnClickPendingIntent(R.id.postCallIconText, openPendingIntent)
            setOnClickPendingIntent(R.id.postCallTitleText, openPendingIntent)
            setOnClickPendingIntent(R.id.postCallPhoneText, openPendingIntent)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(titleText)
            .setContentText(phone)
            .setCustomContentView(contentViews)
            .setCustomHeadsUpContentView(contentViews)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setFullScreenIntent(openPendingIntent, true)
            .addAction(0, "✎ Бележка", openPendingIntent)
            .addAction(0, "Пропусни", skipPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(POST_CALL_NOTIFICATION_ID, notification)
    }
}
