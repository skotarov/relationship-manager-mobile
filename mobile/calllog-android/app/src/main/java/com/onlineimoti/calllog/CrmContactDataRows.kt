package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

object CrmContactDataRows {
    fun insertStructuredName(ops: ArrayList<ContentProviderOperation>, fields: CrmContactNormalizedFields) {
        val values = structuredNameValues(fields)
        if (values.isNotEmpty()) insertRow(ops, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, values)
    }

    fun upsertStructuredName(
        context: Context,
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        fields: CrmContactNormalizedFields,
        allowDisplayName: Boolean,
    ) {
        val values = structuredNameValues(fields, allowDisplayName)
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, values, values.isNotEmpty())
    }

    fun insertPhone(ops: ArrayList<ContentProviderOperation>, number: String, label: String) {
        insertRow(
            ops,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            mapOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER to number,
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM,
                ContactsContract.CommonDataKinds.Phone.LABEL to label,
            ),
        )
    }

    fun upsertPhone(
        context: Context,
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        number: String,
        label: String,
        fallbackToFirstPhone: Boolean,
    ) {
        val id = findPhoneRowId(context, rawId, label, fallbackToFirstPhone)
        val builder = if (id > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        }
        builder.withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
        builder.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM)
        builder.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, label)
        ops.add(builder.build())
    }

    fun deletePhone(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, label: String) {
        val id = findPhoneRowId(context, rawId, label, fallbackToFirstPhone = false)
        if (id > 0L) {
            ops.add(
                ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString()))
                    .build()
            )
        }
    }

    fun insertOptionalRows(ops: ArrayList<ContentProviderOperation>, fields: CrmContactNormalizedFields, groupId: Long) {
        if (fields.organization.isNotBlank() || fields.jobTitle.isNotBlank() || fields.department.isNotBlank() || fields.officeLocation.isNotBlank()) {
            insertRow(ops, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, organizationValues(fields))
        }
        insertTypedPhoneIfPresent(ops, fields.phoneHome, ContactsContract.CommonDataKinds.Phone.TYPE_HOME)
        insertTypedPhoneIfPresent(ops, fields.phoneWork, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
        insertTypedPhoneIfPresent(ops, fields.phoneOther, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER)
        insertTypedPhoneIfPresent(ops, fields.phoneFaxWork, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK)
        insertTypedPhoneIfPresent(ops, fields.phoneFaxHome, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME)
        insertTypedPhoneIfPresent(ops, fields.phonePager, ContactsContract.CommonDataKinds.Phone.TYPE_PAGER)
        insertTypedEmailIfPresent(ops, fields.emailHome, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
        insertTypedEmailIfPresent(ops, fields.emailWork, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
        insertTypedEmailIfPresent(ops, fields.emailOther, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
        insertTypedWebsiteIfPresent(ops, fields.website, ContactsContract.CommonDataKinds.Website.TYPE_WORK)
        insertTypedWebsiteIfPresent(ops, fields.websiteHome, ContactsContract.CommonDataKinds.Website.TYPE_HOME)
        insertTypedWebsiteIfPresent(ops, fields.websiteBlog, ContactsContract.CommonDataKinds.Website.TYPE_BLOG)
        insertTypedWebsiteIfPresent(ops, fields.websiteProfile, ContactsContract.CommonDataKinds.Website.TYPE_PROFILE)
        insertPostalIfPresent(ops, fields, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME)
        insertPostalIfPresent(ops, fields, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
        insertEventIfPresent(ops, fields.birthday, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
        insertEventIfPresent(ops, fields.anniversary, ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY)
        insertEventIfPresent(ops, fields.otherDate, ContactsContract.CommonDataKinds.Event.TYPE_OTHER)
        insertRelationIfPresent(ops, fields.relationSpouse, ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE)
        insertRelationIfPresent(ops, fields.relationAssistant, ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT)
        insertRelationIfPresent(ops, fields.relationManager, ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER)
        insertRelationIfPresent(ops, fields.relationReferredBy, ContactsContract.CommonDataKinds.Relation.TYPE_REFERRED_BY)
        if (fields.nickname.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Nickname.NAME to fields.nickname, ContactsContract.CommonDataKinds.Nickname.TYPE to ContactsContract.CommonDataKinds.Nickname.TYPE_DEFAULT))
        if (fields.sipAddress.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS to fields.sipAddress, ContactsContract.CommonDataKinds.SipAddress.TYPE to ContactsContract.CommonDataKinds.SipAddress.TYPE_OTHER))
        if (fields.im.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Im.DATA to fields.im, ContactsContract.CommonDataKinds.Im.PROTOCOL to ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL to CrmContactAccountStore.ACCOUNT_NAME))
        if (fields.note.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note))
        if (groupId > 0L) insertRow(ops, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId))
        if (fields.customText.isNotBlank()) insertRow(ops, CallReportCrmContactWriter.CRM_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.customText, ContactsContract.Data.DATA2 to "Call Report CRM", ContactsContract.Data.DATA3 to "CRM"))
    }

    fun upsertOptionalRows(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, fields: CrmContactNormalizedFields, groupId: Long) {
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, organizationValues(fields), fields.organization.isNotBlank() || fields.jobTitle.isNotBlank() || fields.department.isNotBlank() || fields.officeLocation.isNotBlank())
        upsertTypedPhone(context, ops, rawId, fields.phoneHome, ContactsContract.CommonDataKinds.Phone.TYPE_HOME)
        upsertTypedPhone(context, ops, rawId, fields.phoneWork, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
        upsertTypedPhone(context, ops, rawId, fields.phoneOther, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER)
        upsertTypedPhone(context, ops, rawId, fields.phoneFaxWork, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK)
        upsertTypedPhone(context, ops, rawId, fields.phoneFaxHome, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME)
        upsertTypedPhone(context, ops, rawId, fields.phonePager, ContactsContract.CommonDataKinds.Phone.TYPE_PAGER)
        upsertTypedEmail(context, ops, rawId, fields.emailHome, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
        upsertTypedEmail(context, ops, rawId, fields.emailWork, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
        upsertTypedEmail(context, ops, rawId, fields.emailOther, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
        upsertTypedWebsite(context, ops, rawId, fields.website, ContactsContract.CommonDataKinds.Website.TYPE_WORK)
        upsertTypedWebsite(context, ops, rawId, fields.websiteHome, ContactsContract.CommonDataKinds.Website.TYPE_HOME)
        upsertTypedWebsite(context, ops, rawId, fields.websiteBlog, ContactsContract.CommonDataKinds.Website.TYPE_BLOG)
        upsertTypedWebsite(context, ops, rawId, fields.websiteProfile, ContactsContract.CommonDataKinds.Website.TYPE_PROFILE)
        upsertPostal(context, ops, rawId, fields, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME)
        upsertPostal(context, ops, rawId, fields, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
        upsertEvent(context, ops, rawId, fields.birthday, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
        upsertEvent(context, ops, rawId, fields.anniversary, ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY)
        upsertEvent(context, ops, rawId, fields.otherDate, ContactsContract.CommonDataKinds.Event.TYPE_OTHER)
        upsertRelation(context, ops, rawId, fields.relationSpouse, ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE)
        upsertRelation(context, ops, rawId, fields.relationAssistant, ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT)
        upsertRelation(context, ops, rawId, fields.relationManager, ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER)
        upsertRelation(context, ops, rawId, fields.relationReferredBy, ContactsContract.CommonDataKinds.Relation.TYPE_REFERRED_BY)
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Nickname.NAME to fields.nickname, ContactsContract.CommonDataKinds.Nickname.TYPE to ContactsContract.CommonDataKinds.Nickname.TYPE_DEFAULT), fields.nickname.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS to fields.sipAddress, ContactsContract.CommonDataKinds.SipAddress.TYPE to ContactsContract.CommonDataKinds.SipAddress.TYPE_OTHER), fields.sipAddress.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Im.DATA to fields.im, ContactsContract.CommonDataKinds.Im.PROTOCOL to ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL to CrmContactAccountStore.ACCOUNT_NAME), fields.im.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note), fields.note.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId), groupId > 0L)
        upsertOrDelete(context, ops, rawId, CallReportCrmContactWriter.CRM_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.customText, ContactsContract.Data.DATA2 to "Call Report CRM", ContactsContract.Data.DATA3 to "CRM"), fields.customText.isNotBlank())
    }

    fun insertHistoryRow(ops: ArrayList<ContentProviderOperation>, originalPhone: String) {
        insertRow(ops, CallReportContactIntegration.HISTORY_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to originalPhone, ContactsContract.Data.DATA2 to CrmContactAccountStore.ACCOUNT_NAME, ContactsContract.Data.DATA3 to "История"))
    }

    fun upsert(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, mime: String, values: Map<String, Any>) {
        val id = findDataRowId(context, rawId, mime)
        val builder = if (id > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, mime)
        }
        values.forEach { (key, value) -> builder.withValue(key, value) }
        ops.add(builder.build())
    }

    private fun structuredNameValues(fields: CrmContactNormalizedFields, allowDisplayName: Boolean = true): Map<String, Any> {
        val values = linkedMapOf<String, Any>()
        if (allowDisplayName && fields.displayName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME] = fields.displayName
        if (fields.namePrefix.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.PREFIX] = fields.namePrefix
        if (fields.givenName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME] = fields.givenName
        if (fields.middleName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME] = fields.middleName
        if (fields.familyName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME] = fields.familyName
        if (fields.nameSuffix.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.SUFFIX] = fields.nameSuffix
        if (fields.phoneticName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME] = fields.phoneticName
        return values
    }

    private fun insertRow(ops: ArrayList<ContentProviderOperation>, mime: String, values: Map<String, Any>) {
        val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mime)
        values.forEach { (key, value) -> builder.withValue(key, value) }
        ops.add(builder.build())
    }

    private fun upsertOrDelete(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, mime: String, values: Map<String, Any>, keep: Boolean) {
        if (keep) upsert(context, ops, rawId, mime, values) else deleteRow(context, ops, rawId, mime)
    }

    private fun deleteRow(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, mime: String) {
        val id = findDataRowId(context, rawId, mime)
        if (id > 0L) ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString())).build())
    }

    private fun organizationValues(fields: CrmContactNormalizedFields): Map<String, Any> {
        return linkedMapOf<String, Any>().apply {
            put(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_WORK)
            if (fields.organization.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.COMPANY, fields.organization)
            if (fields.jobTitle.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.TITLE, fields.jobTitle)
            if (fields.department.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, fields.department)
            if (fields.officeLocation.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.OFFICE_LOCATION, fields.officeLocation)
        }
    }

    private fun insertTypedPhoneIfPresent(ops: ArrayList<ContentProviderOperation>, number: String, type: Int) {
        if (number.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Phone.NUMBER to number, ContactsContract.CommonDataKinds.Phone.TYPE to type))
    }

    private fun insertTypedEmailIfPresent(ops: ArrayList<ContentProviderOperation>, email: String, type: Int) {
        if (email.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Email.ADDRESS to email, ContactsContract.CommonDataKinds.Email.TYPE to type))
    }

    private fun insertTypedWebsiteIfPresent(ops: ArrayList<ContentProviderOperation>, url: String, type: Int) {
        if (url.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Website.URL to url, ContactsContract.CommonDataKinds.Website.TYPE to type))
    }

    private fun insertPostalIfPresent(ops: ArrayList<ContentProviderOperation>, fields: CrmContactNormalizedFields, type: Int) {
        val values = postalValues(fields, type)
        if (values.isNotEmpty()) insertRow(ops, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, values)
    }

    private fun insertEventIfPresent(ops: ArrayList<ContentProviderOperation>, date: String, type: Int) {
        if (date.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Event.START_DATE to date, ContactsContract.CommonDataKinds.Event.TYPE to type))
    }

    private fun insertRelationIfPresent(ops: ArrayList<ContentProviderOperation>, name: String, type: Int) {
        if (name.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Relation.NAME to name, ContactsContract.CommonDataKinds.Relation.TYPE to type))
    }

    private fun upsertTypedPhone(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, number: String, type: Int) {
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE, type, mapOf(ContactsContract.CommonDataKinds.Phone.NUMBER to number, ContactsContract.CommonDataKinds.Phone.TYPE to type), number.isNotBlank())
    }

    private fun upsertTypedEmail(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, email: String, type: Int) {
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.TYPE, type, mapOf(ContactsContract.CommonDataKinds.Email.ADDRESS to email, ContactsContract.CommonDataKinds.Email.TYPE to type), email.isNotBlank())
    }

    private fun upsertTypedWebsite(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, url: String, type: Int) {
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Website.TYPE, type, mapOf(ContactsContract.CommonDataKinds.Website.URL to url, ContactsContract.CommonDataKinds.Website.TYPE to type), url.isNotBlank())
    }

    private fun upsertPostal(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, fields: CrmContactNormalizedFields, type: Int) {
        val values = postalValues(fields, type)
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE, type, values, values.isNotEmpty())
    }

    private fun upsertEvent(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, date: String, type: Int) {
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE, type, mapOf(ContactsContract.CommonDataKinds.Event.START_DATE to date, ContactsContract.CommonDataKinds.Event.TYPE to type), date.isNotBlank())
    }

    private fun upsertRelation(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, name: String, type: Int) {
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Relation.TYPE, type, mapOf(ContactsContract.CommonDataKinds.Relation.NAME to name, ContactsContract.CommonDataKinds.Relation.TYPE to type), name.isNotBlank())
    }

    private fun postalValues(fields: CrmContactNormalizedFields, type: Int): Map<String, Any> {
        val values = linkedMapOf<String, Any>()
        values[ContactsContract.CommonDataKinds.StructuredPostal.TYPE] = type
        val isHome = type == ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
        val street = if (isHome) fields.addressHomeStreet else fields.addressWorkStreet
        val city = if (isHome) fields.addressHomeCity else fields.addressWorkCity
        val region = if (isHome) fields.addressHomeRegion else fields.addressWorkRegion
        val postcode = if (isHome) fields.addressHomePostcode else fields.addressWorkPostcode
        val country = if (isHome) fields.addressHomeCountry else fields.addressWorkCountry
        if (street.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredPostal.STREET] = street
        if (city.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredPostal.CITY] = city
        if (region.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredPostal.REGION] = region
        if (postcode.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE] = postcode
        if (country.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY] = country
        return if (values.size > 1) values else emptyMap()
    }

    private fun upsertTypedOrDelete(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, mime: String, typeColumn: String, typeValue: Int, values: Map<String, Any>, keep: Boolean) {
        val id = findTypedDataRowId(context, rawId, mime, typeColumn, typeValue)
        if (!keep) {
            if (id > 0L) ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString())).build())
            return
        }
        val builder = if (id > 0L) ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(id.toString())) else ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId).withValue(ContactsContract.Data.MIMETYPE, mime)
        values.forEach { (key, value) -> builder.withValue(key, value) }
        ops.add(builder.build())
    }

    private fun findDataRowId(context: Context, rawId: Long, mime: String): Long {
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(rawId.toString(), mime), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
    }

    private fun findTypedDataRowId(context: Context, rawId: Long, mime: String, typeColumn: String, typeValue: Int): Long {
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND $typeColumn=?", arrayOf(rawId.toString(), mime, typeValue.toString()), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
    }

    private fun findPhoneRowId(context: Context, rawId: Long, label: String, fallbackToFirstPhone: Boolean): Long {
        val byLabel = runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.LABEL}=?", arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, label), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
        if (byLabel > 0L || !fallbackToFirstPhone) return byLabel
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
    }
}
