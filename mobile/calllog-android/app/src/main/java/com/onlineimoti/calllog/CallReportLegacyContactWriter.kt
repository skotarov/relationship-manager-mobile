package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

internal object CallReportLegacyContactWriter {
    private const val ACCOUNT_NAME = "Call Report"

    fun link(context: Context, phone: String, displayName: String): Boolean {
        val cleanedPhone = PhoneNormalizer.normalize(phone)
        if (cleanedPhone.isBlank() || !CallReportLegacyContactLookup.hasContactPermissions(context)) return false

        CrmContactAccountStore.ensureAccount(context)
        val title = displayName.ifBlank { cleanedPhone }
        val legacyRawId = CallReportLegacyContactLookup.findRawContactId(context, cleanedPhone)
        val existingRawId = CallReportLegacyContactLookup.findExistingRawContactId(context, cleanedPhone)
        val operations = arrayListOf<ContentProviderOperation>()

        if (legacyRawId > 0L) {
            addUpdateOperations(operations, legacyRawId, cleanedPhone, title, context)
            if (existingRawId > 0L && existingRawId != legacyRawId) {
                operations.add(
                    ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                        .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawId)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, legacyRawId)
                        .build(),
                )
            }
        } else {
            addCreateOperations(operations, cleanedPhone, title, existingRawId)
        }

        if (operations.isNotEmpty()) {
            runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations) }.getOrElse { return false }
        }
        return CallReportLegacyContactLookup.findRawContactId(context, cleanedPhone) > 0L
    }

    fun linkAsAppIfMissing(context: Context, phone: String): Boolean {
        val cleanedPhone = PhoneNormalizer.normalize(phone)
        if (cleanedPhone.isBlank() || !CallReportLegacyContactLookup.hasContactPermissions(context)) return false

        CrmContactAccountStore.ensureAccount(context)
        if (CallReportLegacyContactLookup.findRawContactId(context, cleanedPhone) > 0L) return true

        val operations = arrayListOf<ContentProviderOperation>()
        addCreateOperations(
            operations = operations,
            phone = cleanedPhone,
            title = cleanedPhone,
            existingRawContactId = CallReportLegacyContactLookup.findExistingRawContactId(context, cleanedPhone),
        )
        return runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            CallReportLegacyContactLookup.findRawContactId(context, cleanedPhone) > 0L
        }.getOrDefault(false)
    }

    private fun addCreateOperations(
        operations: ArrayList<ContentProviderOperation>,
        phone: String,
        title: String,
        existingRawContactId: Long,
    ) {
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, phone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build(),
        )
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, title)
                .build(),
        )
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, "Call Report")
                .build(),
        )
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, CallReportContactIntegration.HISTORY_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, phone)
                .withValue(ContactsContract.Data.DATA2, "Call Report")
                .withValue(ContactsContract.Data.DATA3, "История")
                .build(),
        )
        if (existingRawContactId > 0L) {
            operations.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawContactId)
                    .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, 0)
                    .build(),
            )
        }
    }

    private fun addUpdateOperations(
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        phone: String,
        title: String,
        context: Context,
    ) {
        operations.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawContactId.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, phone)
                .build(),
        )
        upsertDataRow(
            context = context,
            operations = operations,
            rawContactId = rawContactId,
            mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            insertValues = mapOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to title),
        )
        upsertDataRow(
            context = context,
            operations = operations,
            rawContactId = rawContactId,
            mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            insertValues = mapOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER to phone,
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to "Call Report",
            ),
        )
        upsertDataRow(
            context = context,
            operations = operations,
            rawContactId = rawContactId,
            mimeType = CallReportContactIntegration.HISTORY_MIME_TYPE,
            insertValues = mapOf(
                ContactsContract.Data.DATA1 to phone,
                ContactsContract.Data.DATA2 to "Call Report",
                ContactsContract.Data.DATA3 to "История",
            ),
        )
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
