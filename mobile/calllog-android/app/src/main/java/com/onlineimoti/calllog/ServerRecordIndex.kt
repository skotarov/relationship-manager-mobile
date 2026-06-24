package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray

/**
 * Persistent local index of records that the Call Report server has acknowledged.
 * It deliberately survives disabling contact sync: the switch controls future uploads,
 * while this index records historical server presence for UI cloud badges.
 */
internal object ServerRecordIndex {
    private const val PREFS = "callreport_server_record_index"
    private const val KEY_CONFIRMED_IDS = "confirmed_client_event_ids_v1"
    private const val KEY_CONFIRMATION_VERSION = "confirmation_version"
    private val lock = Any()

    fun markConfirmed(context: Context, clientEventIds: Collection<String>) {
        val ids = clientEventIds.map { it.trim() }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        synchronized(lock) {
            val known = readLocked(context).toMutableSet()
            val changed = known.addAll(ids)
            writeLocked(context, known, changed)
        }
    }

    /** Changes whenever a server-confirmed record is newly added to the local index. */
    fun confirmationVersion(context: Context): Long {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CONFIRMATION_VERSION, 0L)
    }

    fun isConfirmed(context: Context, clientEventId: String): Boolean {
        if (clientEventId.isBlank()) return false
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

    private fun writeLocked(context: Context, ids: Set<String>, changed: Boolean) {
        val array = JSONArray().apply {
            ids.sorted().forEach { value -> put(value) }
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
