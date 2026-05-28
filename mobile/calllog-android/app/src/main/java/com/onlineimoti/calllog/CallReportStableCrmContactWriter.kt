package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object CallReportStableCrmContactWriter {
    data class Fields(
        val originalPhone: String,
        val displayName: String = "",
        val additionalPhone: String = "",
        val organization: String = "",
        val jobTitle: String = "",
        val website: String = "",
        val note: String = "",
        val groupName: String = "",
        val customText: String = "",
        val namePrefix: String = "",
        val givenName: String = "",
        val middleName: String = "",
        val familyName: String = "",
        val nameSuffix: String = "",
        val phoneticName: String = "",
        val phoneHome: String = "",
        val phoneWork: String = "",
        val phoneOther: String = "",
        val phoneFaxWork: String = "",
        val phoneFaxHome: String = "",
        val phonePager: String = "",
        val emailHome: String = "",
        val emailWork: String = "",
        val emailOther: String = "",
        val department: String = "",
        val officeLocation: String = "",
        val websiteHome: String = "",
        val websiteBlog: String = "",
        val websiteProfile: String = "",
        val addressHomeStreet: String = "",
        val addressHomeCity: String = "",
        val addressHomeRegion: String = "",
        val addressHomePostcode: String = "",
        val addressHomeCountry: String = "",
        val addressWorkStreet: String = "",
        val addressWorkCity: String = "",
        val addressWorkRegion: String = "",
        val addressWorkPostcode: String = "",
        val addressWorkCountry: String = "",
        val birthday: String = "",
        val anniversary: String = "",
        val otherDate: String = "",
        val nickname: String = "",
        val sipAddress: String = "",
        val im: String = "",
        val relationSpouse: String = "",
        val relationAssistant: String = "",
        val relationManager: String = "",
        val relationReferredBy: String = "",
    )

    fun save(context: Context, fields: Fields): Boolean {
        return runCatching {
            val originalPhone = PhoneNormalizer.normalize(fields.originalPhone)
            if (originalPhone.isBlank()) return@runCatching false
            if (!canReadAndWriteContacts(context)) return@runCatching false

            CrmContactAccountStore.ensureAccount(context)
            val existingRawContactId = CrmContactAccountStore.findExistingRawContactId(context, originalPhone)
            val normalized = CrmContactNormalizedFields.from(fields, originalPhone, existingRawContactId)
            val groupId = CrmContactAccountStore.ensureGroup(context, normalized.groupName)
            val callReportRawContactId = CrmContactAccountStore.findCallReportRawContactId(context, originalPhone)
            val ops = arrayListOf<ContentProviderOperation>()

            if (callReportRawContactId > 0L) {
                updateRawContact(context, ops, callReportRawContactId, normalized, groupId, existingRawContactId)
                keepTogether(ops, existingRawContactId, callReportRawContactId)
            } else {
                createRawContact(ops, normalized, existingRawContactId, groupId)
            }

            if (ops.isNotEmpty()) context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            CrmContactAccountStore.findCallReportRawContactId(context, originalPhone) > 0L
        }.getOrDefault(false)
    }

    private fun createRawContact(
        ops: ArrayList<ContentProviderOperation>,
        fields: CrmContactNormalizedFields,
        existingRawContactId: Long,
        groupId: Long,
    ) {
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, CrmContactAccountStore.ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, fields.originalPhone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )
        CrmContactDataRows.insertStructuredName(ops, fields)
        CrmContactDataRows.insertPhone(ops, fields.originalPhone, CrmContactAccountStore.ACCOUNT_NAME)
        if (fields.additionalPhone.isNotBlank()) {
            CrmContactDataRows.insertPhone(ops, fields.additionalPhone, CrmContactAccountStore.EXTRA_PHONE_LABEL)
        }
        CrmContactDataRows.insertOptionalRows(ops, fields, groupId)
        CrmContactDataRows.insertHistoryRow(ops, fields.originalPhone)
        keepTogetherWithBackReference(ops, existingRawContactId)
    }

    private fun updateRawContact(
        context: Context,
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        fields: CrmContactNormalizedFields,
        groupId: Long,
        existingRawContactId: Long,
    ) {
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, fields.originalPhone)
                .build()
        )
        CrmContactDataRows.upsertStructuredName(context, ops, rawId, fields, existingRawContactId <= 0L)
        CrmContactDataRows.upsertPhone(
            context = context,
            ops = ops,
            rawId = rawId,
            number = fields.originalPhone,
            label = CrmContactAccountStore.ACCOUNT_NAME,
            fallbackToFirstPhone = true,
        )
        if (fields.additionalPhone.isNotBlank()) {
            CrmContactDataRows.upsertPhone(context, ops, rawId, fields.additionalPhone, CrmContactAccountStore.EXTRA_PHONE_LABEL, fallbackToFirstPhone = false)
        } else {
            CrmContactDataRows.deletePhone(context, ops, rawId, CrmContactAccountStore.EXTRA_PHONE_LABEL)
        }
        CrmContactDataRows.upsertOptionalRows(context, ops, rawId, fields, groupId)
        CrmContactDataRows.upsert(
            context,
            ops,
            rawId,
            CallReportContactIntegration.HISTORY_MIME_TYPE,
            mapOf(
                ContactsContract.Data.DATA1 to fields.originalPhone,
                ContactsContract.Data.DATA2 to CrmContactAccountStore.ACCOUNT_NAME,
                ContactsContract.Data.DATA3 to "История",
            ),
        )
    }

    private fun keepTogetherWithBackReference(ops: ArrayList<ContentProviderOperation>, existingRawId: Long) {
        if (existingRawId <= 0L) return
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawId)
                .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, 0)
                .build()
        )
    }

    private fun keepTogether(ops: ArrayList<ContentProviderOperation>, existingRawId: Long, callReportRawId: Long) {
        if (existingRawId <= 0L || existingRawId == callReportRawId) return
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawId)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, callReportRawId)
                .build()
        )
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
