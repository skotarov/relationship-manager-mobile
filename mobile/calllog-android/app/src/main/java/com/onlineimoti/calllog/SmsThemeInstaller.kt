package com.onlineimoti.calllog

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a small local MIUI/HyperOS .mtz package containing only Call Report's icon override.
 * Xiaomi Themes owns installation and applying of themes, so the app generates the package and
 * opens the Themes/file-import flow. No wallpaper, font, lock screen or other icon is included.
 */
internal object SmsThemeInstaller {
    private const val PREFS_NAME = "sms_theme_installer"
    private const val KEY_CRM_THEME_NEXT_RESTORE = "crm_theme_next_restore"
    private const val ICON_SIZE = 512
    private const val CALL_REPORT_PACKAGE = "com.onlineimoti.calllog"
    private const val XIAOMI_THEMES_PACKAGE = "com.android.thememanager"

    fun isRestoreActionNext(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRM_THEME_NEXT_RESTORE, false)
    }

    fun openNext(context: Context, setStatus: (String) -> Unit) {
        if (isRestoreActionNext(context)) {
            openRestoreTheme(context, setStatus)
        } else {
            openCrmSmsTheme(context, setStatus)
        }
    }

    private fun openCrmSmsTheme(context: Context, setStatus: (String) -> Unit) {
        runCatching {
            backupCurrentIconIfNeeded(context)
            val icon = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_sms_launcher))
            val uri = writeTheme(
                context = context,
                displayName = "CallReport-SMS-icon.mtz",
                title = "Call Report SMS",
                iconBytes = renderPng(icon),
            )
            openThemesOrFilePicker(context, uri, "Инсталирай CRM SMS тема")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CRM_THEME_NEXT_RESTORE, true)
                .apply()
            setStatus(
                "Избери Downloads → Call Report → CallReport-SMS-icon.mtz. " +
                    "В Themes приложи само Icons, после премести Call Report на мястото на старата SMS иконка.",
            )
        }.onFailure { error ->
            setStatus("Не успях да подготвя SMS темата: ${error.message.orEmpty()}")
        }
    }

    private fun openRestoreTheme(context: Context, setStatus: (String) -> Unit) {
        runCatching {
            val originalIcon = readBackedUpIcon(context)
                ?: renderPng(context.packageManager.getApplicationIcon(context.packageName))
            val uri = writeTheme(
                context = context,
                displayName = "CallReport-original-icon.mtz",
                title = "Call Report original icon",
                iconBytes = originalIcon,
            )
            openThemesOrFilePicker(context, uri, "Върни иконката")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CRM_THEME_NEXT_RESTORE, false)
                .apply()
            setStatus("Избери Downloads → Call Report → CallReport-original-icon.mtz, за да върнеш запазената иконка.")
        }.onFailure { error ->
            setStatus("Не успях да подготвя пакета за връщане: ${error.message.orEmpty()}")
        }
    }

    private fun backupCurrentIconIfNeeded(context: Context) {
        val target = backupFile(context)
        if (target.isFile && target.length() > 0L) return
        target.parentFile?.mkdirs()
        target.writeBytes(renderPng(context.packageManager.getApplicationIcon(context.packageName)))
    }

    private fun readBackedUpIcon(context: Context): ByteArray? {
        val target = backupFile(context)
        return if (target.isFile && target.length() > 0L) target.readBytes() else null
    }

    private fun backupFile(context: Context): File {
        return File(context.filesDir, "theme-backup/callreport-original-icon.png")
    }

    private fun writeTheme(
        context: Context,
        displayName: String,
        title: String,
        iconBytes: ByteArray,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Call Report")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android не създаде файл за темата.")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                ZipOutputStream(output).use { themeZip ->
                    writeEntry(themeZip, "description.xml", descriptionXml(title).toByteArray(Charsets.UTF_8))
                    writeEntry(themeZip, "icons", iconsArchive(iconBytes))
                }
            } ?: throw IOException("Не мога да запиша темата.")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun descriptionXml(title: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <theme>
            <title>$title</title>
            <author>OnlineImoti</author>
            <designer>OnlineImoti</designer>
            <version>1.0</version>
            <uiVersion>15</uiVersion>
        </theme>
    """.trimIndent()

    private fun iconsArchive(iconBytes: ByteArray): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { iconsZip ->
                writeEntry(iconsZip, "$CALL_REPORT_PACKAGE.png", iconBytes)
            }
            output.toByteArray()
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun renderPng(drawable: Drawable): ByteArray {
        val previousBounds = drawable.bounds
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(canvas)
        drawable.bounds = previousBounds
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun openThemesOrFilePicker(context: Context, uri: Uri, title: String) {
        val openTheme = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            clipData = ClipData.newRawUri("Call Report SMS theme", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val themesIntent = Intent(openTheme).setPackage(XIAOMI_THEMES_PACKAGE)
        val packageManager = context.packageManager
        when {
            themesIntent.resolveActivity(packageManager) != null -> context.startActivity(themesIntent)
            openTheme.resolveActivity(packageManager) != null -> context.startActivity(Intent.createChooser(openTheme, title))
            else -> throw IOException("Няма приложение, което да отвори файла с темата.")
        }
    }
}
