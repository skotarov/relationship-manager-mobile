package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.File

internal data class StoredNoteSearchResult(
    val phone: String,
    val phoneKey: String,
    val note: String,
    val noteAt: Long,
    val callAt: Long,
    val direction: String,
    val durationSeconds: Long,
    val isCallNote: Boolean,
)

/**
 * Keeps a small, short-lived local-note snapshot. Filtering still uses every
 * stored note; only repeated disk walks during a single typing session vanish.
 */
internal object StoredNoteSearchProvider {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"
    private const val CACHE_MS = 15_000L
    private val cacheLock = Any()
    private var cachedNotes: List<StoredNoteSearchResult> = emptyList()
    private var cachedAtMs = 0L

    fun search(context: Context, query: String): List<StoredNoteSearchResult> {
        val terms = SearchQueryTerms.from(query)
        if (terms.isEmpty) return emptyList()
        return allNotes(context)
            .asSequence()
            .filter { result -> terms.matches(result.note, result.phone, result.phoneKey) }
            .sortedByDescending { it.noteAt.takeIf { at -> at > 0L } ?: it.callAt }
            .toList()
    }

    fun invalidate() {
        synchronized(cacheLock) {
            cachedNotes = emptyList()
            cachedAtMs = 0L
        }
    }

    private fun allNotes(context: Context): List<StoredNoteSearchResult> {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            if (cachedAtMs > 0L && now - cachedAtMs < CACHE_MS) return cachedNotes
        }
        val loaded = generalNotes(context) + callNotes(context)
        synchronized(cacheLock) {
            cachedNotes = loaded
            cachedAtMs = now
            return cachedNotes
        }
    }

    private fun generalNotes(context: Context): List<StoredNoteSearchResult> {
        val prefs = context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            val note = (value as? String).orEmpty().trim()
            if (key.isBlank() || note.isBlank()) return@mapNotNull null
            StoredNoteSearchResult(
                phone = key,
                phoneKey = key,
                note = note,
                noteAt = 0L,
                callAt = 0L,
                direction = "",
                durationSeconds = 0L,
                isCallNote = false,
            )
        }
    }

    private fun callNotes(context: Context): List<StoredNoteSearchResult> {
        if (!LocalNotesFileStore.canUseConfiguredFolder(context)) return emptyList()
        val root = File(LocalNotesFileStore.activeRootPath(context), "notes")
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.name == "calllog.notes" }
            .flatMap { file -> parseCallNotes(file).asSequence() }
            .toList()
    }

    private fun parseCallNotes(file: File): List<StoredNoteSearchResult> {
        return runCatching {
            file.readLines().mapNotNull { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
                if (json.optString("type") != "call_note") return@mapNotNull null
                val note = json.optString("note").trim()
                if (note.isBlank()) return@mapNotNull null
                val phone = json.optString("phone").ifBlank { json.optString("normalized_phone") }
                val phoneKey = json.optString("normalized_phone").ifBlank { noteKey(phone) }
                if (phoneKey.isBlank()) return@mapNotNull null
                StoredNoteSearchResult(
                    phone = phone.ifBlank { phoneKey },
                    phoneKey = phoneKey,
                    note = note,
                    noteAt = json.optLong("at", file.lastModified()),
                    callAt = json.optLong("call_at", 0L),
                    direction = json.optString("direction"),
                    durationSeconds = json.optLong("duration", 0L),
                    isCallNote = true,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
