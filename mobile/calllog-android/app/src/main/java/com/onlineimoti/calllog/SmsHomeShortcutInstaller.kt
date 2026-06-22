package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.Toast

/**
 * Adds the Call Report SMS icon to the home screen through the launcher.
 *
 * Android does not permit one application to write directly into the private icon storage of a
 * launcher or another application. We first use the legacy launcher request when it is exposed
 * (common on manufacturer launchers), then fall back to Android's pinned-shortcut API.
 */
internal object SmsHomeShortcutInstaller {
    private const val SHORTCUT_ID = "callreport_sms_home"
    private const val LEGACY_INSTALL_SHORTCUT_ACTION = "com.android.launcher.action.INSTALL_SHORTCUT"

    fun request(context: Context, setStatus: (String) -> Unit) {
        val activity = context as? Activity
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            requestFromLauncher(context, setStatus)
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Добави икона „Съобщения“")
            .setMessage(
                "Ще поискаме от началния екран да добави икона „Съобщения“ с нашата SMS иконка. " +
                    "След това можеш да я преместиш на мястото на старата SMS икона.",
            )
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Добави") { _, _ ->
                requestFromLauncher(context, setStatus)
            }
            .show()
    }

    private fun requestFromLauncher(context: Context, setStatus: (String) -> Unit) {
        val launchIntent = Intent(context, HomeActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        if (requestLegacyLauncherShortcut(context, launchIntent)) {
            showMessage(
                context,
                setStatus,
                "Изпратих заявка към началния екран за иконата „Съобщения“. Провери началния екран.",
            )
            return
        }

        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
            showMessage(
                context,
                setStatus,
                "Текущият начален екран не позволява автоматично добавяне на икона. " +
                    "Отвори списъка с приложения и добави Call Report ръчно.",
            )
            return
        }

        val shortcut = ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel("Съобщения")
            .setLongLabel("Съобщения — Call Report")
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_sms_launcher))
            .setIntent(launchIntent)
            .build()

        try {
            val accepted = shortcutManager.requestPinShortcut(shortcut, null)
            if (accepted) {
                showMessage(
                    context,
                    setStatus,
                    "Началният екран трябва да покаже заявка за добавяне на „Съобщения“.",
                )
            } else {
                showMessage(
                    context,
                    setStatus,
                    "Началният екран отказа заявката за добавяне на иконата.",
                )
            }
        } catch (error: Throwable) {
            showMessage(
                context,
                setStatus,
                "Не успях да поискам добавяне на иконата: ${error.message.orEmpty()}",
            )
        }
    }

    private fun requestLegacyLauncherShortcut(context: Context, launchIntent: Intent): Boolean {
        val legacyIntent = Intent(LEGACY_INSTALL_SHORTCUT_ACTION).apply {
            putExtra(Intent.EXTRA_SHORTCUT_NAME, "Съобщения")
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
            putExtra(
                Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(context, R.mipmap.ic_sms_launcher),
            )
            putExtra("duplicate", false)
        }
        val hasReceiver = context.packageManager
            .queryBroadcastReceivers(legacyIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()
        if (!hasReceiver) return false

        return runCatching {
            context.sendBroadcast(legacyIntent)
            true
        }.getOrDefault(false)
    }

    private fun showMessage(context: Context, setStatus: (String) -> Unit, message: String) {
        setStatus(message)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
