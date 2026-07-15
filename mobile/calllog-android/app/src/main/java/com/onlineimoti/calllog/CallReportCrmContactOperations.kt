package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

internal object CallReportCrmContactOperations {
    fun addCreate(
        operations: ArrayList<ContentProviderOperation>,
        fields: CallReportCrmContactWriter.Fields,
        existingRawContactId: Long,
        groupId: Long,
        accountName: String,
        crmMimeType: String,
    ) {
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
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
                ContactsContract.CommonDataKinds.Phone.LABEL to accountName,
            ),
        )
        addOptionalInsertRows(operations, fields, groupId, accountName, crmMimeType)
        addInsertDataRow(
            operations,
            CallReportContactIntegration.HISTORY_MIME_TYPE,
            mapOf(
                ContactsContract.Data.DATA1 to fields.phone,
                ContactsContract.Data.DATA2 to accountName,
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

    fun addUpdate(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        fields: CallReportCrmContactWriter.Fields,
        groupId: Long,
        accountName: String,
        crmMimeType: String,
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
                ContactsContract.CommonDataKinds.Phone.LABEL to accountName,
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
                ContactsContract.CommonDataKinds.Website.LABEL to accountName,
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
            crmMimeType,
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
                ContactsContract.Data.DATA2 to accountName,
                ContactsContract.Data.DATA3 to "История",
            ),
        )
    }

    private fun addOptionalInsertRows(
        operations: ArrayList<ContentProviderOperation>,
        fields: CallReportCrmContactWriter.Fields,
        groupId: Long,
        accountName: String,
        crmMimeType: String,
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
                    ContactsContract.CommonDataKinds.Website.LABEL to accountName,
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
                crmMimeType,
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
}
