package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * Small bounded cache for one contact's History screen.
 *
 * The complete prepared state lives only in process memory. A compact local-only snapshot is also
 * persisted so a newly created History activity can show useful rows before querying Android's
 * CallLog, SMS and contacts providers again.
 */
internal data class HistoryCachedState(
    val local: HistoryLocalSnapshot,
    val serverHistory: CallReportHistoryLookupResult = CallReportHistoryLookupResult(),
    val serverLoaded: Boolean = false,
    val prepared: HistoryPreparedSnapshot = HistoryPreparedSnapshot(),
    val remoteSignature: String = "",
)

internal object HistorySnapshotCache {
    private const val PREFS = "relationship_manager_history_snapshot_cache_v1"
    private const val INDEX_KEY = "cached_phone_keys"
    private const val ENTRY_PREFIX = "phone_"
    private const val MAX_MEMORY_ENTRIES = 16
    private const val MAX_DISK_ENTRIES = 12
    private const val MAX_CACHE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_CACHED_CALLS = 120
    private const val MAX_CACHED_SMS = 100
    private const val MAX_CACHED_NOTES = 120

    private val lock = Any()
    private val memory = object : LinkedHashMap<String, HistoryCachedState>(MAX_MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HistoryCachedState>?): Boolean =
            size > MAX_MEMORY_ENTRIES
    }

    fun memoryState(phone: String): HistoryCachedState? {
        val key = phoneKey(phone)
        if (key.isBlank()) return null
        return synchronized(lock) { memory[key] }
    }

    fun putMemory(phone: String, state: HistoryCachedState) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        synchronized(lock) { memory[key] = state }
    }

    fun readLocal(context: Context, phone: String): HistoryLocalSnapshot? {
        val key = phoneKey(phone)
        if (key.isBlank()) return null
        val appContext = context.applicationContext
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(entryKey(key), "")
            .orEmpty()
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val savedAtMs = json.optLong("saved_at_ms", 0L)
        if (savedAtMs <= 0L || System.currentTimeMillis() - savedAtMs > MAX_CACHE_AGE_MS) {
            removeDiskEntry(appContext, key)
            return null
        }
        val parsed = runCatching { localSnapshotFromJson(json) }.getOrNull() ?: return null
        return sanitizeForCurrentPermissions(appContext, parsed)
    }

    /** Called from the History background loader, never from render(). */
    fun writeLocal(context: Context, phone: String, snapshot: HistoryLocalSnapshot) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            val index = readIndex(prefs.getString(INDEX_KEY, "[]").orEmpty())
            index.remove(key)
            index.add(key)
            val removed = mutableListOf<String>()
            while (index.size > MAX_DISK_ENTRIES) removed += index.removeAt(0)
            val editor = prefs.edit()
                .putString(entryKey(key), localSnapshotToJson(trimForDisk(snapshot)).toString())
                .putString(INDEX_KEY, JSONArray(index).toString())
            removed.forEach { oldKey -> editor.remove(entryKey(oldKey)) }
            editor.commit()
        }
    }

    fun remoteSignature(config: AppConfig): String {
        if (!CallReportRemoteAccess.isEnabled(config)) return ""
        val base = config.baseUrl.trim().trimEnd('/').lowercase()
        return "$base|${config.accessToken.hashCode()}"
    }

    private fun trimForDisk(snapshot: HistoryLocalSnapshot): HistoryLocalSnapshot {
        val calls = snapshot.calls.take(MAX_CACHED_CALLS)
        return snapshot.copy(
            calls = calls,
            latestCall = calls.firstOrNull() ?: snapshot.latestCall,
            sms = snapshot.sms.take(MAX_CACHED_SMS),
            callNotes = snapshot.callNotes.take(MAX_CACHED_NOTES),
        )
    }

    private fun sanitizeForCurrentPermissions(context: Context, snapshot: HistoryLocalSnapshot): HistoryLocalSnapshot {
        val calls = if (PhoneCallReader.hasCallLogPermission(context)) snapshot.calls else emptyList()
        val sms = if (SmsMessageReader.hasReadSmsPermission(context)) snapshot.sms else emptyList()
        val contactsAllowed = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        return snapshot.copy(
            calls = calls,
            latestCall = calls.firstOrNull(),
            sms = sms,
            contactExists = contactsAllowed && snapshot.contactExists,
        )
    }

    private fun localSnapshotToJson(snapshot: HistoryLocalSnapshot): JSONObject = JSONObject().apply {
        put("saved_at_ms", System.currentTimeMillis())
        put("calls", JSONArray().apply { snapshot.calls.forEach { put(callToJson(it)) } })
        put("sms", JSONArray().apply { snapshot.sms.forEach { put(smsToJson(it)) } })
        put("notes", JSONArray().apply { snapshot.callNotes.forEach { put(noteToJson(it)) } })
        put("general_note", snapshot.generalNote)
        put("general_note_pending", snapshot.generalNotePending)
        put("contact_exists", snapshot.contactExists)
        put("company_scope_available", snapshot.companyScopeAvailable)
    }

    private fun localSnapshotFromJson(json: JSONObject): HistoryLocalSnapshot {
        val calls = json.optJSONArray("calls").objects(::callFromJson)
        val sms = json.optJSONArray("sms").objects(::smsFromJson)
        val notes = json.optJSONArray("notes").objects(::noteFromJson)
        return HistoryLocalSnapshot(
            calls = calls,
            latestCall = calls.firstOrNull(),
            sms = sms,
            callNotes = notes,
            generalNote = json.optString("general_note").trim(),
            generalNotePending = json.optBoolean("general_note_pending", false),
            contactExists = json.optBoolean("contact_exists", false),
            companyScopeAvailable = json.optBoolean("company_scope_available", false),
        )
    }

    private fun callToJson(call: PhoneCallRecord): JSONObject = JSONObject().apply {
        put("number", call.number)
        put("name", call.name)
        put("direction", call.direction)
        put("started_at", call.startedAt)
        put("duration", call.durationSeconds)
        put("sms_body", call.smsBody)
        put("provider_id", call.providerId)
        put("call_type", call.callType)
        put("search_snippet", call.searchSnippet)
    }

    private fun callFromJson(json: JSONObject): PhoneCallRecord = PhoneCallRecord(
        number = json.optString("number"),
        name = json.optString("name"),
        direction = json.optString("direction"),
        startedAt = json.optLong("started_at", 0L),
        durationSeconds = json.optLong("duration", 0L),
        smsBody = json.optString("sms_body"),
        providerId = json.optString("provider_id"),
        callType = json.optInt("call_type", 0),
        searchSnippet = json.optString("search_snippet"),
    )

    private fun smsToJson(sms: SmsMessageRecord): JSONObject = JSONObject().apply {
        put("body", sms.body)
        put("timestamp", sms.timestampMs)
        put("type", sms.type)
        put("provider_id", sms.providerId)
    }

    private fun smsFromJson(json: JSONObject): SmsMessageRecord = SmsMessageRecord(
        body = json.optString("body"),
        timestampMs = json.optLong("timestamp", 0L),
        type = json.optInt("type", 0),
        providerId = json.optString("provider_id"),
    )

    private fun noteToJson(note: ContactCallNote): JSONObject = JSONObject().apply {
        put("note", note.note)
        put("call_at", note.callAt)
        put("saved_at", note.savedAt)
        put("direction", note.direction)
        put("duration", note.durationSeconds)
        put("client_note_id", note.clientNoteId)
        put("company_id", note.companyId)
        put("server_client_event_id", note.serverClientEventId)
    }

    private fun noteFromJson(json: JSONObject): ContactCallNote = ContactCallNote(
        note = json.optString("note"),
        callAt = json.optLong("call_at", 0L),
        savedAt = json.optLong("saved_at", 0L),
        direction = json.optString("direction"),
        durationSeconds = json.optLong("duration", 0L),
        clientNoteId = json.optString("client_note_id"),
        companyId = json.optString("company_id"),
        serverClientEventId = json.optString("server_client_event_id"),
    )

    private fun <T> JSONArray?.objects(parser: (JSONObject) -> T): List<T> = buildList {
        val source = this@objects ?: return@buildList
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.let { item -> runCatching { parser(item) }.getOrNull()?.let(::add) }
        }
    }

    private fun readIndex(raw: String): MutableList<String> {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct().toMutableList()
    }

    private fun removeDiskEntry(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            val index = readIndex(prefs.getString(INDEX_KEY, "[]").orEmpty()).apply { remove(key) }
            prefs.edit()
                .remove(entryKey(key))
                .putString(INDEX_KEY, JSONArray(index).toString())
                .apply()
        }
    }

    private fun entryKey(key: String): String = "$ENTRY_PREFIX$key"

    private fun phoneKey(phone: String): String = HomeCallPageLoader.noteKey(phone)
}
