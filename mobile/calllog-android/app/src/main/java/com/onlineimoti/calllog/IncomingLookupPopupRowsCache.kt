package com.onlineimoti.calllog

/**
 * Short-lived hand-off from the incoming-call lookup coordinator to the overlay.
 * It prevents duplicate server requests and keeps heavy local call-log reads off
 * the overlay UI thread.
 */
internal object IncomingLookupPopupRowsCache {
    private const val TTL_MS = 90_000L
    private val lock = Any()
    private val dataByPhone = mutableMapOf<String, Entry>()

    fun putRemoteRows(phone: String, rows: List<PostCallLookupRemoteRow>) {
        update(phone) { current -> current.copy(remoteRows = rows) }
    }

    fun putLocalRows(phone: String, rows: List<String>) {
        update(phone) { current -> current.copy(localRows = rows) }
    }

    fun remoteRowsFor(phone: String): List<PostCallLookupRemoteRow> = snapshot(phone)?.remoteRows.orEmpty()

    /** Null means the background local call-log scan has not completed yet. */
    fun localRowsFor(phone: String): List<String>? = snapshot(phone)?.localRows

    private fun update(phone: String, transform: (Entry) -> Entry) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        synchronized(lock) {
            pruneExpiredLocked(System.currentTimeMillis())
            val current = dataByPhone[key] ?: Entry()
            dataByPhone[key] = transform(current).copy(storedAtMs = System.currentTimeMillis())
        }
    }

    private fun snapshot(phone: String): Entry? {
        val key = phoneKey(phone)
        if (key.isBlank()) return null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pruneExpiredLocked(now)
            return dataByPhone[key]
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val expired = dataByPhone
            .filterValues { entry -> now - entry.storedAtMs > TTL_MS }
            .keys
        expired.forEach(dataByPhone::remove)
    }

    private fun phoneKey(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private data class Entry(
        val storedAtMs: Long = 0L,
        val remoteRows: List<PostCallLookupRemoteRow> = emptyList(),
        val localRows: List<String>? = null,
    )
}
