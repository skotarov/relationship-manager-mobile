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
    private const val LOOKUP_NOTIFICATION_ID = 2001
    const val POST_CALL_NOTIFICATION_ID = 2002
    private const val HISTORY_LIMIT = 5

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

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
        val body = stream.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }
        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP $responseCode: $body")
        }

        val json = org.json.JSONObject(body)
        val linesJson = json.optJSONArray("lines")
        val serverLines = buildList {
            if (linesJson != null) {
                for (index in 0 until linesJson.length()) {
                    add(linesJson.optString(index))
                }
            }
        }
        val previousCallCount = json.optInt("previous_call_count", -1)
        val recentLinesJson = json.optJSONArray("recent_call_lines")
        val recentLines = buildList {
            if (recentLinesJson != null) {
                for (index in 0 until recentLinesJson.length()) {
                    add(recentLinesJson.optString(index))
                }
            }
        }
        val lines = buildList {
            if (previousCallCount >= 0) {
                add("В Call Report: $previousCallCount записани")
            }
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
        val placeholder = LookupResult(
            title = title,
            subtitle = phone,
            lines = listOf("Зарежда се информация от Call Report…"),
            openFormUrl = "",
        )
        showLookupNotification(
            context = context,
            result = placeholder,
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
        val systemHistoryIntent = Intent(context, SystemCallHistoryActivity::class.java)
            .putExtra(SystemCallHistoryActivity.EXTRA_PHONE, phone)
            .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL)
        val systemHistoryPendingIntent = PendingIntent.getActivity(
            context,
            1004,
            systemHistoryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val filteredHistoryIntent = Intent(context, SystemCallHistoryActivity::class.java)
            .putExtra(SystemCallHistoryActivity.EXTRA_PHONE, phone)
            .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_NUMBER)
        val filteredHistoryPendingIntent = PendingIntent.getActivity(
            context,
            1005,
            filteredHistoryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val localCallCountLine = LocalCallStatsProvider.buildLine(context, phone)
        val visibleLines = buildList {
            if (localCallCountLine.isNotBlank()) {
                add(localCallCountLine)
            }
            add(result.subtitle)
            addAll(result.lines.take(HISTORY_LIMIT + 2))
        }.filter { it.isNotBlank() }

        val contentText = visibleLines.firstOrNull() ?: result.subtitle
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(result.title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(visibleLines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.open_full_log), pendingIntent)
            .addAction(0, "Тел. история", systemHistoryPendingIntent)
            .addAction(0, "История номер", filteredHistoryPendingIntent)
        if (fullscreen) {
            builder.setFullScreenIntent(pendingIntent, true)
        }
        val notification = builder.build()

        if (phone.isNotBlank()) {
            CallPopupTracker.markPopupOpened(context, phone, direction)
        }
        NotificationManagerCompat.from(context).notify(LOOKUP_NOTIFICATION_ID, notification)
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
        if (Settings.canDrawOverlays(context)) {
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
        if (formUrl.isBlank()) {
            showLookupNotification(
                context = context,
                result = LookupResult(
                    title = title.ifBlank { "Локални действия след разговора" },
                    subtitle = "Локален режим — без сървърна бележка",
                    lines = emptyList(),
                    openFormUrl = "",
                ),
                fullscreen = true,
                phone = phone,
                direction = direction,
            )
            return
        }
        showPostCallFallbackNotification(context, formUrl, phone, direction, title)
    }

    private fun showPostCallFallbackNotification(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String,
    ) {
        ensureNotificationChannel(context)

        val openFormIntent = Intent(context, WebViewActivity::class.java)
            .putExtra(WebViewActivity.EXTRA_URL, formUrl)
            .putExtra(WebViewActivity.EXTRA_PHONE, phone)
            .putExtra(WebViewActivity.EXTRA_DIRECTION, direction)
        val openFormPendingIntent = PendingIntent.getActivity(
            context,
            2002,
            openFormIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val skipIntent = Intent(context, NotificationDismissReceiver::class.java)
            .putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, POST_CALL_NOTIFICATION_ID)
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            2003,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title.ifBlank { "Бележка след разговора" })
            .setContentText(phone)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(phone, "Натисни, за да запишеш бележка.")
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openFormPendingIntent)
            .setFullScreenIntent(openFormPendingIntent, true)
            .addAction(0, "Запиши бележка", openFormPendingIntent)
            .addAction(0, "Пропусни", skipPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(POST_CALL_NOTIFICATION_ID, notification)
    }
}
