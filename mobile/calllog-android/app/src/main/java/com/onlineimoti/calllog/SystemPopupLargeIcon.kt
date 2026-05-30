package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object SystemPopupLargeIcon {
    fun bitmap(context: Context, phone: String, hasLoggedData: Boolean): Bitmap? {
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

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}