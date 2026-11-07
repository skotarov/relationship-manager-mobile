package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Addresses belonging to real device contacts never enter the RM cloud store. */
internal object ContactAddressReader {
    private const val PREFS = "callreport_contact_addresses"
    private const val KEY_PREFIX = "address:"
    private const val MAX_ADDRESSES = 12

    fun addressesForPhone(context: Context, phone: String): List<String> {
        val rawContactId = realRawContactId(context, phone)
        return if (rawContactId > 0L) {
            readAndroidAddresses(context, rawContactId)
        } else {
            stored(context, phone).addresses
        }
    }

    fun isAndroidContact(context: Context, phone: String): Boolean = realRawContactId(context, phone) > 0L

    fun saveUnknownAddresses(context: Context, phone: String, values: Collection<String>): Boolean {
        if (isAndroidContact(context, phone)) return false
        val key = PhoneNormalizer.key(phone)
        if (key.isBlank()) return false
        val addresses = normalize(values)
        val payload = JSONObject().apply {
            put("updated_at_ms", System.currentTimeMillis())
            put("addresses", JSONArray(addresses))
            put("pending", true)
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + key, payload.toString()).apply()
        ContactAddressSyncScheduler.enqueue(context.applicationContext)
        return true
    }

    fun pending(context: Context): List<StoredAddress> {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .asSequence()
            .filter { (key, value) -> key.startsWith(KEY_PREFIX) && value is String }
            .mapNotNull { (key, value) -> parse(key.removePrefix(KEY_PREFIX), value as String) }
            .filter { it.pending }
            .toList()
    }

    fun markSynced(context: Context, phoneKey: String, updatedAtMs: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = parse(phoneKey, prefs.getString(KEY_PREFIX + phoneKey, "").orEmpty()) ?: return
        if (current.updatedAtMs != updatedAtMs) return
        val payload = JSONObject().apply {
            put("updated_at_ms", current.updatedAtMs)
            put("addresses", JSONArray(current.addresses))
            put("pending", false)
        }
        prefs.edit().putString(KEY_PREFIX + phoneKey, payload.toString()).apply()
    }

    fun mergeServerAddresses(context: Context, phone: String, values: Collection<String>, updatedAtMs: Long) {
        if (updatedAtMs <= 0L || isAndroidContact(context, phone)) return
        val key = PhoneNormalizer.key(phone)
        if (key.isBlank()) return
        val current = stored(context, phone)
        if (current.pending || current.updatedAtMs > updatedAtMs) return
        val payload = JSONObject().apply {
            put("updated_at_ms", updatedAtMs)
            put("addresses", JSONArray(normalize(values)))
            put("pending", false)
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + key, payload.toString()).apply()
    }

    private fun stored(context: Context, phone: String): StoredAddress {
        val key = PhoneNormalizer.key(phone)
        if (key.isBlank()) return StoredAddress("", emptyList(), 0L, false)
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + key, "").orEmpty()
        return parse(key, raw) ?: StoredAddress(key, emptyList(), 0L, false)
    }

    private fun parse(phoneKey: String, raw: String): StoredAddress? = runCatching {
        val json = JSONObject(raw)
        StoredAddress(
            phoneKey = phoneKey,
            addresses = normalize(buildList {
                val items = json.optJSONArray("addresses") ?: JSONArray()
                for (index in 0 until items.length()) add(items.optString(index))
            }),
            updatedAtMs = json.optLong("updated_at_ms", 0L),
            pending = json.optBoolean("pending", false),
        )
    }.getOrNull()

    private fun realRawContactId(context: Context, phone: String): Long {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 0L
        return RmRealContactLookup.findRawContactId(context, phone)
    }

    private fun readAndroidAddresses(context: Context, rawContactId: Long): List<String> = runCatching {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.StructuredPostal.STREET,
                ContactsContract.CommonDataKinds.StructuredPostal.CITY,
                ContactsContract.CommonDataKinds.StructuredPostal.REGION,
                ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
                ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val formatted = cursor.getString(0).orEmpty().trim()
                    val value = formatted.ifBlank {
                        (1..5).map { cursor.getString(it).orEmpty().trim() }
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                    }
                    add(value)
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList()).let(::normalize)

    internal fun normalize(values: Collection<String>): List<String> = values.asSequence()
        .flatMap { it.lineSequence() }
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .take(MAX_ADDRESSES)
        .toList()

    internal data class StoredAddress(
        val phoneKey: String,
        val addresses: List<String>,
        val updatedAtMs: Long,
        val pending: Boolean,
    )
}
