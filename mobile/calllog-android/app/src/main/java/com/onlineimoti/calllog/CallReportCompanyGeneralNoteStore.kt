package com.onlineimoti.calllog

import android.content.Context

/**
 * Small local cache for the server-scoped main note of a phone inside one company.
 * It keeps the newly saved value visible while the durable topic outbox is waiting
 * for the server. It is never used for the ordinary local-only main note.
 */
internal object CallReportCompanyGeneralNoteStore {
    private const val PREFS = "callreport_company_general_notes_v1"
    private const val SAVED_AT_SUFFIX = "#saved_at_ms"

    fun noteFor(context: Context, phone: String, companyId: String): String {
        val key = key(phone, companyId)
        if (key.isBlank()) return ""
        return preferences(context).getString(key, "").orEmpty().trim()
    }

    fun savedAtMsFor(context: Context, phone: String, companyId: String): Long {
        val key = key(phone, companyId)
        if (key.isBlank()) return 0L
        return preferences(context).getLong(savedAtKey(key), 0L).coerceAtLeast(0L)
    }

    fun saveOrDelete(context: Context, phone: String, companyId: String, note: String): Boolean {
        val key = key(phone, companyId)
        if (key.isBlank()) return false
        val edit = preferences(context).edit()
        val value = note.trim()
        if (value.isBlank()) {
            edit.remove(key).remove(savedAtKey(key))
        } else {
            edit.putString(key, value).putLong(savedAtKey(key), System.currentTimeMillis())
        }
        return edit.commit()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savedAtKey(key: String) = "$key$SAVED_AT_SUFFIX"

    private fun key(phone: String, companyId: String): String {
        val phoneKey = phone.filter { it.isDigit() }.let { if (it.length > 9) it.takeLast(9) else it }
        val companyKey = companyId.trim()
        return if (phoneKey.isBlank() || companyKey.isBlank()) "" else "$phoneKey|$companyKey"
    }
}
