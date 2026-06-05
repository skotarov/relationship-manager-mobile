package com.onlineimoti.calllog

import android.content.Context

internal object CrmContactSyncStore {
    private const val PREFS = "crm_contact_sync"

    fun isEnabled(context: Context, phone: String): Boolean {
        val key = phoneKey(phone)
        if (key.isBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
    }

    fun setEnabled(context: Context, phone: String, enabled: Boolean) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, enabled).apply()
    }

    fun toggle(context: Context, phone: String): Boolean {
        val enabled = !isEnabled(context, phone)
        setEnabled(context, phone, enabled)
        return enabled
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
