package com.onlineimoti.calllog

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived hand-off from the incoming-call lookup coordinator to the overlay.
 * It avoids a second history request after lookup.php has already started one.
 */
internal object IncomingLookupPopupRowsCache {
    private const val TTL_MS = 90_000L
    private val rowsByPhone = ConcurrentHashMap<String, Entry>()

    fun put(phone: String, rows: List<PostCallLookupRemoteRow>) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        rowsByPhone[key] = Entry(System.currentTimeMillis(), rows)
        pruneExpired()
    }

    fun rowsFor(phone: String): List<PostCallLookupRemoteRow> {
        val key = phoneKey(phone)
        if (key.isBlank()) return emptyList()
        val entry = rowsByPhone[key] ?: return emptyList()
        if (System.currentTimeMillis() - entry.storedAtMs > TTL_MS) {
            rowsByPhone.remove(key, entry)
            return emptyList()
        }
        return entry.rows
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        rowsByPhone.entries.removeIf { (_, entry) -> now - entry.storedAtMs > TTL_MS }
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private data class Entry(
        val storedAtMs: Long,
        val rows: List<PostCallLookupRemoteRow>,
    )
}
