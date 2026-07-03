package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale

internal data class ContactSearchResult(
    val phone: String,
    val name: String,
    /** Full provider-normalized number retained for digit searches. */
    val normalizedPhone: String = "",
)

/**
 * Searches all device contacts, but retains a short-lived in-memory snapshot so
 * typing one query character at a time does not rescan the Contacts provider.
 */
internal object ContactSearchProvider {
    private const val CACHE_MS = 30_000L
    private val cacheLock = Any()
    private var cachedContacts: List<ContactSearchResult> = emptyList()
    private var cachedAtMs = 0L

    fun search(context: Context, query: String): List<ContactSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val lowerQuery = trimmed.lowercase(Locale.getDefault())
        val digitsQuery = trimmed.filter { it.isDigit() }
        return allContacts(context)
            .asSequence()
            .filter { result -> matches(result.name, result.phone, result.normalizedPhone, lowerQuery, digitsQuery) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.phone } })
            .toList()
    }

    fun invalidate() {
        synchronized(cacheLock) {
            cachedContacts = emptyList()
            cachedAtMs = 0L
        }
    }

    private fun allContacts(context: Context): List<ContactSearchResult> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            if (cachedAtMs > 0L && now - cachedAtMs < CACHE_MS) return cachedContacts
        }
        val loaded = loadAllContacts(context)
        synchronized(cacheLock) {
            cachedContacts = loaded
            cachedAtMs = now
            return cachedContacts
        }
    }

    private fun loadAllContacts(context: Context): List<ContactSearchResult> {
        val results = linkedMapOf<String, ContactSearchResult>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val normalizedIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
            while (cursor.moveToNext()) {
                val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                val normalized = if (normalizedIndex >= 0) cursor.getString(normalizedIndex).orEmpty() else ""
                val phone = number.ifBlank { normalized }
                val key = noteKey(phone)
                if (key.isBlank() || results.containsKey(key)) continue
                results[key] = ContactSearchResult(
                    phone = phone,
                    name = name.ifBlank { phone },
                    normalizedPhone = normalized,
                )
            }
        }
        return results.values.toList()
    }

    private fun matches(name: String, number: String, normalized: String, lowerQuery: String, digitsQuery: String): Boolean {
        if (name.lowercase(Locale.getDefault()).contains(lowerQuery)) return true
        if (digitsQuery.length < 3) return false
        return number.filter { it.isDigit() }.contains(digitsQuery) || normalized.filter { it.isDigit() }.contains(digitsQuery)
    }

    private fun noteKey(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}
