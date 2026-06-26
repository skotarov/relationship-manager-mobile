package com.onlineimoti.calllog

import android.content.Context

/**
 * Small local cache for the server-scoped main note of a phone inside one company.
 * It keeps the newly saved value visible while the durable topic outbox is waiting
 * for the server. It is never used for the ordinary local-only main note.
 */
internal object CallReportCompanyGeneralNoteStore {
    private const val PREFS = "callreport_company_general_notes_v1"

    fun noteFor(context: Context, phone: String, companyId: String): String {
        val key = key(phone, companyId)
        if (key.isBlank()) return ""
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, "")
            .orEmpty()
            .trim()
    }

    fun saveOrDelete(context: Context, phone: String, companyId: String, note: String): Boolean {
        val key = key(phone, companyId)
        if (key.isBlank()) return false
        val edit = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val value = note.trim()
        if (value.isBlank()) edit.remove(key) else edit.putString(key, value)
        return edit.commit()
    }

    private fun key(phone: String, companyId: String): String {
        val phoneKey = phone.filter { it.isDigit() }.let { if (it.length > 9) it.takeLast(9) else it }
        val companyKey = companyId.trim()
        return if (phoneKey.isBlank() || companyKey.isBlank()) "" else "$phoneKey|$companyKey"
    }
}
