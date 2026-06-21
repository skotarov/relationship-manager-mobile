package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * Creates or updates a normal Android pinned shortcut for the SMS/CRM entry point.
 * Xiaomi Themes is not involved: it has no public install/apply API for a third-party app.
 */
internal object SmsThemeInstaller {
    private const val PREFS_NAME = "sms_theme_installer"
    private const val KEY_CRM_THEME_NEXT_RESTORE = "crm_theme_next_restore"
    private const val SHORTCUT_ID = "callreport_sms_home"

    fun isRestoreActionNext(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRM_THEME_NEXT_RESTORE, false)
    }

    fun openNext(context: Context, setStatus: (String) -> Unit) {
        val enableSmsIdentity = !isRestoreActionNext(context)
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            setStatus("Началният екран не позволява добавяне на shortcut за SMS иконата.")
            return
        }

        val shortcut = buildShortcut(context, enableSmsIdentity)
        val alreadyPinned = manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }
        val accepted = if (alreadyPinned) {
            manager.updateShortcuts(listOf(shortcut))
            true
        } else {
            manager.requestPinShortcut(shortcut, null)
        }
        if (!accepted) {
            setStatus("Xiaomi launcher не прие добавянето на SMS иконата.")
            return
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CRM_THEME_NEXT_RESTORE, enableSmsIdentity)
            .apply()

        setStatus(
            if (enableSmsIdentity) {
                if (alreadyPinned) {
                    "SMS иконата е обновена. На началния екран вече е „Съобщения“ с CRM балон."
                } else {
                    "Потвърди добавянето на „Съобщения“ към началния екран и я постави на мястото на старата SMS иконка."
                }
            } else {
                "Shortcut-ът е върнат към стандартната иконка и име на Call Report."
            },
        )
    }

    private fun buildShortcut(context: Context, smsIdentity: Boolean): ShortcutInfo {
        val intent = Intent(context, HomeActivity::class.java).apply {
            action = if (smsIdentity) ACTION_OPEN_SMS_HOME else ACTION_OPEN_CALL_REPORT_HOME
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel(
                if (smsIdentity) context.getString(R.string.sms_launcher_label) else context.getString(R.string.app_name),
            )
            .setLongLabel(
                if (smsIdentity) "Съобщения CRM" else context.getString(R.string.app_name),
            )
            .setIcon(
                Icon.createWithResource(
                    context,
                    if (smsIdentity) R.mipmap.ic_sms_launcher else R.mipmap.ic_launcher,
                ),
            )
            .setIntent(intent)
            .build()
    }

    private const val ACTION_OPEN_SMS_HOME = "com.onlineimoti.calllog.OPEN_SMS_HOME"
    private const val ACTION_OPEN_CALL_REPORT_HOME = "com.onlineimoti.calllog.OPEN_CALL_REPORT_HOME"
}
