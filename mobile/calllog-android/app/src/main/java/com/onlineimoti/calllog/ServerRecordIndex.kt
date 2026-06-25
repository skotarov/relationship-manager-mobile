package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray

/**
 * Persistent local index of records that the Call Report server has acknowledged.
 *
 * It is only a UI/sync optimisation: source SMS, call logs and notes remain in
 * Android/the local note store. The index is intentionally bounded so a device
 * that runs the app for months cannot accumulate an ever-growing JSON blob.
 */
internal object ServerRecordIndex {
    private const val PREFS = "callreport_server_record_index"
    private const val KEY_CONFIRMED_IDS = "confirmed_client_event_ids_v1"
    private const val KEY_CONFIRMATION_VERSION = "confirmation_version"
    private const val MAX_CONFIRMED_IDS = 5_000
    private val lock = Any()

    fun markConfirmed(context: Context, clientEventIds: Collection<String>) {
        val ids = clientEventIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        synchronized(lock) {
            val known = readLocked(context)
            val changed = known.addAll(ids)
            writeLocked(context, trimToLimit(known), changed)
        }
    }

    /** Removes excess legacy/current IDs even when the next sync batch is empty. */
    fun prune(context: Context) {
        synchronized(lock) {
            val known = readLocked(context)
            val trimmed = trimToLimit(known)
            if (trimmed.size != known.size) {
                writeLocked(context, trimmed, changed = true)
            }
        }
    }

    /**
     * A new local edit supersedes the server-confirmed version for the same stable ID.
     * Its cloud badge returns only after sync.php confirms this new version.
     */
    fun markPending(context: Context, clientEventId: String) {
        val id = clientEventId.trim()
        if (id.isBlank()) return
        synchronized(lock) {
            val known = readLocked(context)
            val changed = known.remove(id)
            writeLocked(context, known, changed)
        }
    }

    /** Changes whenever the persisted acknowledgement state changes. */
    fun confirmationVersion(context: Context): Long {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CONFIRMATION_VERSION, 0L)
    }

    fun isConfirmed(context: Context, clientEventId: String): Boolean {
        if (clientEventId.isBlank() || !CallReportRemoteAccess.isEnabled(context)) return false
        synchronized(lock) {
            return clientEventId in readLocked(context)
        }
    }

    fun isCommunicationConfirmed(context: Context, record: PhoneCallRecord): Boolean {
        val providerId = record.providerId.trim()
        if (providerId.isBlank()) return false
        val type = if (record.isSms) "sms" else "phone"
        return isConfirmed(context, communicationEventId(context, type, providerId))
    }

    fun isGeneralNoteConfirmed(context: Context, phone: String): Boolean {
        return isConfirmed(context, generalNoteEventId(context, phone))
    }

    fun isCallNoteConfirmed(context: Context, phone: String, note: ContactCallNote): Boolean {
        val clientNoteId = note.clientNoteId.ifBlank {
            LocalNotesFileStore.clientNoteIdForCall(phone, note.callAt, note.direction)
        }
        if (clientNoteId.isBlank()) return false
        return isConfirmed(context, callNoteEventId(context, clientNoteId))
    }

    fun isCallNoteConfirmed(context: Context, phone: String, callAt: Long, direction: String): Boolean {
        val clientNoteId = LocalNotesFileStore.clientNoteIdForCall(phone, callAt, direction)
        if (clientNoteId.isBlank()) return false
        return isConfirmed(context, callNoteEventId(context, clientNoteId))
    }

    fun communicationEventId(context: Context, communicationType: String, providerId: String): String {
        return "${CallReportInstallationId.get(context.applicationContext)}:$communicationType:${providerId.trim()}"
    }

    fun generalNoteEventId(context: Context, phone: String): String {
        return "${CallReportInstallationId.get(context.applicationContext)}:note:general:${phoneKey(phone)}"
    }

    fun callNoteEventId(context: Context, clientNoteId: String): String {
        return "${CallReportInstallationId.get(context.applicationContext)}:note:call:${clientNoteId.trim()}"
    }

    private fun readLocked(context: Context): LinkedHashSet<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED_IDS, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return linkedSetOf<String>().apply {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun trimToLimit(ids: LinkedHashSet<String>): LinkedHashSet<String> {
        if (ids.size <= MAX_CONFIRMED_IDS) return ids
        val kept = LinkedHashSet<String>(MAX_CONFIRMED_IDS)
        ids.toList().takeLast(MAX_CONFIRMED_IDS).forEach(kept::add)
        return kept
    }

    private fun writeLocked(context: Context, ids: Set<String>, changed: Boolean) {
        val array = JSONArray().apply {
            // Preserve insertion order so trimToLimit keeps the newest confirmations.
            ids.forEach { value -> put(value) }
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putString(KEY_CONFIRMED_IDS, array.toString())
        if (changed) editor.putLong(KEY_CONFIRMATION_VERSION, System.currentTimeMillis())
        editor.commit()
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
