package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CallReportRuntime {
    private const val CHANNEL_ID = "callreport_lookup"
    private const val LOOKUP_NOTIFICATION_ID = 2001
    const val POST_CALL_NOTIFICATION_ID = 2002
    private const val HISTORY_LIMIT = 5
    private const val LOCAL_CALL_MATCH_LIMIT = HISTORY_LIMIT + 1
    private const val LOCAL_CALL_SCAN_LIMIT = 5000

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

        val localCallCountLine = localCallCountLine(context, phone)
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
        if (fullscreen) {
            builder.setFullScreenIntent(pendingIntent, true)
        }
        val notification = builder.build()

        if (phone.isNotBlank()) {
            CallPopupTracker.markPopupOpened(context, phone, direction)
        }
        NotificationManagerCompat.from(context).notify(LOOKUP_NOTIFICATION_ID, notification)
    }

    fun showPostCallPromptNotification(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String,
    ) {
        if (formUrl.isBlank()) {
            return
        }
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
            .setContentTitle("Бележка след разговора")
            .setContentText(title.ifBlank { phone })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(title.ifBlank { phone }, "Разговорът приключи.")
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

    private fun localCallCountLine(context: Context, phone: String): String {
        if (phone.isBlank()) {
            return ""
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return "В телефона: няма разрешение"
        }

        val count = runCatching {
            countLocalCallsForPhone(context, phone)
        }.getOrDefault(0)
        val label = if (count > HISTORY_LIMIT) "${HISTORY_LIMIT}+" else count.toString()
        return "В телефона: $label разговора"
    }

    private fun countLocalCallsForPhone(context: Context, phone: String): Int {
        val digits = normalizePhone(phone)
        if (digits.isBlank()) {
            return 0
        }

        val projection = arrayOf(CallLog.Calls.NUMBER)
        val sortOrder = "${CallLog.Calls.DATE} DESC"
        var count = 0
        var scanned = 0

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder,
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            while (cursor.moveToNext() && scanned < LOCAL_CALL_SCAN_LIMIT && count < LOCAL_CALL_MATCH_LIMIT) {
                scanned += 1
                val callNumber = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                if (samePhone(digits, callNumber)) {
                    count += 1
                }
            }
        }

        return count
    }

    private fun samePhone(normalizedPhone: String, candidate: String): Boolean {
        val normalizedCandidate = normalizePhone(candidate)
        if (normalizedPhone.isBlank() || normalizedCandidate.isBlank()) {
            return false
        }
        return normalizedPhone == normalizedCandidate ||
            normalizedPhone.endsWith(normalizedCandidate) ||
            normalizedCandidate.endsWith(normalizedPhone)
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
