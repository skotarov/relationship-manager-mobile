package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object RmContactRepairReader {
    private const val MAX_CONTACTS_PER_RUN = 2500

    fun collectRealContacts(context: Context): List<RealContactCandidate> {
        val rmRawIds = findRmRawContactIds(context)
        val contactsByPhone = linkedMapOf<String, RealContactCandidate>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.Data.RAW_CONTACT_ID,
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && contactsByPhone.size < MAX_CONTACTS_PER_RUN) {
                val rawId = cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                if (rawId > 0L && rmRawIds.contains(rawId)) continue
                val visiblePhone = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val normalizedPhone = PhoneNormalizer.normalize(visiblePhone)
                if (normalizedPhone.isBlank() || contactsByPhone.containsKey(normalizedPhone)) continue
                val fallbackName = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                contactsByPhone[normalizedPhone] = RealContactCandidate(
                    rawContactId = rawId,
                    normalizedPhone = normalizedPhone,
                    visiblePhone = visiblePhone,
                    name = structuredName(context, rawId, fallbackName),
                )
            }
        }
        return contactsByPhone.values.toList()
    }

    fun findRmRawContactId(context: Context, normalizedPhone: String): Long {
        val bySync = CrmContactAccountStore.findCallReportRawContactId(context, normalizedPhone)
        if (bySync > 0L) return bySync
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val phone = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (PhoneNormalizer.normalize(phone) == normalizedPhone) return@use cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                }
                0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    fun findDataRowId(context: Context, rawId: Long, mimeType: String): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), mimeType),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    fun findPrimaryRmPhoneRowId(context: Context, rawId: Long): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Phone.LABEL),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                var first = 0L
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (first <= 0L) first = id
                    if (cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.LABEL) == CrmContactAccountStore.ACCOUNT_NAME) return@use id
                }
                first
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun structuredName(context: Context, rawId: Long, fallback: String): StructuredContactName {
        if (rawId <= 0L) return StructuredContactName.fromDisplayName(fallback)
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use StructuredContactName.fromDisplayName(fallback)
                val given = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                val middle = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME)
                val family = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)
                val display = listOf(given, middle, family)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME) }
                    .ifBlank { fallback }
                StructuredContactName(givenName = given, middleName = middle, familyName = family, displayName = display)
            } ?: StructuredContactName.fromDisplayName(fallback)
        }.getOrDefault(StructuredContactName.fromDisplayName(fallback))
    }

    private fun findRmRawContactIds(context: Context): Set<Long> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?) AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(
                    CallReportContactIntegration.ACCOUNT_TYPE,
                    CrmContactAccountStore.ACCOUNT_NAME,
                    CrmContactAccountStore.LEGACY_ACCOUNT_NAME,
                ),
                null,
            )?.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0) getString(index).orEmpty() else ""
    }

    private fun android.database.Cursor.getLongOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0) getLong(index) else 0L
    }
}
