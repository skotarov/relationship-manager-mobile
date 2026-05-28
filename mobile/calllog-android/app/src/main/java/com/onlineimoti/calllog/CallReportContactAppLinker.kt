package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object CallReportContactAppLinker {
    private const val ACCOUNT_TYPE = CallReportContactIntegration.ACCOUNT_TYPE
    private const val ACCOUNT_NAME = "Call Report"
    private const val CRM_MIME_TYPE = "vnd.android.cursor.item/vnd.com.onlineimoti.calllog.crm"

    fun isLinked(context: Context, phone: String): Boolean {
        val originalPhone = PhoneNormalizer.normalize(phone)
        if (originalPhone.isBlank() || !canReadContacts(context)) return false
        val existingRawId = findExistingRawContactId(context, originalPhone)
        if (existingRawId > 0L && findDataRowId(context, existingRawId, CallReportContactIntegration.HISTORY_MIME_TYPE) > 0L) {
            return true
        }
        return CallReportContactIntegration.isContactLinked(context, originalPhone)
    }

    fun save(context: Context, fields: CallReportStableCrmContactWriter.Fields): Boolean {
        val originalPhone = PhoneNormalizer.normalize(fields.originalPhone)
        if (originalPhone.isBlank() || !canReadAndWriteContacts(context)) return false
        val existingRawId = findExistingRawContactId(context, originalPhone)
        if (existingRawId <= 0L) return CallReportStableCrmContactWriter.save(context, fields)

        CallReportContactIntegration.removeContact(context, originalPhone)
        val operations = arrayListOf<ContentProviderOperation>()
        upsertDataRow(
            context = context,
            operations = operations,
            rawContactId = existingRawId,
            mimeType = CallReportContactIntegration.HISTORY_MIME_TYPE,
            values = mapOf(
                ContactsContract.Data.DATA1 to originalPhone,
                ContactsContract.Data.DATA2 to ACCOUNT_NAME,
                ContactsContract.Data.DATA3 to "История",
            ),
        )
        upsertOrDeleteDataRow(
            context = context,
            operations = operations,
            rawContactId = existingRawId,
            mimeType = CRM_MIME_TYPE,
            values = mapOf(
                ContactsContract.Data.DATA1 to fields.customText.trim(),
                ContactsContract.Data.DATA2 to "Call Report CRM",
                ContactsContract.Data.DATA3 to "CRM",
            ),
            keep = fields.customText.isNotBlank(),
        )
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        return findDataRowId(context, existingRawId, CallReportContactIntegration.HISTORY_MIME_TYPE) > 0L
    }

    fun remove(context: Context, phone: String): Int {
        val originalPhone = PhoneNormalizer.normalize(phone)
        if (originalPhone.isBlank() || !canReadAndWriteContacts(context)) return 0
        val operations = arrayListOf<ContentProviderOperation>()
        val existingRawId = findExistingRawContactId(context, originalPhone)
        if (existingRawId > 0L) {
            addDeleteDataRowOperation(context, operations, existingRawId, CallReportContactIntegration.HISTORY_MIME_TYPE)
            addDeleteDataRowOperation(context, operations, existingRawId, CRM_MIME_TYPE)
        }
        val removedDataRows = if (operations.isNotEmpty()) {
            runCatching {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                operations.size
            }.getOrDefault(0)
        } else 0
        val removedRawContacts = CallReportContactIntegration.removeContact(context, originalPhone)
        return removedDataRows + removedRawContacts
    }

    private fun upsertOrDeleteDataRow(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        mimeType: String,
        values: Map<String, Any>,
        keep: Boolean,
    ) {
        if (keep) {
            upsertDataRow(context, operations, rawContactId, mimeType, values)
        } else {
            addDeleteDataRowOperation(context, operations, rawContactId, mimeType)
        }
    }

    private fun upsertDataRow(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        mimeType: String,
        values: Map<String, Any>,
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
        values.forEach { (key, value) -> builder.withValue(key, value) }
        operations.add(builder.build())
    }

    private fun addDeleteDataRowOperation(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        mimeType: String,
    ) {
        val dataId = findDataRowId(context, rawContactId, mimeType)
        if (dataId > 0L) {
            operations.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID}=?", arrayOf(dataId.toString()))
                    .build()
            )
        }
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
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ACCOUNT_TYPE),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun canReadContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
