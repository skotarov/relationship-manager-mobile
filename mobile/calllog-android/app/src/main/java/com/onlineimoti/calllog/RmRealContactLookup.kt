package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object RmRealContactLookup {
    fun findRawContactId(context: Context, phone: String): Long {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return 0L
        phoneLookupCandidates(phone, normalizedPhone).forEach { candidate ->
            val rawId = findByPhoneLookup(context, candidate)
            if (rawId > 0L) return rawId
        }
        return findByScanningPhoneRows(context, normalizedPhone)
    }

    fun findContactId(context: Context, phone: String): Long {
        val rawId = findRawContactId(context, phone)
        return if (rawId > 0L) contactIdForRaw(context, rawId) else 0L
    }

    /**
     * Returns the name stored on a real device/account contact, never from the internal
     * Call Report account layer. This is used after returning from Android Contacts so the
     * History header immediately shows the newly entered name.
     */
    fun resolveDisplayName(context: Context, phone: String): String? {
        val rawContactId = findRawContactId(context, phone)
        if (rawContactId <= 0L) return null

        val rawName = runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(
                    rawContactId.toString(),
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
                        .trim()
                        .takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()
        if (!rawName.isNullOrBlank()) return rawName

        val contactId = contactIdForRaw(context, rawContactId)
        if (contactId <= 0L) return null
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getStringOrEmpty(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                        .trim()
                        .takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun findByPhoneLookup(context: Context, phone: String): Long {
        if (phone.isBlank()) return 0L
        val contactId = runCatching {
            context.contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(),
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        return realRawForContactId(context, contactId)
    }

    private fun findByScanningPhoneRows(context: Context, normalizedPhone: String): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                var found = 0L
                while (cursor.moveToNext() && found <= 0L) {
                    val rowPhone = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val rawId = cursor.getLongOrZero(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
                    if (samePhone(rowPhone, normalizedPhone) && isRealRawContact(context, rawId)) {
                        found = rawId
                    }
                }
                found
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun realRawForContactId(context: Context, contactId: Long): Long {
        if (contactId <= 0L) return 0L
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor ->
                var found = 0L
                while (cursor.moveToNext() && found <= 0L) {
                    val rawId = cursor.getLongOrZero(ContactsContract.RawContacts._ID)
                    val accountType = cursor.getStringOrEmpty(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    if (rawId > 0L && accountType != CallReportContactIntegration.ACCOUNT_TYPE) found = rawId
                }
                found
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun contactIdForRaw(context: Context, rawContactId: Long): Long {
        if (rawContactId <= 0L) return 0L
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLongOrZero(ContactsContract.RawContacts.CONTACT_ID) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun isRealRawContact(context: Context, rawContactId: Long): Boolean {
        if (rawContactId <= 0L) return false
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts._ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(rawContactId.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) false
                else cursor.getStringOrEmpty(ContactsContract.RawContacts.ACCOUNT_TYPE) != CallReportContactIntegration.ACCOUNT_TYPE
            } ?: false
        }.getOrDefault(false)
    }

    private fun phoneLookupCandidates(original: String, normalized: String): List<String> {
        val digits = normalized.filter { it.isDigit() }
        val local = when {
            digits.startsWith("359") && digits.length > 3 -> "0${digits.drop(3)}"
            else -> ""
        }
        val lastNine = digits.takeLast(9)
        return listOf(original, normalized, digits, local, lastNine)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun samePhone(left: String, right: String): Boolean {
        val a = PhoneNormalizer.normalize(left)
        val b = PhoneNormalizer.normalize(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val ad = a.filter { it.isDigit() }
        val bd = b.filter { it.isDigit() }
        return ad.length >= 9 && bd.length >= 9 && ad.takeLast(9) == bd.takeLast(9)
    }
}
