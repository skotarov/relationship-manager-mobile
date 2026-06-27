package com.onlineimoti.calllog

import android.content.Context

/** Local immediate cache of the single company selected for an SMS. */
internal object SmsCompanyAssignmentStore {
    private const val PREFS = "callreport_sms_company_assignments"

    fun companyIdFor(context: Context, phone: String, providerId: String): String {
        val key = key(phone, providerId) ?: return ""
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, "")
            .orEmpty()
            .trim()
    }

    fun save(context: Context, phone: String, providerId: String, companyId: String): Boolean {
        val key = key(phone, providerId) ?: return false
        val value = companyId.trim()
        if (value.isBlank()) return false
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
        return true
    }

    private fun key(phone: String, providerId: String): String? {
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        val smsId = providerId.trim()
        if (phoneKey.isBlank() || smsId.isBlank()) return null
        return "$phoneKey:$smsId"
    }
}
