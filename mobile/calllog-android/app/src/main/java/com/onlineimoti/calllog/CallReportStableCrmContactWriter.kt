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

object CallReportStableCrmContactWriter {
    private const val ACCOUNT_NAME = "Call Report"
    private const val EXTRA_PHONE_LABEL = "Call Report доп."

    data class Fields(
        val originalPhone: String,
        val displayName: String,
        val additionalPhone: String,
        val organization: String,
        val jobTitle: String,
        val website: String,
        val note: String,
        val groupName: String,
        val customText: String,
    )

    private data class Normalized(
        val originalPhone: String,
        val displayName: String,
        val additionalPhone: String,
        val organization: String,
        val jobTitle: String,
        val website: String,
        val note: String,
        val groupName: String,
        val customText: String,
    )

    fun save(context: Context, fields: Fields): Boolean {
        return runCatching {
            val originalPhone = PhoneNormalizer.normalize(fields.originalPhone)
            if (originalPhone.isBlank()) return@runCatching false
            if (!canReadAndWriteContacts(context)) return@runCatching false

            ensureAccount(context)
            val existingRawContactId = findExistingRawContactId(context, originalPhone)
            val normalized = Normalized(
                originalPhone = originalPhone,
                displayName = if (existingRawContactId > 0L) "" else fields.displayName.trim().ifBlank { originalPhone },
                additionalPhone = PhoneNormalizer.normalize(fields.additionalPhone).takeIf { it.isNotBlank() && it != originalPhone }.orEmpty(),
                organization = fields.organization.trim(),
                jobTitle = fields.jobTitle.trim(),
                website = fields.website.trim(),
                note = fields.note.trim(),
                groupName = fields.groupName.trim(),
                customText = fields.customText.trim(),
            )
            val groupId = ensureGroup(context, normalized.groupName)
            val callReportRawContactId = findCallReportRawContactId(context, originalPhone)
            val ops = arrayListOf<ContentProviderOperation>()

            if (callReportRawContactId > 0L) {
                updateRawContact(context, ops, callReportRawContactId, normalized, groupId, existingRawContactId)
                keepTogether(ops, existingRawContactId, callReportRawContactId)
            } else {
                createRawContact(ops, normalized, existingRawContactId, groupId)
            }

            if (ops.isNotEmpty()) context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            findCallReportRawContactId(context, originalPhone) > 0L
        }.getOrDefault(false)
    }

