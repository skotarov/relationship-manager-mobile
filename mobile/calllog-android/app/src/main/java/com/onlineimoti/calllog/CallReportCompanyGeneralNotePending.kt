package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray

/** Reads the durable topic-note queue only to decide whether one firm's cached main note is newer. */
internal object CallReportCompanyGeneralNotePending {
    private const val PREFS = "callreport_topic_note_outbox"
    private const val KEY_OPERATIONS = "operations_v1"

    fun isPending(context: Context, phone: String, companyId: String): Boolean {
        val phoneKey = phoneKey(phone)
        val targetCompanyId = companyId.trim()
        if (phoneKey.isBlank() || targetCompanyId.isBlank()) return false
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OPERATIONS, "[]")
            .orEmpty()
        val queue = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        for (index in 0 until queue.length()) {
            val item = queue.optJSONObject(index) ?: continue
            if (item.optString("company_id").trim() != targetCompanyId) continue
            if (phoneKey(item.optString("phone")) != phoneKey) continue
            if (item.optString("direction").trim().isNotBlank()) continue
            if (item.optLong("duration_seconds", 0L) > 0L) continue
            if (!item.optString("client_event_id").contains(":general:")) continue
            return true
        }
        return false
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
