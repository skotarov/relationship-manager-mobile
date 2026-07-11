package com.onlineimoti.calllog

import android.content.Context

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
            .filter { result -> terms.matches(result.note, result.phone, result.phoneKey, PhoneNormalizer.display(result.phone)) }
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
        val fromActiveStore = LocalNotesFileStore.storedGeneralNotes(context).map { note ->
            StoredNoteSearchResult(
                phone = note.phone,
                phoneKey = note.phoneKey,
                note = note.note,
                noteAt = note.noteAt,
                callAt = 0L,
                direction = "",
                durationSeconds = 0L,
                isCallNote = false,
            )
        }
        // Once an external/public local notes folder is active, search must not mix
        // in SharedPreferences from the app installation. Otherwise filters find
        // stale notes from the private install while History/Call Log use the folder.
        if (usesExternalLocalNotesStore(context)) return fromActiveStore

        val prefs = context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE)
        val fromPrefs = prefs.all.mapNotNull { (key, value) ->
            val note = (value as? String).orEmpty().trim()
            val phoneKey = PhoneNormalizer.key(key)
            if (phoneKey.isBlank() || note.isBlank()) return@mapNotNull null
            StoredNoteSearchResult(
                phone = key,
                phoneKey = phoneKey,
                note = note,
                noteAt = 0L,
                callAt = 0L,
                direction = "",
                durationSeconds = 0L,
                isCallNote = false,
            )
        }
        return (fromPrefs + fromActiveStore).distinctBy { it.phoneKey to it.note }
    }

    private fun callNotes(context: Context): List<StoredNoteSearchResult> {
        return LocalNotesFileStore.storedCallNotes(context).map { note ->
            StoredNoteSearchResult(
                phone = note.phone,
                phoneKey = note.phoneKey,
                note = note.note,
                noteAt = note.noteAt,
                callAt = note.callAt,
                direction = note.direction,
                durationSeconds = note.durationSeconds,
                isCallNote = true,
            )
        }
    }

    private fun usesExternalLocalNotesStore(context: Context): Boolean {
        return LocalNotesFileStore.usesSelectedFolder(context) || LocalNotesFileStore.usesPublicFolder(context)
    }
}
