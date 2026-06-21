package com.onlineimoti.calllog

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches Call Report's own launcher entry between its normal identity and an SMS/CRM identity.
 * This stays inside Android's supported component model and does not modify Xiaomi Themes or any
 * other application's icon.
 */
internal object SmsLauncherIconController {
    private const val DEFAULT_ALIAS = "com.onlineimoti.calllog.DefaultLauncherAlias"
    private const val SMS_ALIAS = "com.onlineimoti.calllog.SmsLauncherAlias"

    fun isSmsIconActive(context: Context): Boolean {
        return context.packageManager.getComponentEnabledSetting(ComponentName(context, SMS_ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    fun setSmsIconActive(context: Context, active: Boolean) {
        val packageManager = context.packageManager
        val defaultAlias = ComponentName(context, DEFAULT_ALIAS)
        val smsAlias = ComponentName(context, SMS_ALIAS)

        if (active) {
            // Enable the new launcher entry before hiding the old one, so there is always a way
            // back to the app even if the launcher refreshes between the two calls.
            packageManager.setComponentEnabledSetting(
                smsAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            packageManager.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        } else {
            packageManager.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            packageManager.setComponentEnabledSetting(
                smsAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
