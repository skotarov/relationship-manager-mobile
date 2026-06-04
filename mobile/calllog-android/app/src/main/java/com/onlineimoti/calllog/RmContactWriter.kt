package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

internal object RmContactWriter {
    fun create(context: Context, real: BulkContactCandidate): Boolean {
        val ops = arrayListOf<ContentProviderOperation>()
        val rawBackRef = 0
        val title = RmContactNameResolver.titleFor(real)
        val visiblePhone = real.displayPhone.ifBlank { real.phone }
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withYieldAllowed(true)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, CrmContactAccountStore.ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, real.phone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withStructuredNameValues(title)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, visiblePhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, CrmContactAccountStore.ACCOUNT_NAME)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, CallReportContactIntegration.HISTORY_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, visiblePhone)
                .withValue(ContactsContract.Data.DATA2, title)
                .withValue(ContactsContract.Data.DATA3, CrmContactAccountStore.ACCOUNT_NAME)
                .build()
        )
        if (real.existingRawContactId > 0L) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, real.existingRawContactId)
                    .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawBackRef)
                    .build()
            )
        }
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }

    fun update(context: Context, real: BulkContactCandidate, rm: RmRecord): Boolean {
        val ops = arrayListOf<ContentProviderOperation>()
        val title = RmContactNameResolver.titleFor(real)
        val visiblePhone = real.displayPhone.ifBlank { real.phone }
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withYieldAllowed(true)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rm.rawContactId.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, real.phone)
                .build()
        )
        upsertDataRow(
            ops = ops,
            rawId = rm.rawContactId,
            rowId = rm.nameRowId,
            mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            values = structuredNameValues(title),
        )
        upsertDataRow(
            ops = ops,
            rawId = rm.rawContactId,
            rowId = rm.phoneRowId,
            mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            values = mapOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER to visiblePhone,
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to CrmContactAccountStore.ACCOUNT_NAME,
            ),
        )
        upsertDataRow(
            ops = ops,
            rawId = rm.rawContactId,
            rowId = rm.historyRowId,
            mimeType = CallReportContactIntegration.HISTORY_MIME_TYPE,
            values = mapOf(
                ContactsContract.Data.DATA1 to visiblePhone,
                ContactsContract.Data.DATA2 to title,
                ContactsContract.Data.DATA3 to CrmContactAccountStore.ACCOUNT_NAME,
            ),
        )
        if (real.existingRawContactId > 0L && real.existingRawContactId != rm.rawContactId) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, real.existingRawContactId)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rm.rawContactId)
                    .build()
            )
        }
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }

    fun delete(context: Context, rawContactId: Long): Int {
        return runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?) AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(
                    rawContactId.toString(),
                    CallReportContactIntegration.ACCOUNT_TYPE,
                    CrmContactAccountStore.ACCOUNT_NAME,
                    CrmContactAccountStore.LEGACY_ACCOUNT_NAME,
                ),
            )
        }.getOrDefault(0)
    }

    private fun upsertDataRow(
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        rowId: Long,
        mimeType: String,
        values: Map<String, Any>,
    ) {
        val builder = if (rowId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(rowId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        }
        values.forEach { (key, value) -> builder.withValue(key, value) }
        ops.add(builder.build())
    }

    private fun ContentProviderOperation.Builder.withStructuredNameValues(title: String): ContentProviderOperation.Builder {
        structuredNameValues(title).forEach { (key, value) -> withValue(key, value) }
        return this
    }

    private fun structuredNameValues(title: String): Map<String, Any> {
        val parts = RmContactNameResolver.structuredParts(title)
        return buildMap {
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, title)
            if (parts.givenName.isNotBlank()) put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, parts.givenName)
            if (parts.middleName.isNotBlank()) put(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, parts.middleName)
            if (parts.familyName.isNotBlank()) put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, parts.familyName)
        }
    }
}
