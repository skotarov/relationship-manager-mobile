package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object RmContactReader {
    fun findRealContact(context: Context, phone: String, displayName: String): BulkContactCandidate? {
        val existingRawId = RmRealContactLookup.findRawContactId(context, phone)
        if (existingRawId <= 0L) return null
        return BulkContactCandidate(
            phone = phone,
            displayPhone = phone,
            displayName = RmContactNameResolver.cleanFallbackDisplayName(displayName, phone).ifBlank { phone },
            existingRawContactId = existingRawId,
        )
    }

    fun findRmRecord(context: Context, phone: String): RmRecord? {
        return collectRmRecords(context, onlyPhone = phone)[phone]
    }

    fun collectRmRecords(context: Context, onlyPhone: String = ""): Map<String, RmRecord> {
        val rawRecords = linkedMapOf<Long, MutableRmRecord>()
        val accountNames = CrmContactAccountStore.accountNames()
        val rawSelection = buildString {
            append("${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?, ?) AND ${ContactsContract.RawContacts.DELETED}=0")
            if (onlyPhone.isNotBlank()) append(" AND ${ContactsContract.RawContacts.SYNC1}=?")
        }
        val rawArgs = buildList {
            add(CallReportContactIntegration.ACCOUNT_TYPE)
            addAll(accountNames.toList())
            if (onlyPhone.isNotBlank()) add(onlyPhone)
        }.toTypedArray()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1),
                rawSelection,
                rawArgs,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rawId = cursor.getLongOrZero(ContactsContract.RawContacts._ID)
                    if (rawId <= 0L) continue
                    val syncPhone = PhoneNormalizer.normalize(cursor.getStringOrEmpty(ContactsContract.RawContacts.SYNC1))
                    rawRecords[rawId] = MutableRmRecord(rawContactId = rawId, syncPhone = syncPhone)
                }
            }
        }
        if (rawRecords.isEmpty()) return emptyMap()

        fillRmDataRows(context, rawRecords)
        return buildMap {
            rawRecords.values.forEach { mutable ->
                if (mutable.syncPhone.isNotBlank()) mutable.normalizedPhones.add(mutable.syncPhone)
                val primaryPhone = mutable.syncPhone.ifBlank { mutable.normalizedPhones.firstOrNull().orEmpty() }
                if (primaryPhone.isNotBlank()) {
                    put(
                        primaryPhone,
                        RmRecord(
                            rawContactId = mutable.rawContactId,
                            syncPhone = mutable.syncPhone,
                            normalizedPhones = mutable.normalizedPhones.toSet(),
                            displayName = mutable.displayName.ifBlank { primaryPhone },
                            givenName = mutable.givenName,
                            middleName = mutable.middleName,
                            familyName = mutable.familyName,
                            nameRowId = mutable.nameRowId,
                            phoneRowId = mutable.phoneRowId,
                            historyRowId = mutable.historyRowId,
                        ),
                    )
                }
            }
        }
    }

    private fun fillRmDataRows(context: Context, rawRecords: LinkedHashMap<Long, MutableRmRecord>) {
        val rawIds = rawRecords.keys.toList()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.RAW_CONTACT_ID,
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3,
                    ContactsContract.Data.DATA5,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID} IN (${rawIds.joinToString(",") { "?" }}) AND ${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)",
                (rawIds.map { it.toString() } + listOf(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    CallReportContactIntegration.HISTORY_MIME_TYPE,
                )).toTypedArray(),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val dataId = cursor.getLongOrZero(ContactsContract.Data._ID)
                    val rawId = cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                    val record = rawRecords[rawId] ?: continue
                    when (cursor.getStringOrEmpty(ContactsContract.Data.MIMETYPE)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            if (record.phoneRowId <= 0L) record.phoneRowId = dataId
                            PhoneNormalizer.normalize(cursor.getStringOrEmpty(ContactsContract.Data.DATA1))
                                .takeIf { it.isNotBlank() }
                                ?.let { record.normalizedPhones.add(it) }
                        }
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            record.nameRowId = dataId
                            record.displayName = cursor.getStringOrEmpty(ContactsContract.Data.DATA1)
                            record.givenName = cursor.getStringOrEmpty(ContactsContract.Data.DATA2)
                            record.familyName = cursor.getStringOrEmpty(ContactsContract.Data.DATA3)
                            record.middleName = cursor.getStringOrEmpty(ContactsContract.Data.DATA5)
                        }
                        CallReportContactIntegration.HISTORY_MIME_TYPE -> {
                            record.historyRowId = dataId
                            PhoneNormalizer.normalize(cursor.getStringOrEmpty(ContactsContract.Data.DATA1))
                                .takeIf { it.isNotBlank() }
                                ?.let { record.normalizedPhones.add(it) }
                        }
                    }
                }
            }
        }
    }
}
