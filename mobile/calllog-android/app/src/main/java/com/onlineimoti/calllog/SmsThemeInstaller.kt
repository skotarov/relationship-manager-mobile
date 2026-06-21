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
 * Builds a deliberately tiny MIUI/HyperOS .mtz package containing only one icon override:
 * Call Report's launcher package. The package is written to Downloads/Call Report and then
 * handed to Xiaomi Themes when that app accepts the file, otherwise Android shows a chooser.
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
            val iconBytes = renderPng(
                requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_qs_callreport)),
            )
            val uri = writeTheme(
                context = context,
                displayName = "CallReport-SMS-icon.mtz",
                title = "Call Report SMS",
                iconBytes = iconBytes,
            )
            openWithThemesOrChooser(context, uri, "Инсталирай SMS темата")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CRM_THEME_NEXT_RESTORE, true)
                .apply()
            setStatus(
                "Подготвена е SMS темата. В Themes избери пакета и приложи само Icons. " +
                    "След това постави Call Report на мястото на старата SMS иконка.",
            )
        }.onFailure {
            setStatus("Не успях да подготвя SMS темата: ${it.message.orEmpty()}")
        }
    }

    private fun openRestoreTheme(context: Context, setStatus: (String) -> Unit) {
        runCatching {
            val iconBytes = readBackedUpIcon(context) ?: renderPng(
                context.packageManager.getApplicationIcon(context.packageName),
            )
            val uri = writeTheme(
                context = context,
                displayName = "CallReport-original-icon.mtz",
                title = "Call Report original icon",
                iconBytes = iconBytes,
            )
            openWithThemesOrChooser(context, uri, "Върни иконата")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CRM_THEME_NEXT_RESTORE, false)
                .apply()
            setStatus("Подготвен е пакет за връщане на запазената иконка на Call Report.")
        }.onFailure {
            setStatus("Не успях да подготвя пакета за връщане: ${it.message.orEmpty()}")
        }
    }

    private fun backupCurrentIconIfNeeded(context: Context) {
        val backupFile = backupFile(context)
        if (backupFile.isFile && backupFile.length() > 0L) return
        backupFile.parentFile?.mkdirs()
        backupFile.writeBytes(
            renderPng(context.packageManager.getApplicationIcon(context.packageName)),
        )
    }

    private fun readBackedUpIcon(context: Context): ByteArray? {
        val backupFile = backupFile(context)
        return if (backupFile.isFile && backupFile.length() > 0L) backupFile.readBytes() else null
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
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/Call Report",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android не създаде файл за темата.")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                ZipOutputStream(output).use { mtz ->
                    writeTextEntry(mtz, "description.xml", descriptionXml(title))
                    writeBinaryEntry(mtz, "icons", iconsArchive(iconBytes))
                }
            } ?: throw IOException("Не мога да запиша пакета за темата.")

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

    private fun descriptionXml(title: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <theme>
                <title>$title</title>
                <author>OnlineImoti</author>
                <designer>OnlineImoti</designer>
                <version>1.0</version>
                <uiVersion>15</uiVersion>
            </theme>
        """.trimIndent()
    }

    private fun iconsArchive(iconBytes: ByteArray): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { icons ->
                writeBinaryEntry(icons, "$CALL_REPORT_PACKAGE.png", iconBytes)
            }
            output.toByteArray()
        }
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, text: String) {
        writeBinaryEntry(zip, name, text.toByteArray(Charsets.UTF_8))
    }

    private fun writeBinaryEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun renderPng(drawable: Drawable): ByteArray {
        val oldLeft = drawable.bounds.left
        val oldTop = drawable.bounds.top
        val oldRight = drawable.bounds.right
        val oldBottom = drawable.bounds.bottom
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(canvas)
        drawable.setBounds(oldLeft, oldTop, oldRight, oldBottom)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun openWithThemesOrChooser(context: Context, uri: Uri, chooserTitle: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            clipData = ClipData.newRawUri("Call Report SMS theme", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val themeIntent = Intent(viewIntent).setPackage(XIAOMI_THEMES_PACKAGE)
        val packageManager = context.packageManager
        when {
            themeIntent.resolveActivity(packageManager) != null -> context.startActivity(themeIntent)
            viewIntent.resolveActivity(packageManager) != null -> {
                context.startActivity(Intent.createChooser(viewIntent, chooserTitle))
            }
            else -> throw IOException("Няма приложение, което да отвори .mtz пакет.")
        }
    }
}
