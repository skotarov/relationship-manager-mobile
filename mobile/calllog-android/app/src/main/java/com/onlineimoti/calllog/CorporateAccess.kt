package com.onlineimoti.calllog

import android.content.Context

/**
 * The Play-facing app treats phone/call-log functions as a signed-in corporate
 * CRM capability, never as a free personal call tracker.
 */
object CorporateAccess {
    fun isActive(context: Context): Boolean {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        val configured = config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
        if (!configured) return false
        // Existing sideloaded/internal installations keep their legacy device-token
        // compatibility. The Play product additionally requires a token produced
        // by the company login or invitation flow on this device.
        return !BuildConfig.PLAY_BILLING_ENABLED || CompanySessionStore.isCurrent(appContext, config.accessToken)
    }
}
