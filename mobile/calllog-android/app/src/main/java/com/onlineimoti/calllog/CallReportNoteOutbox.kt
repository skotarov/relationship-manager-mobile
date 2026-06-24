package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, coalescing outbox for local notes belonging to contacts enabled for server sync.
 * An operation is persisted before WorkManager is scheduled, so offline changes are never lost.
 */
internal data class CallReportQueuedNote(
    val clientEventId: String,
    val phone: String,
    val direction: String,
    val occurredAtMs: Long,
    val durationSeconds: Long,
    val note: String,
    val contactName: String,
    val updatedAtMs: Long,
) {
    fun toSyncEvent(context: Context): CallReportSyncEvent {
        val deviceId = CallReportInstallationId.get(context)
        return CallReportSyncEvent(
            clientEventId = clientEventId,
            communicationType = "note",
            direction = direction,
            status = "",
            phone = phone,
            contactName = contactName,
            occurredAtMs = occurredAtMs,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            providerRowId = clientEventId,
            deviceId = deviceId,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}

internal object CallReportNoteOutbox {
    private const val PREFS = "callreport_note_outbox"
    private const val KEY_OPERATIONS = "operations_v1"
    private const val MAX_OPERATION_COUNT = 2_000
    private val lock = Any()

    /** Queue an upsert or a delete (blank [note]) for the contact's general note. */
    fun enqueueGeneral(context: Context, phone: String, note: String): Boolean {
        val appContext = context.applicationContext
        if (!CrmContactSyncStore.isEnabled(appContext, phone)) return false
        val phoneKey = phoneKey(phone)
        if (phoneKey.isBlank()) return false
        val installationId = CallReportInstallationId.get(appContext)
        return enqueue(
            context = appContext,
            operation = CallReportQueuedNote(
                clientEventId = "$installationId:note:general:$phoneKey",
                phone = phone,
                direction = "",
                occurredAtMs = System.currentTimeMillis(),
                durationSeconds = 0L,
                note = note.trim(),
                contactName = contactName(appContext, phone),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Queue an upsert or a delete (blank [note]) for one concrete local call note. */
    fun enqueueCall(
        context: Context,
        phone: String,
        note: String,
        direction: String,
        callAt: Long,
        durationSeconds: Long,
        clientNoteId: String = "",
    ): Boolean {
        val appContext = context.applicationContext
        if (!CrmContactSyncStore.isEnabled(appContext, phone)) return false
        if (phoneKey(phone).isBlank() || callAt <= 0L) return false
        val installationId = CallReportInstallationId.get(appContext)
        val stableLocalId = clientNoteId.ifBlank {
            LocalNotesFileStore.clientNoteIdForCall(phone, callAt, direction)
        }
        if (stableLocalId.isBlank()) return false
        return enqueue(
            context = appContext,
            operation = CallReportQueuedNote(
                clientEventId = "$installationId:note:call:$stableLocalId",
                phone = phone,
                direction = direction,
                occurredAtMs = callAt,
                durationSeconds = durationSeconds.coerceAtLeast(0L),
                note = note.trim(),
                contactName = contactName(appContext, phone),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** When sync is enabled after notes already exist, queue their current local state once. */
    fun enqueueCurrentLocalNotes(context: Context, phone: String) {
        val appContext = context.applicationContext
        if (!CrmContactSyncStore.isEnabled(appContext, phone)) return
        val generalNote = ContactNoteReader.generalNoteForPhone(appContext, phone)
        if (generalNote.isNotBlank()) enqueueGeneral(appContext, phone, generalNote)
        ContactNoteReader.callNotesForPhone(appContext, phone).forEach { callNote ->
            if (callNote.note.isNotBlank() && callNote.callAt > 0L) {
                enqueueCall(
                    context = appContext,
                    phone = phone,
                    note = callNote.note,
                    direction = callNote.direction,
                    callAt = callNote.callAt,
                    durationSeconds = callNote.durationSeconds,
                    clientNoteId = callNote.clientNoteId,
                )
            }
        }
    }

    /** Stop future sync for a contact as soon as the user turns its sync switch off. */
    fun removeForPhone(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        synchronized(lock) {
            val remaining = readLocked(context).filterNot { operation -> phoneKey(operation.phone) == key }
            writeLocked(context, remaining)
        }
    }

    internal fun takeBatch(context: Context, limit: Int): List<CallReportQueuedNote> {
        synchronized(lock) {
            return readLocked(context)
                .sortedBy { operation -> operation.updatedAtMs }
                .take(limit.coerceIn(1, 50))
        }
    }

    internal fun acknowledge(context: Context, clientEventIds: Collection<String>) {
        if (clientEventIds.isEmpty()) return
        val acknowledged = clientEventIds.toSet()
        synchronized(lock) {
            val remaining = readLocked(context).filterNot { operation -> operation.clientEventId in acknowledged }
            writeLocked(context, remaining)
        }
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) { readLocked(context).isNotEmpty() }

    private fun enqueue(context: Context, operation: CallReportQueuedNote): Boolean {
        synchronized(lock) {
            val operations = readLocked(context)
                .filterNot { item -> item.clientEventId == operation.clientEventId }
                .toMutableList()
            operations += operation
            writeLocked(context, operations.takeLast(MAX_OPERATION_COUNT))
        }
        CallReportNoteOutboxScheduler.enqueue(context, reason = "note_changed")
        return true
    }

    private fun readLocked(context: Context): List<CallReportQueuedNote> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OPERATIONS, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val operation = json.toOperation() ?: continue
                add(operation)
            }
        }
    }

    private fun writeLocked(context: Context, operations: List<CallReportQueuedNote>) {
        val payload = JSONArray().apply {
            operations.forEach { operation -> put(operation.toJson()) }
        }
        // commit() intentionally persists the outbox before any background network attempt begins.
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OPERATIONS, payload.toString())
            .commit()
    }

    private fun CallReportQueuedNote.toJson(): JSONObject = JSONObject().apply {
        put("client_event_id", clientEventId)
        put("phone", phone)
        put("direction", direction)
        put("occurred_at_ms", occurredAtMs)
        put("duration_seconds", durationSeconds)
        put("note", note)
        put("contact_name", contactName)
        put("updated_at_ms", updatedAtMs)
    }

    private fun JSONObject.toOperation(): CallReportQueuedNote? {
        val id = optString("client_event_id").trim()
        val phone = optString("phone").trim()
        if (id.isBlank() || phoneKey(phone).isBlank()) return null
        return CallReportQueuedNote(
            clientEventId = id,
            phone = phone,
            direction = optString("direction").trim(),
            occurredAtMs = optLong("occurred_at_ms", 0L).takeIf { value -> value > 0L } ?: System.currentTimeMillis(),
            durationSeconds = optLong("duration_seconds", 0L).coerceAtLeast(0L),
            note = optString("note"),
            contactName = optString("contact_name").trim(),
            updatedAtMs = optLong("updated_at_ms", 0L).takeIf { value -> value > 0L } ?: System.currentTimeMillis(),
        )
    }

    private fun contactName(context: Context, phone: String): String {
        return ContactGroupFilter.resolveDisplayName(context, phone).orEmpty().trim()
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { character -> character.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
