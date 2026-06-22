package com.onlineimoti.calllog

import android.content.ContentValues
import android.content.Context
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
 * Builds a tiny Xiaomi/HyperOS .mtz package containing exactly one icon override and hands that
 * file to Xiaomi Themes. It does not create a launcher shortcut or modify another app directly.
 */
internal object SmsThemeInstaller {
    private const val PREFS_NAME = "sms_theme_installer"
    private const val KEY_RESTORE_NEXT = "restore_next"
    private const val KEY_TARGET_PACKAGE = "target_sms_package"
    private const val THEME_MIME_TYPE = "application/x-miui-theme"
    private const val ICON_SIZE = 512

    private val knownSystemSmsPackages = listOf(
        "com.android.mms",
        "com.google.android.apps.messaging",
    )

    fun isRestoreActionNext(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_NEXT, false)
    }

    fun openNext(context: Context, setStatus: (String) -> Unit) {
        if (isRestoreActionNext(context)) {
            prepareRestoreTheme(context, setStatus)
        } else {
            prepareSmsIconTheme(context, setStatus)
        }
    }

    private fun prepareSmsIconTheme(context: Context, setStatus: (String) -> Unit) {
        runCatching {
            val targetPackage = resolveTargetSmsPackage(context)
            backupOriginalIcon(context, targetPackage)
            val smsIcon = requireNotNull(ContextCompat.getDrawable(context, R.mipmap.ic_sms_launcher))
            val themeUri = writeTheme(
                context = context,
                displayName = "CallReport-SMS-icon.mtz",
                title = "Call Report SMS icon",
                targetPackage = targetPackage,
                iconBytes = renderPng(smsIcon),
            )
            reportInstallerResult(
                result = MiuiThemeInstallerIntent.open(context, themeUri),
                context = context,
                targetPackage = targetPackage,
                restoreNext = true,
                installMessage = "Themes е отворено със самия CallReport-SMS-icon.mtz файл. Потвърди инсталирането и приложи само Icons.",
                shareMessage = "Themes получи CallReport-SMS-icon.mtz. Потвърди импортирането и приложи само Icons.",
                unsupportedMessage = "Темата е записана в Downloads/Call Report, но този Themes installer не приема .mtz файл от друго приложение. Затова не отварям празен Themes екран.",
                setStatus = setStatus,
            )
        }.onFailure { error ->
            setStatus("Не успях да подготвя SMS темата: ${error.message.orEmpty()}")
        }
    }

    private fun prepareRestoreTheme(context: Context, setStatus: (String) -> Unit) {
        runCatching {
            val targetPackage = savedTargetPackage(context)
                ?: throw IOException("Няма запазена оригинална SMS икона за връщане.")
            val originalIcon = backupFile(context, targetPackage)
                .takeIf { it.isFile && it.length() > 0L }
                ?.readBytes()
                ?: throw IOException("Не намирам запазената оригинална SMS икона.")
            val themeUri = writeTheme(
                context = context,
                displayName = "CallReport-SMS-original-icon.mtz",
                title = "Original SMS icon",
                targetPackage = targetPackage,
                iconBytes = originalIcon,
            )
            reportInstallerResult(
                result = MiuiThemeInstallerIntent.open(context, themeUri),
                context = context,
                targetPackage = targetPackage,
                restoreNext = false,
                installMessage = "Themes е отворено с пакета за връщане на оригиналната SMS иконка.",
                shareMessage = "Themes получи пакета за връщане на оригиналната SMS иконка.",
                unsupportedMessage = "Пакетът за връщане е в Downloads/Call Report, но този Themes installer не приема външни .mtz файлове автоматично.",
                setStatus = setStatus,
            )
        }.onFailure { error ->
            setStatus("Не успях да подготвя пакета за връщане: ${error.message.orEmpty()}")
        }
    }

    private fun reportInstallerResult(
        result: MiuiThemeInstallerIntent.Result,
        context: Context,
        targetPackage: String,
        restoreNext: Boolean,
        installMessage: String,
        shareMessage: String,
        unsupportedMessage: String,
        setStatus: (String) -> Unit,
    ) {
        when (result) {
            MiuiThemeInstallerIntent.Result.DIRECT_FILE -> {
                rememberTarget(context, targetPackage, restoreNext)
                setStatus(installMessage)
            }
            MiuiThemeInstallerIntent.Result.SHARED_FILE -> {
                rememberTarget(context, targetPackage, restoreNext)
                setStatus(shareMessage)
            }
            MiuiThemeInstallerIntent.Result.NOT_SUPPORTED -> setStatus(unsupportedMessage)
        }
    }

    private fun resolveTargetSmsPackage(context: Context): String {
        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Не е намерено Default SMS приложение.")
        if (defaultPackage != context.packageName) return defaultPackage

        return knownSystemSmsPackages.firstOrNull { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
        } ?: throw IOException("Call Report е Default SMS, но не намирам Xiaomi/Google Messages за смяна на иконата.")
    }

    private fun backupOriginalIcon(context: Context, targetPackage: String) {
        val file = backupFile(context, targetPackage)
        if (file.isFile && file.length() > 0L) return
        file.parentFile?.mkdirs()
        file.writeBytes(renderPng(context.packageManager.getApplicationIcon(targetPackage)))
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
        val oldBounds = drawable.bounds
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = oldBounds
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
