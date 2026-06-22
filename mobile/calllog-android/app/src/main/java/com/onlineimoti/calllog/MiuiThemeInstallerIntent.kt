package com.onlineimoti.calllog

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

internal object MiuiThemeInstallerIntent {
    private const val THEMES_PACKAGE = "com.android.thememanager"
    private const val THEME_MIME_TYPE = "application/x-miui-theme"

    enum class Result {
        DIRECT_FILE,
        SHARED_FILE,
        NOT_SUPPORTED,
    }

    fun open(context: Context, uri: Uri): Result {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setPackage(THEMES_PACKAGE)
            setDataAndType(uri, THEME_MIME_TYPE)
            clipData = ClipData.newRawUri("Call Report SMS theme", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addNewTaskFlagWhenNeeded(context)
        }
        if (startIfHandled(context, viewIntent)) return Result.DIRECT_FILE

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            setPackage(THEMES_PACKAGE)
            type = THEME_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Call Report SMS theme", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addNewTaskFlagWhenNeeded(context)
        }
        if (startIfHandled(context, shareIntent)) return Result.SHARED_FILE

        return Result.NOT_SUPPORTED
    }

    private fun Intent.addNewTaskFlagWhenNeeded(context: Context) {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun startIfHandled(context: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(context.packageManager) == null) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
