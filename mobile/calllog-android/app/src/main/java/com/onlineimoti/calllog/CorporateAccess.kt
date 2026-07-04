package com.onlineimoti.calllog

import android.content.Context

/**
 * The Play-facing app treats phone/call-log functions as a signed-in corporate
 * CRM capability, never as a free personal call tracker.
 */
object CorporateAccess {
    fun isActive(context: Context): Boolean {
        val config = ConfigStore.load(context.applicationContext)
        return config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
    }
}
