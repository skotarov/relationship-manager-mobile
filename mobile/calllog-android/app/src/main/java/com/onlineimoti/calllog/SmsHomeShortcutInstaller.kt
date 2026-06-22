package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * Adds a supported launcher shortcut for the application's SMS role.
 *
 * Android applications cannot copy an icon into another application's data or into a launcher's
 * private icon store. The launcher owns the home screen, so it must confirm the pin request.
 */
internal object SmsHomeShortcutInstaller {
    private const val SHORTCUT_ID = "callreport_sms_home"

    fun request(context: Context, setStatus: (String) -> Unit) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
            setStatus(
                "Текущият начален екран не позволява добавяне на икона от приложението. " +
                    "Добави Call Report ръчно на началния екран.",
            )
            return
        }

        val launchIntent = Intent(context, HomeActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel("Съобщения")
            .setLongLabel("Съобщения — Call Report")
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_sms_launcher))
            .setIntent(launchIntent)
            .build()

        if (shortcutManager.pinnedShortcuts.any { it.id == SHORTCUT_ID }) {
            shortcutManager.updateShortcuts(listOf(shortcut))
            setStatus("Иконата „Съобщения“ вече е добавена на началния екран.")
            return
        }

        val requested = shortcutManager.requestPinShortcut(shortcut, null)
        if (requested) {
            setStatus("Потвърди добавянето на иконата „Съобщения“ на началния екран.")
        } else {
            setStatus("Началният екран не прие заявката за добавяне на иконата.")
        }
    }
}
