package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A durable local reminder for notes saved while no live or cached company list
 * was available. It never uploads anything until the broker explicitly chooses a firm.
 */
internal data class DeferredCompanyAssignment(
    val recordId: String,
    val phone: String,
    val direction: String,
    val callAtMs: Long,
    val durationSeconds: Long,
    val isGeneralNote: Boolean,
    val updatedAtMs: Long,
)

internal object CallReportDeferredCompanyAssignmentStore {
    private const val PREFS = "callreport_deferred_company_assignment"
    private const val KEY_ENTRIES = "entries_v1"
    private val lock = Any()

    fun markGeneral(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        upsert(
            context = context,
            entry = DeferredCompanyAssignment(
                recordId = "general:$key",
                phone = phone,
                direction = "",
                callAtMs = 0L,
                durationSeconds = 0L,
                isGeneralNote = true,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    fun markCall(
        context: Context,
        phone: String,
        direction: String,
        callAtMs: Long,
        durationSeconds: Long,
    ) {
        val stableId = LocalNotesFileStore.clientNoteIdForCall(phone, callAtMs, direction)
        if (stableId.isBlank()) return
        upsert(
            context = context,
            entry = DeferredCompanyAssignment(
                recordId = "call:$stableId",
                phone = phone,
                direction = direction,
                callAtMs = callAtMs,
                durationSeconds = durationSeconds.coerceAtLeast(0L),
                isGeneralNote = false,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    fun clearGeneral(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        remove(context) { it.recordId == "general:$key" }
    }

    fun clearCall(context: Context, phone: String, direction: String, callAtMs: Long) {
        val stableId = LocalNotesFileStore.clientNoteIdForCall(phone, callAtMs, direction)
        if (stableId.isBlank()) return
        remove(context) { it.recordId == "call:$stableId" }
    }

    fun isCallPending(context: Context, phone: String, direction: String, callAtMs: Long): Boolean {
        val stableId = LocalNotesFileStore.clientNoteIdForCall(phone, callAtMs, direction)
        return stableId.isNotBlank() && entries(context).any { it.recordId == "call:$stableId" }
    }

    fun isGeneralPending(context: Context, phone: String): Boolean {
        val key = phoneKey(phone)
        return key.isNotBlank() && entries(context).any { it.recordId == "general:$key" }
    }

    fun count(context: Context): Int = entries(context).size

    private fun upsert(context: Context, entry: DeferredCompanyAssignment) = synchronized(lock) {
        val updated = readLocked(context).filterNot { it.recordId == entry.recordId }.toMutableList()
        updated += entry
        writeLocked(context, updated)
    }

    private fun remove(context: Context, predicate: (DeferredCompanyAssignment) -> Boolean) = synchronized(lock) {
        val entries = readLocked(context)
        val updated = entries.filterNot(predicate)
        if (updated.size != entries.size) writeLocked(context, updated)
    }

    private fun entries(context: Context): List<DeferredCompanyAssignment> = synchronized(lock) { readLocked(context) }

    private fun readLocked(context: Context): List<DeferredCompanyAssignment> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toEntry()?.let(::add)
            }
        }
    }

    private fun writeLocked(context: Context, entries: List<DeferredCompanyAssignment>) {
        val array = JSONArray().apply { entries.forEach { put(it.toJson()) } }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).commit()
    }

    private fun DeferredCompanyAssignment.toJson(): JSONObject = JSONObject().apply {
        put("record_id", recordId)
        put("phone", phone)
        put("direction", direction)
        put("call_at_ms", callAtMs)
        put("duration_seconds", durationSeconds)
        put("is_general_note", isGeneralNote)
        put("updated_at_ms", updatedAtMs)
    }

    private fun JSONObject.toEntry(): DeferredCompanyAssignment? {
        val recordId = optString("record_id").trim()
        val phone = optString("phone").trim()
        if (recordId.isBlank() || phoneKey(phone).isBlank()) return null
        return DeferredCompanyAssignment(
            recordId = recordId,
            phone = phone,
            direction = optString("direction").trim(),
            callAtMs = optLong("call_at_ms", 0L).coerceAtLeast(0L),
            durationSeconds = optLong("duration_seconds", 0L).coerceAtLeast(0L),
            isGeneralNote = optBoolean("is_general_note", false),
            updatedAtMs = optLong("updated_at_ms", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
