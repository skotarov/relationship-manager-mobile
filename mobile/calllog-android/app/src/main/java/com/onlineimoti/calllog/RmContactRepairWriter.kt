package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

internal object RmContactRepairWriter {
    fun repairOne(context: Context, rmRawId: Long, real: RealContactCandidate): Boolean {
        return runCatching {
            val ops = arrayListOf<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                    .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rmRawId.toString()))
                    .withValue(ContactsContract.RawContacts.SYNC1, real.normalizedPhone)
                    .build()
            )
            upsertStructuredName(context, ops, rmRawId, real.name)
            upsertVisiblePhone(context, ops, rmRawId, real.visiblePhone.ifBlank { real.normalizedPhone })
            upsertHistoryRow(context, ops, rmRawId, real.normalizedPhone)
            keepTogetherIfPossible(ops, real.rawContactId, rmRawId)
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }.getOrDefault(false)
    }

    private fun upsertStructuredName(
        context: Context,
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        name: StructuredContactName,
    ) {
        val rowId = RmContactRepairReader.findDataRowId(context, rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        val builder = dataBuilder(rawId, rowId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        ops.add(
            builder
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.displayName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, name.givenName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, name.middleName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, name.familyName)
                .build()
        )
    }

    private fun upsertVisiblePhone(
        context: Context,
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        visiblePhone: String,
    ) {
        val rowId = RmContactRepairReader.findPrimaryRmPhoneRowId(context, rawId)
        val builder = dataBuilder(rawId, rowId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        ops.add(
            builder
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, visiblePhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, CrmContactAccountStore.ACCOUNT_NAME)
                .build()
        )
    }

    private fun upsertHistoryRow(
        context: Context,
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        normalizedPhone: String,
    ) {
        val rowId = RmContactRepairReader.findDataRowId(context, rawId, CallReportContactIntegration.HISTORY_MIME_TYPE)
        val builder = dataBuilder(rawId, rowId, CallReportContactIntegration.HISTORY_MIME_TYPE)
        ops.add(
            builder
                .withValue(ContactsContract.Data.DATA1, normalizedPhone)
                .withValue(ContactsContract.Data.DATA2, CrmContactAccountStore.ACCOUNT_NAME)
                .withValue(ContactsContract.Data.DATA3, "История")
                .build()
        )
    }

    private fun dataBuilder(rawId: Long, rowId: Long, mimeType: String): ContentProviderOperation.Builder {
        return if (rowId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(rowId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        }
    }

    private fun keepTogetherIfPossible(
        ops: ArrayList<ContentProviderOperation>,
        realRawContactId: Long,
        rmRawContactId: Long,
    ) {
        if (realRawContactId <= 0L || realRawContactId == rmRawContactId) return
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, realRawContactId)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rmRawContactId)
                .build()
        )
    }
}
