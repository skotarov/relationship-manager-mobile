package com.onlineimoti.calllog

import android.content.Context

/**
 * Phone/call-log processing is available only after cloud CRM access is configured.
 * A dedicated Play-business distribution may additionally require proof that the
 * token was issued by the company login flow on this device.
 */
object CorporateAccess {
    fun isActive(context: Context): Boolean {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        val configured = config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
        if (!configured) return false

        // Billing support is shared by the normal app and must not silently turn
        // every APK into the restricted Play-business product. Existing configured
        // installations keep working; only an explicitly restricted distribution
        // requires a token saved by CompanyAccountApi.applySession().
        return !DistributionCapabilities.isPlayBusinessBuild ||
            CompanySessionStore.isCurrent(appContext, config.accessToken)
    }
}
