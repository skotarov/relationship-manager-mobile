package com.onlineimoti.calllog

import android.content.Context

internal object CrmContactSyncStore {
    private const val PREFS = "crm_contact_sync"

    fun isEnabled(context: Context, phone: String): Boolean {
        val key = phoneKey(phone)
        if (key.isBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
    }

    /** Returns all phones whose per-contact CRM switch is currently enabled. */
    fun enabledPhoneKeys(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all
            .asSequence()
            .filter { entry -> entry.value as? Boolean == true }
            .map { entry -> entry.key }
            .filter { key -> key.isNotBlank() }
            .toSet()
    }

    fun setEnabled(context: Context, phone: String, enabled: Boolean) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, enabled).apply()
        if (previous != enabled) HomeCrmCompanyMembershipStore.invalidate(context, phone)
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
