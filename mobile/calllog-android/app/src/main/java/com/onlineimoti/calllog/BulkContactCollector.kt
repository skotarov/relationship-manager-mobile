package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object BulkContactCollector {
    private const val MAX_CONTACTS_PER_RUN = 2500

    fun collectUniqueContacts(context: Context): List<BulkContactCandidate> {
        val callReportRawContactIds = findCallReportRawContactIds(context)
        val contactsByPhone = linkedMapOf<String, BulkContactCandidate>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.Data.RAW_CONTACT_ID,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val rawContactIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            if (numberIndex < 0) return@use
            var scanned = 0
            while (cursor.moveToNext() && contactsByPhone.size < MAX_CONTACTS_PER_RUN) {
                val rawContactId = if (rawContactIndex >= 0) cursor.getLong(rawContactIndex) else 0L
                if (rawContactId > 0L && callReportRawContactIds.contains(rawContactId)) continue
                val originalPhone = cursor.getString(numberIndex).orEmpty()
                val phone = PhoneNormalizer.normalize(originalPhone)
                if (phone.isBlank() || contactsByPhone.containsKey(phone)) continue
                val fallbackDisplayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                val exactDisplayName = exactStructuredDisplayName(context, rawContactId, fallbackDisplayName)
                contactsByPhone[phone] = BulkContactCandidate(
                    phone = phone,
                    displayPhone = originalPhone,
                    displayName = cleanDisplayName(exactDisplayName, fallbackDisplayName, phone),
                    existingRawContactId = rawContactId,
                )
                scanned += 1
                if (scanned % 250 == 0) Thread.yield()
            }
        }
        return contactsByPhone.values.toList()
    }

    fun findCallReportPhoneRawContactIds(context: Context): Map<String, Long> {
        val accountNames = CrmContactAccountStore.accountNames()
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?, ?) AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(
                    CallReportContactIntegration.ACCOUNT_TYPE,
                    accountNames[0],
                    accountNames[1],
                    accountNames[2],
                ),
                null,
            )?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val phone = PhoneNormalizer.normalize(cursor.getString(0).orEmpty())
                        if (phone.isNotBlank()) put(phone, cursor.getLong(1))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun cleanDisplayName(exactDisplayName: String, fallbackDisplayName: String, phone: String): String {
        return RmContactNameResolver.cleanFallbackDisplayName(exactDisplayName, phone)
            .ifBlank { RmContactNameResolver.cleanFallbackDisplayName(fallbackDisplayName, phone) }
    }

    private fun exactStructuredDisplayName(context: Context, rawContactId: Long, fallback: String): String {
        if (rawContactId <= 0L) return fallback
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use fallback
                val firstSecondThird = listOf(
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME),
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME),
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
                ).filter { it.isNotBlank() }.joinToString(" ")
                firstSecondThird.ifBlank {
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME).ifBlank { fallback }
                }
            }.orEmpty().ifBlank { fallback }
        }.getOrDefault(fallback)
    }

    private fun findCallReportRawContactIds(context: Context): Set<Long> {
        val accountNames = CrmContactAccountStore.accountNames()
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?, ?) AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(
                    CallReportContactIntegration.ACCOUNT_TYPE,
                    accountNames[0],
                    accountNames[1],
                    accountNames[2],
                ),
                null,
            )?.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0) getString(index).orEmpty() else ""
    }
}
