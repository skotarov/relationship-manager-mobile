package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
    private const val BRAND_BLUE = 0xFF0A84FF.toInt()

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
        if (config.accessToken.isNotBlank()) connection.setRequestProperty("X-Callreport-Token", config.accessToken)

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.use { input -> BufferedReader(InputStreamReader(input)).readText() }
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode: $body")

        val json = org.json.JSONObject(body)
        val linesJson = json.optJSONArray("lines")
        val serverLines = buildList {
            if (linesJson != null) for (index in 0 until linesJson.length()) add(linesJson.optString(index))
        }
        val previousCallCount = json.optInt("previous_call_count", -1)
        val recentLinesJson = json.optJSONArray("recent_call_lines")
        val recentLines = buildList {
            if (recentLinesJson != null) for (index in 0 until recentLinesJson.length()) add(recentLinesJson.optString(index))
        }
        val lines = buildList {
            if (previousCallCount >= 0) add("В Call Report: $previousCallCount записани")
            addAll(serverLines)
            addAll(recentLines.take(HISTORY_LIMIT))
        }
        val openFormUrl = json.optString("open_form_url")
        val resolvedFormUrl = if (openFormUrl.startsWith("http")) openFormUrl else config.baseUrl.trim().trimEnd('/') + openFormUrl
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

    private fun contactNotesPendingIntent(context: Context, requestCode: Int, phone: String, title: String): PendingIntent {
        val intent = Intent(context, ContactNotesActivity::class.java)
            .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
            .putExtra(ContactNotesActivity.EXTRA_TITLE, title)
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
            else -> phone.ifBlank { result.title.ifBlank { "Call Report" } }
        }

        val editIntent = editorPendingIntent(context, 1001, PostCallOverlayService.MODE_NOTE, phone, resolvedDirection, result.title, callAt, duration)
        val allNotesIntent = contactNotesPendingIntent(context, 1003, phone, notificationTitle)
        val notificationRows = LocalCallStatsProvider.buildPopupInfoRows(context, phone)
        val rowsText = notificationRows.joinToString("\n")
        val inboxStyle = NotificationCompat.InboxStyle().setBigContentTitle(notificationTitle)
        notificationRows.forEach { inboxStyle.addLine(it) }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_system_popup_callreport)
            .setColor(BRAND_BLUE)
            .setColorized(true)
            .setLargeIcon(systemPopupLargeIcon(context, phone, notificationRows.isNotEmpty()))
            .setContentTitle(notificationTitle)
            .setContentText(rowsText.ifBlank { notificationTitle })
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(!alertAgain)
            .setContentIntent(editIntent)
            .addAction(0, "💬", editIntent)
            .addAction(0, "История", allNotesIntent)
        if (notificationRows.isNotEmpty()) builder.setStyle(inboxStyle)
        if (fullscreen || alertAgain) builder.setFullScreenIntent(editIntent, fullscreen)

        if (markPopup && phone.isNotBlank()) CallPopupTracker.markPopupOpened(context, phone, direction)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    private fun systemPopupLargeIcon(context: Context, phone: String, hasLoggedData: Boolean): Bitmap? {
        contactPhotoBitmap(context, phone)?.let { return it }
        return if (hasLoggedData) infoBitmap(context, 45) else null
    }

    private fun contactPhotoBitmap(context: Context, phoneNumber: String): Bitmap? {
        if (phoneNumber.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val photoUri = context.contentResolver.query(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phoneNumber).build(),
            arrayOf(ContactsContract.PhoneLookup.PHOTO_URI, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0).orEmpty().ifBlank { cursor.getString(1).orEmpty() }.ifBlank { null }
        } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(photoUri)).use { stream ->
                val original = BitmapFactory.decodeStream(stream) ?: return@use null
                Bitmap.createScaledBitmap(original, dp(context, 45), dp(context, 45), true)
            }
        }.getOrNull()
    }

    private fun infoBitmap(context: Context, sizeDp: Int): Bitmap {
        val size = dp(context, sizeDp)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(14, 165, 233) }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = size * 0.56f
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("i", size / 2f, y, paint)
        return bitmap
    }

    private fun drawableToBitmap(context: Context, drawableRes: Int, sizeDp: Int): Bitmap? {
        val drawable: Drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
        val sizePx = dp(context, sizeDp)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun showImmediatePostCallPrompt(context: Context, formUrl: String, phone: String, direction: String, title: String = "Бележка след разговора") {
        showPostCallPromptNotification(context, formUrl, phone, direction, title)
    }

    fun showPostCallPromptNotification(context: Context, formUrl: String, phone: String, direction: String, title: String) {
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
