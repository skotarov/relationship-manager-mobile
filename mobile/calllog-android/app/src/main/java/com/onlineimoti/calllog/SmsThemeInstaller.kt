package com.onlineimoti.calllog

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a minimal local Xiaomi/HyperOS .mtz package which overrides exactly one icon:
 * the package currently selected by Android as the default SMS application.
 *
 * The theme is intentionally opened through Xiaomi Themes, not as a generic ZIP. Themes owns
 * importing and applying icons, while Android prevents this application from copying files into
 * the private theme/launcher storage directly.
 */
internal object SmsThemeInstaller {
    private const val PREFS_NAME = "sms_theme_installer"
    private const val KEY_RESTORE_NEXT = "restore_next"
    private const val KEY_TARGET_PACKAGE = "target_sms_package"
    private const val ICON_SIZE = 512
    private const val XIAOMI_THEMES_PACKAGE = "com.android.thememanager"
    private const val THEME_MIME_TYPE = "application/x-miui-theme"

    fun isRestoreActionNext(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_NEXT, false)
    }

    fun openNext(context: Context, setStatus: (String) -> Unit) {
        if (isRestoreActionNext(context)) {
            createRestoreTheme(context, setStatus)
        } else {
            createSmsIconTheme(context, setStatus)
        }
    }

    private fun createSmsIconTheme(context: Context, setStatus: (String) -> Unit) {
        runCatching {
            val targetPackage = currentDefaultSmsPackage(context)
            backupOriginalIcon(context, targetPackage)
            val crmIcon = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_sms_launcher))
            writeTheme(
                context = context,
                displayName = "CallReport-SMS-icon.mtz",
                title = "Call Report SMS icon",
                targetPackage = targetPackage,
                iconBytes = renderPng(crmIcon),
            )
            rememberTarget(context, targetPackage, restoreNext = true)
            val themesOpened = openXiaomiThemes(context)
            setStatus(
                if (themesOpened) {
                    "Темата е записана в Downloads/Call Report и е отворено Themes. " +
                        "Импортирай CallReport-SMS-icon.mtz като Local/Offline theme и приложи само Icons."
                } else {
                    "Темата е записана в Downloads/Call Report като CallReport-SMS-icon.mtz. " +
                        "Отвори Xiaomi Themes → Local/Offline themes → Import и приложи само Icons."
                },
            )
        }.onFailure { error ->
            setStatus("Не успях да подготвя SMS темата: ${error.message.orEmpty()}")
        }
    }

    private fun createRestoreTheme(context: Context, setStatus: (String) -> Unit) {
        runCatching {
            val targetPackage = savedTargetPackage(context)
                ?: throw IOException("Няма запазена оригинална SMS икона за връщане.")
            val originalIcon = backupFile(context, targetPackage)
                .takeIf { it.isFile && it.length() > 0L }
                ?.readBytes()
                ?: throw IOException("Не намирам запазената оригинална SMS икона.")
            writeTheme(
                context = context,
                displayName = "CallReport-SMS-original-icon.mtz",
                title = "Original SMS icon",
                targetPackage = targetPackage,
                iconBytes = originalIcon,
            )
            rememberTarget(context, targetPackage, restoreNext = false)
            val themesOpened = openXiaomiThemes(context)
            setStatus(
                if (themesOpened) {
                    "Пакетът за връщане е записан в Downloads/Call Report и е отворено Themes. " +
                        "Импортирай CallReport-SMS-original-icon.mtz и приложи само Icons."
                } else {
                    "Пакетът за връщане е в Downloads/Call Report като CallReport-SMS-original-icon.mtz."
                },
            )
        }.onFailure { error ->
            setStatus("Не успях да подготвя пакета за връщане: ${error.message.orEmpty()}")
        }
    }

    private fun currentDefaultSmsPackage(context: Context): String {
        return Telephony.Sms.getDefaultSmsPackage(context)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Първо избери Default SMS приложение от статуса на разрешенията.")
    }

    private fun backupOriginalIcon(context: Context, targetPackage: String) {
        val target = backupFile(context, targetPackage)
        if (target.isFile && target.length() > 0L) return
        target.parentFile?.mkdirs()
        val original = context.packageManager.getApplicationIcon(targetPackage)
        target.writeBytes(renderPng(original))
    }

    private fun backupFile(context: Context, targetPackage: String): File {
        return File(context.filesDir, "theme-backup/$targetPackage.png")
    }

    private fun savedTargetPackage(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGET_PACKAGE, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun rememberTarget(context: Context, targetPackage: String, restoreNext: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_PACKAGE, targetPackage)
            .putBoolean(KEY_RESTORE_NEXT, restoreNext)
            .apply()
    }

    private fun writeTheme(
        context: Context,
        displayName: String,
        title: String,
        targetPackage: String,
        iconBytes: ByteArray,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, THEME_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Call Report")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Android не създаде файл за темата.")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                ZipOutputStream(output).use { themeZip ->
                    writeEntry(themeZip, "description.xml", descriptionXml(title).toByteArray(Charsets.UTF_8))
                    writeEntry(themeZip, "icons", iconsArchive(targetPackage, iconBytes))
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

    private fun iconsArchive(targetPackage: String, iconBytes: ByteArray): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { iconsZip ->
                writeEntry(iconsZip, "$targetPackage.png", iconBytes)
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

    private fun openXiaomiThemes(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(XIAOMI_THEMES_PACKAGE)
            ?: return false
        if (context !is android.app.Activity) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
        return true
    }
}
