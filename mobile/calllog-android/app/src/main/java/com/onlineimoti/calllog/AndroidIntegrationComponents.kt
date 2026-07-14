package com.onlineimoti.calllog

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Keeps optional Android entry points in sync with the user's integration choices. */
internal object AndroidIntegrationComponents {
    fun apply(context: Context, config: AppConfig) {
        val appContext = context.applicationContext
        setEnabled(appContext, PhoneNumberActionActivity::class.java, config.useLinkedContactIntegration)
        setEnabled(appContext, ContactShareActivity::class.java, config.useContactShareIntegration)
    }

    private fun setEnabled(context: Context, clazz: Class<*>, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, clazz),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