    private fun createRawContact(ops: ArrayList<ContentProviderOperation>, fields: Normalized, existingRawContactId: Long, groupId: Long) {
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, fields.originalPhone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )
        if (fields.displayName.isNotBlank()) {
            insertRow(ops, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to fields.displayName))
        }
        insertPhone(ops, fields.originalPhone, ACCOUNT_NAME)
        if (fields.additionalPhone.isNotBlank()) insertPhone(ops, fields.additionalPhone, EXTRA_PHONE_LABEL)
        insertOptionalRows(ops, fields, groupId)
        insertHistoryRow(ops, fields.originalPhone)
        if (existingRawContactId > 0L) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawContactId)
                    .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, 0)
                    .build()
            )
        }
    }

    private fun updateRawContact(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, fields: Normalized, groupId: Long, existingRawContactId: Long) {
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, fields.originalPhone)
                .build()
        )
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to fields.displayName), existingRawContactId <= 0L && fields.displayName.isNotBlank())
        upsertPhone(context, ops, rawId, fields.originalPhone, ACCOUNT_NAME, fallbackToFirstPhone = true)
        if (fields.additionalPhone.isNotBlank()) upsertPhone(context, ops, rawId, fields.additionalPhone, EXTRA_PHONE_LABEL, fallbackToFirstPhone = false) else deletePhone(context, ops, rawId, EXTRA_PHONE_LABEL)
        upsertOptionalRows(context, ops, rawId, fields, groupId)
        upsert(context, ops, rawId, CallReportContactIntegration.HISTORY_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.originalPhone, ContactsContract.Data.DATA2 to ACCOUNT_NAME, ContactsContract.Data.DATA3 to "История"))
    }

    private fun insertOptionalRows(ops: ArrayList<ContentProviderOperation>, fields: Normalized, groupId: Long) {
        if (fields.organization.isNotBlank() || fields.jobTitle.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Organization.COMPANY to fields.organization, ContactsContract.CommonDataKinds.Organization.TITLE to fields.jobTitle, ContactsContract.CommonDataKinds.Organization.TYPE to ContactsContract.CommonDataKinds.Organization.TYPE_WORK))
        if (fields.website.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Website.URL to fields.website, ContactsContract.CommonDataKinds.Website.TYPE to ContactsContract.CommonDataKinds.Website.TYPE_OTHER, ContactsContract.CommonDataKinds.Website.LABEL to ACCOUNT_NAME))
        if (fields.note.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note))
        if (groupId > 0L) insertRow(ops, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId))
        if (fields.customText.isNotBlank()) insertRow(ops, CallReportCrmContactWriter.CRM_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.customText, ContactsContract.Data.DATA2 to "Call Report CRM", ContactsContract.Data.DATA3 to "CRM"))
    }

    private fun upsertOptionalRows(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, fields: Normalized, groupId: Long) {
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Organization.COMPANY to fields.organization, ContactsContract.CommonDataKinds.Organization.TITLE to fields.jobTitle, ContactsContract.CommonDataKinds.Organization.TYPE to ContactsContract.CommonDataKinds.Organization.TYPE_WORK), fields.organization.isNotBlank() || fields.jobTitle.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Website.URL to fields.website, ContactsContract.CommonDataKinds.Website.TYPE to ContactsContract.CommonDataKinds.Website.TYPE_OTHER, ContactsContract.CommonDataKinds.Website.LABEL to ACCOUNT_NAME), fields.website.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note), fields.note.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId), groupId > 0L)
        upsertOrDelete(context, ops, rawId, CallReportCrmContactWriter.CRM_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.customText, ContactsContract.Data.DATA2 to "Call Report CRM", ContactsContract.Data.DATA3 to "CRM"), fields.customText.isNotBlank())
    }

    private fun insertPhone(ops: ArrayList<ContentProviderOperation>, number: String, label: String) {
        insertRow(ops, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Phone.NUMBER to number, ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM, ContactsContract.CommonDataKinds.Phone.LABEL to label))
    }

    private fun insertHistoryRow(ops: ArrayList<ContentProviderOperation>, originalPhone: String) {
        insertRow(ops, CallReportContactIntegration.HISTORY_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to originalPhone, ContactsContract.Data.DATA2 to ACCOUNT_NAME, ContactsContract.Data.DATA3 to "История"))
    }

    private fun insertRow(ops: ArrayList<ContentProviderOperation>, mime: String, values: Map<String, Any>) {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mime)
        values.forEach { (key, value) -> builder.withValue(key, value) }
        ops.add(builder.build())
    }

    private fun upsertPhone(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, number: String, label: String, fallbackToFirstPhone: Boolean) {
        val id = findPhoneRowId(context, rawId, label, fallbackToFirstPhone)
        val builder = if (id > 0L) ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString())) else ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        builder.withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
        builder.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
        builder.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, label)
        ops.add(builder.build())
    }

    private fun deletePhone(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, label: String) {
        val id = findPhoneRowId(context, rawId, label, fallbackToFirstPhone = false)
        if (id > 0L) ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString())).build())
    }

    private fun upsertOrDelete(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, mime: String, values: Map<String, Any>, keep: Boolean) {
        if (keep) upsert(context, ops, rawId, mime, values) else deleteRow(context, ops, rawId, mime)
    }

    private fun upsert(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, mime: String, values: Map<String, Any>) {
        val id = findDataRowId(context, rawId, mime)
        val builder = if (id > 0L) ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString())) else ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, mime)
        values.forEach { (key, value) -> builder.withValue(key, value) }
        ops.add(builder.build())
    }

    private fun deleteRow(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, mime: String) {
        val id = findDataRowId(context, rawId, mime)
        if (id > 0L) ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString())).build())
    }

    private fun keepTogether(ops: ArrayList<ContentProviderOperation>, existingRawId: Long, callReportRawId: Long) {
        if (existingRawId <= 0L || existingRawId == callReportRawId) return
        ops.add(ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI).withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER).withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawId).withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, callReportRawId).build())
    }

    private fun findDataRowId(context: Context, rawId: Long, mime: String): Long {
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(rawId.toString(), mime), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
    }

    private fun findPhoneRowId(context: Context, rawId: Long, label: String, fallbackToFirstPhone: Boolean): Long {
        val byLabel = runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.LABEL}=?", arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, label), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
        if (byLabel > 0L || !fallbackToFirstPhone) return byLabel
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
    }

    private fun ensureAccount(context: Context) {
        val account = Account(ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE)
        val manager = AccountManager.get(context)
        runCatching { if (manager.getAccountsByType(CallReportContactIntegration.ACCOUNT_TYPE).none { it.name == ACCOUNT_NAME }) manager.addAccountExplicitly(account, null, null) }
        runCatching { ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1); ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true) }
    }

    private fun ensureGroup(context: Context, title: String): Long {
        val groupTitle = title.trim()
        if (groupTitle.isBlank()) return 0L
        val existingId = runCatching { context.contentResolver.query(ContactsContract.Groups.CONTENT_URI, arrayOf(ContactsContract.Groups._ID), "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.ACCOUNT_NAME}=? AND ${ContactsContract.Groups.TITLE}=? AND ${ContactsContract.Groups.DELETED}=0", arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, groupTitle), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
        if (existingId > 0L) return existingId
        return runCatching {
            val uri = context.contentResolver.insert(ContactsContract.Groups.CONTENT_URI, ContentValues().apply { put(ContactsContract.Groups.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE); put(ContactsContract.Groups.ACCOUNT_NAME, ACCOUNT_NAME); put(ContactsContract.Groups.TITLE, groupTitle); put(ContactsContract.Groups.GROUP_VISIBLE, 1); put(ContactsContract.Groups.SHOULD_SYNC, 1) })
            if (uri == null) 0L else ContentUris.parseId(uri)
        }.getOrDefault(0L)
    }

    private fun findCallReportRawContactId(context: Context, phone: String): Long {
        val bySync = runCatching { context.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID), "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.SYNC1}=? AND ${ContactsContract.RawContacts.DELETED}=0", arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, phone), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
        if (bySync > 0L) return bySync
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.RAW_CONTACT_ID), "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0 AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.NUMBER}=?", arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phone), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
    }

    private fun findExistingRawContactId(context: Context, phone: String): Long {
        val contactId = runCatching { context.contentResolver.query(ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(), arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
        if (contactId <= 0L) return 0L
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.RAW_CONTACT_ID), "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? AND ${ContactsContract.RawContacts.DELETED}=0", arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
