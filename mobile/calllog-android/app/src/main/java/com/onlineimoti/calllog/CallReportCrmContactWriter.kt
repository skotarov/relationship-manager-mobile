package com.onlineimoti.calllog

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object CallReportCrmContactWriter {
    const val CRM_MIME_TYPE = "vnd.android.cursor.item/vnd.com.onlineimoti.calllog.crm"
    private const val ACCOUNT_NAME = "Call Report"

    data class Fields(
        val phone: String,
        val displayName: String,
        val organization: String,
        val jobTitle: String,
        val website: String,
        val note: String,
        val groupName: String,
        val customText: String,
    )

    fun save(context: Context, fields: Fields): Boolean {
        return runCatching {
            val phone = PhoneNormalizer.normalize(fields.phone)
            if (phone.isBlank()) return@runCatching false
            if (!canReadAndWriteContacts(context)) return@runCatching false

            ensureAccount(context)
            val normalized = fields.copy(
                phone = phone,
                displayName = fields.displayName.trim().ifBlank { phone },
                organization = fields.organization.trim(),
                jobTitle = fields.jobTitle.trim(),
                website = fields.website.trim(),
                note = fields.note.trim(),
                groupName = fields.groupName.trim(),
                customText = fields.customText.trim(),
            )
            val groupId = ensureGroup(context, normalized.groupName)
            val callReportRawContactId = findCallReportRawContactId(context, phone)
            val existingRawContactId = findExistingRawContactId(context, phone)
            val operations = arrayListOf<ContentProviderOperation>()

            if (callReportRawContactId > 0L) {
                addUpdateOperations(context, operations, callReportRawContactId, normalized, groupId)
                if (existingRawContactId > 0L && existingRawContactId != callReportRawContactId) {
                    operations.add(
                        ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                            .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                            .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawContactId)
                            .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, callReportRawContactId)
                            .build()
                    )
                }
            } else {
                addCreateOperations(operations, normalized, existingRawContactId, groupId)
            }

            if (operations.isNotEmpty()) {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            }
            findCallReportRawContactId(context, phone) > 0L
        }.getOrDefault(false)
    }

    private fun addCreateOperations(
        operations: ArrayList<ContentProviderOperation>,
        fields: Fields,
        existingRawContactId: Long,
        groupId: Long,
    ) {
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, fields.phone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )
        addInsertDataRow(
            operations,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            mapOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to fields.displayName),
        )
        addInsertDataRow(
            operations,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            mapOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER to fields.phone,
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to ACCOUNT_NAME,
            ),
        )
        addOptionalInsertRows(operations, fields, groupId)
        addInsertDataRow(
            operations,
            CallReportContactIntegration.HISTORY_MIME_TYPE,
            mapOf(
                ContactsContract.Data.DATA1 to fields.phone,
                ContactsContract.Data.DATA2 to ACCOUNT_NAME,
                ContactsContract.Data.DATA3 to "История",
            ),
        )
        if (existingRawContactId > 0L) {
            operations.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawContactId)
                    .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, 0)
                    .build()
            )
        }
    }

    private fun addOptionalInsertRows(
        operations: ArrayList<ContentProviderOperation>,
        fields: Fields,
        groupId: Long,
    ) {
        if (fields.organization.isNotBlank() || fields.jobTitle.isNotBlank()) {
            addInsertDataRow(
                operations,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                mapOf(
                    ContactsContract.CommonDataKinds.Organization.COMPANY to fields.organization,
                    ContactsContract.CommonDataKinds.Organization.TITLE to fields.jobTitle,
                    ContactsContract.CommonDataKinds.Organization.TYPE to ContactsContract.CommonDataKinds.Organization.TYPE_WORK,
                ),
            )
        }
        if (fields.website.isNotBlank()) {
            addInsertDataRow(
                operations,
                ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
                mapOf(
                    ContactsContract.CommonDataKinds.Website.URL to fields.website,
                    ContactsContract.CommonDataKinds.Website.TYPE to ContactsContract.CommonDataKinds.Website.TYPE_OTHER,
                    ContactsContract.CommonDataKinds.Website.LABEL to ACCOUNT_NAME,
                ),
            )
        }
        if (fields.note.isNotBlank()) {
            addInsertDataRow(
                operations,
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note),
            )
        }
        if (groupId > 0L) {
            addInsertDataRow(
                operations,
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
                mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId),
            )
        }
        if (fields.customText.isNotBlank()) {
            addInsertDataRow(
                operations,
                CRM_MIME_TYPE,
                mapOf(
                    ContactsContract.Data.DATA1 to fields.customText,
                    ContactsContract.Data.DATA2 to "Call Report CRM",
                    ContactsContract.Data.DATA3 to "CRM",
                ),
            )
        }
    }

    private fun addInsertDataRow(
        operations: ArrayList<ContentProviderOperation>,
        mimeType: String,
        values: Map<String, Any>,
    ) {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        values.forEach { (key, value) -> builder.withValue(key, value) }
        operations.add(builder.build())
    }

    private fun addUpdateOperations(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        fields: Fields,
        groupId: Long,
    ) {
        operations.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawContactId.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, fields.phone)
                .build()
        )
        upsertDataRow(
            context,
            operations,
            rawContactId,
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            mapOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to fields.displayName),
        )
        upsertDataRow(
            context,
            operations,
            rawContactId,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            mapOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER to fields.phone,
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to ACCOUNT_NAME,
            ),
        )
        upsertOrDeleteDataRow(
            context,
            operations,
            rawContactId,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            mapOf(
                ContactsContract.CommonDataKinds.Organization.COMPANY to fields.organization,
                ContactsContract.CommonDataKinds.Organization.TITLE to fields.jobTitle,
                ContactsContract.CommonDataKinds.Organization.TYPE to ContactsContract.CommonDataKinds.Organization.TYPE_WORK,
            ),
            fields.organization.isNotBlank() || fields.jobTitle.isNotBlank(),
        )
        upsertOrDeleteDataRow(
            context,
            operations,
            rawContactId,
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            mapOf(
                ContactsContract.CommonDataKinds.Website.URL to fields.website,
                ContactsContract.CommonDataKinds.Website.TYPE to ContactsContract.CommonDataKinds.Website.TYPE_OTHER,
                ContactsContract.CommonDataKinds.Website.LABEL to ACCOUNT_NAME,
            ),
            fields.website.isNotBlank(),
        )
        upsertOrDeleteDataRow(
            context,
            operations,
            rawContactId,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note),
            fields.note.isNotBlank(),
        )
        upsertOrDeleteDataRow(
            context,
            operations,
            rawContactId,
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId),
            groupId > 0L,
        )
        upsertOrDeleteDataRow(
            context,
            operations,
            rawContactId,
            CRM_MIME_TYPE,
            mapOf(
                ContactsContract.Data.DATA1 to fields.customText,
                ContactsContract.Data.DATA2 to "Call Report CRM",
                ContactsContract.Data.DATA3 to "CRM",
            ),
            fields.customText.isNotBlank(),
        )
        upsertDataRow(
            context,
            operations,
            rawContactId,
            CallReportContactIntegration.HISTORY_MIME_TYPE,
            mapOf(
                ContactsContract.Data.DATA1 to fields.phone,
                ContactsContract.Data.DATA2 to ACCOUNT_NAME,
                ContactsContract.Data.DATA3 to "История",
            ),
        )
    }

    private fun upsertOrDeleteDataRow(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        mimeType: String,
        insertValues: Map<String, Any>,
        keep: Boolean,
    ) {
        if (keep) {
            upsertDataRow(context, operations, rawContactId, mimeType, insertValues)
            return
        }
        val dataId = findDataRowId(context, rawContactId, mimeType)
        if (dataId > 0L) {
            operations.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID}=?", arrayOf(dataId.toString()))
                    .build()
            )
        }
    }

    private fun upsertDataRow(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        mimeType: String,
        insertValues: Map<String, Any>,
    ) {
        val dataId = findDataRowId(context, rawContactId, mimeType)
        val builder = if (dataId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(dataId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        }
        insertValues.forEach { (key, value) -> builder.withValue(key, value) }
        operations.add(builder.build())
    }

    private fun findDataRowId(context: Context, rawContactId: Long, mimeType: String): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawContactId.toString(), mimeType),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun ensureAccount(context: Context) {
        val account = Account(ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE)
        val manager = AccountManager.get(context)
        runCatching {
            if (manager.getAccountsByType(CallReportContactIntegration.ACCOUNT_TYPE).none { it.name == ACCOUNT_NAME }) {
                manager.addAccountExplicitly(account, null, null)
            }
        }
        runCatching {
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        }
    }

    private fun ensureGroup(context: Context, title: String): Long {
        val groupTitle = title.trim()
        if (groupTitle.isBlank()) return 0L
        val existingId = runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID),
                "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.ACCOUNT_NAME}=? AND ${ContactsContract.Groups.TITLE}=? AND ${ContactsContract.Groups.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, groupTitle),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (existingId > 0L) return existingId

        return runCatching {
            val uri = context.contentResolver.insert(
                ContactsContract.Groups.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Groups.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                    put(ContactsContract.Groups.ACCOUNT_NAME, ACCOUNT_NAME)
                    put(ContactsContract.Groups.TITLE, groupTitle)
                    put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                    put(ContactsContract.Groups.SHOULD_SYNC, 1)
                },
            )
            if (uri == null) 0L else ContentUris.parseId(uri)
        }.getOrDefault(0L)
    }

    private fun findCallReportRawContactId(context: Context, phone: String): Long {
        val bySync = runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.SYNC1}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, phone),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (bySync > 0L) return bySync

        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0 AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.NUMBER}=?",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phone),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun findExistingRawContactId(context: Context, phone: String): Long {
        val contactId = runCatching {
            context.contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(),
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (contactId <= 0L) return 0L

        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
