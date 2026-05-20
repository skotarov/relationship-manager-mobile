package com.onlineimoti.calllog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CallReportRuntime {
    private const val LOOKUP_CHANNEL_ID = "callreport_lookup"
    private const val POST_CALL_CHANNEL_ID = "callreport_post_call"
    private const val LOOKUP_NOTIFICATION_ID = 2001

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val lookupChannel = NotificationChannel(
            LOOKUP_CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        val postCallChannel = NotificationChannel(
            POST_CALL_CHANNEL_ID,
            context.getString(R.string.post_call_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.post_call_channel_description)
        }
        manager.createNotificationChannel(lookupChannel)
        manager.createNotificationChannel(postCallChannel)
    }

    fun fetchLookup(config: AppConfig, phone: String, direction: String): LookupResult {
        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = "/broker/callreport/lookup.php",
            params = linkedMapOf(
                "phone" to phone,
                "direction" to direction,
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

        val json = JSONObject(body)
        val linesJson = json.optJSONArray("lines")
        val lines = buildList {
            if (linesJson != null) {
                for (index in 0 until linesJson.length()) {
                    add(linesJson.optString(index))
                }
            }
        }

        val openFormUrl = json.optString("open_form_url")
        val resolvedFormUrl = when {
            openFormUrl.startsWith("http") -> openFormUrl
            openFormUrl.isNotBlank() -> config.baseUrl.trim().trimEnd('/') + openFormUrl
            else -> config.buildFormUrl(phone, direction)
        }

        return LookupResult(
            phone = phone,
            direction = direction,
            title = json.optString("title", phone),
            subtitle = json.optString("subtitle", ""),
            lines = lines,
            openFormUrl = resolvedFormUrl,
            openHistoryUrl = config.buildHistoryUrl(phone, direction),
        )
    }

    fun showLookupNotification(context: Context, result: LookupResult, fullscreen: Boolean = false) {
        val openIntent = Intent(context, WebViewActivity::class.java)
            .putExtra(WebViewActivity.EXTRA_URL, result.openHistoryUrl)
        val pendingIntent = PendingIntent.getActivity(
            context,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, LOOKUP_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setLargeIcon(drawableToBitmap(context, R.drawable.ic_phone_handset))
            .setContentTitle(result.title)
            .setContentText(result.subtitle)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(result.subtitle)
                        .plus(result.lines.take(3))
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.full_log), pendingIntent)
        if (fullscreen) {
            builder.setFullScreenIntent(pendingIntent, true)
        }

        NotificationManagerCompat.from(context).notify(LOOKUP_NOTIFICATION_ID, builder.build())
    }

    fun showPostCallPrompt(context: Context, call: RecentCallItem, config: AppConfig, fullscreen: Boolean) {
        val notificationId = 4000 + (call.id % 1000).toInt()
        val promptIntent = Intent(context, PostCallPromptActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PostCallPromptActivity.EXTRA_PHONE, call.number)
            putExtra(PostCallPromptActivity.EXTRA_DIRECTION, call.directionSlug)
            putExtra(PostCallPromptActivity.EXTRA_DISPLAY_NAME, call.displayLabel)
            putExtra(PostCallPromptActivity.EXTRA_FORM_URL, config.buildFormUrl(call.number, call.directionSlug))
            putExtra(PostCallPromptActivity.EXTRA_TIMEOUT_SECONDS, config.resolvedPostCallAutoCloseSeconds)
            putExtra(PostCallPromptActivity.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val promptPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            promptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, POST_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setLargeIcon(drawableToBitmap(context, R.drawable.ic_note_lines))
            .setContentTitle(context.getString(R.string.post_call_prompt_title))
            .setContentText(call.displayLabel)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.post_call_prompt_body,
                        call.displayLabel
                    )
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(config.resolvedPostCallAutoCloseSeconds * 1000L)
            .setContentIntent(promptPendingIntent)
        if (fullscreen) {
            builder.setFullScreenIntent(promptPendingIntent, true)
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    private fun drawableToBitmap(context: Context, drawableResId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableResId) ?: return null
        return drawable.toBitmap()
    }

    private fun Drawable.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            intrinsicWidth.coerceAtLeast(1),
            intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}
