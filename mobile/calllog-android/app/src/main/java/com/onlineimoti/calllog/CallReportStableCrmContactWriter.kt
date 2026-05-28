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
        val namePrefix: String,
        val givenName: String,
        val middleName: String,
        val familyName: String,
        val nameSuffix: String,
        val phoneticName: String,
        val phoneHome: String,
        val phoneWork: String,
        val phoneOther: String,
        val phoneFaxWork: String,
        val phoneFaxHome: String,
        val phonePager: String,
        val emailHome: String,
        val emailWork: String,
        val emailOther: String,
        val department: String,
        val officeLocation: String,
        val websiteHome: String,
        val websiteBlog: String,
        val websiteProfile: String,
        val addressHomeStreet: String,
        val addressHomeCity: String,
        val addressHomeRegion: String,
        val addressHomePostcode: String,
        val addressHomeCountry: String,
        val addressWorkStreet: String,
        val addressWorkCity: String,
        val addressWorkRegion: String,
        val addressWorkPostcode: String,
        val addressWorkCountry: String,
        val birthday: String,
        val anniversary: String,
        val otherDate: String,
        val nickname: String,
        val sipAddress: String,
        val im: String,
        val relationSpouse: String,
        val relationAssistant: String,
        val relationManager: String,
        val relationReferredBy: String,
    )

    fun save(context: Context, fields: Fields): Boolean {
        return runCatching {
            val originalPhone = PhoneNormalizer.normalize(fields.originalPhone)
            if (originalPhone.isBlank()) return@runCatching false
            if (!canReadAndWriteContacts(context)) return@runCatching false

            ensureAccount(context)
            val existingRawContactId = findExistingRawContactId(context, originalPhone)
            val normalized = fields.normalized(originalPhone, existingRawContactId)
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

    private fun Fields.normalized(originalPhone: String, existingRawContactId: Long): Normalized {
        return Normalized(
            originalPhone = originalPhone,
            displayName = if (existingRawContactId > 0L) "" else displayName.trim().ifBlank { originalPhone },
            additionalPhone = PhoneNormalizer.normalize(additionalPhone).takeIf { it.isNotBlank() && it != originalPhone }.orEmpty(),
            organization = organization.trim(),
            jobTitle = jobTitle.trim(),
            website = website.trim(),
            note = note.trim(),
            groupName = groupName.trim(),
            customText = customText.trim(),
            namePrefix = namePrefix.trim(),
            givenName = givenName.trim(),
            middleName = middleName.trim(),
            familyName = familyName.trim(),
            nameSuffix = nameSuffix.trim(),
            phoneticName = phoneticName.trim(),
            phoneHome = PhoneNormalizer.normalize(phoneHome),
            phoneWork = PhoneNormalizer.normalize(phoneWork),
            phoneOther = PhoneNormalizer.normalize(phoneOther),
            phoneFaxWork = PhoneNormalizer.normalize(phoneFaxWork),
            phoneFaxHome = PhoneNormalizer.normalize(phoneFaxHome),
            phonePager = PhoneNormalizer.normalize(phonePager),
            emailHome = emailHome.trim(),
            emailWork = emailWork.trim(),
            emailOther = emailOther.trim(),
            department = department.trim(),
            officeLocation = officeLocation.trim(),
            websiteHome = websiteHome.trim(),
            websiteBlog = websiteBlog.trim(),
            websiteProfile = websiteProfile.trim(),
            addressHomeStreet = addressHomeStreet.trim(),
            addressHomeCity = addressHomeCity.trim(),
            addressHomeRegion = addressHomeRegion.trim(),
            addressHomePostcode = addressHomePostcode.trim(),
            addressHomeCountry = addressHomeCountry.trim(),
            addressWorkStreet = addressWorkStreet.trim(),
            addressWorkCity = addressWorkCity.trim(),
            addressWorkRegion = addressWorkRegion.trim(),
            addressWorkPostcode = addressWorkPostcode.trim(),
            addressWorkCountry = addressWorkCountry.trim(),
            birthday = birthday.trim(),
            anniversary = anniversary.trim(),
            otherDate = otherDate.trim(),
            nickname = nickname.trim(),
            sipAddress = sipAddress.trim(),
            im = im.trim(),
            relationSpouse = relationSpouse.trim(),
            relationAssistant = relationAssistant.trim(),
            relationManager = relationManager.trim(),
            relationReferredBy = relationReferredBy.trim(),
        )
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
        insertStructuredName(ops, fields)
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
        upsertStructuredName(context, ops, rawId, fields, existingRawContactId <= 0L)
        upsertPhone(context, ops, rawId, fields.originalPhone, ACCOUNT_NAME, fallbackToFirstPhone = true)
        if (fields.additionalPhone.isNotBlank()) upsertPhone(context, ops, rawId, fields.additionalPhone, EXTRA_PHONE_LABEL, fallbackToFirstPhone = false) else deletePhone(context, ops, rawId, EXTRA_PHONE_LABEL)
        upsertOptionalRows(context, ops, rawId, fields, groupId)
        upsert(context, ops, rawId, CallReportContactIntegration.HISTORY_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.originalPhone, ContactsContract.Data.DATA2 to ACCOUNT_NAME, ContactsContract.Data.DATA3 to "История"))
    }

    private fun insertStructuredName(ops: ArrayList<ContentProviderOperation>, fields: Normalized) {
        val values = structuredNameValues(fields)
        if (values.isNotEmpty()) insertRow(ops, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, values)
    }

    private fun upsertStructuredName(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, fields: Normalized, allowDisplayName: Boolean) {
        val values = structuredNameValues(fields, allowDisplayName)
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, values, values.isNotEmpty())
    }

    private fun structuredNameValues(fields: Normalized, allowDisplayName: Boolean = true): Map<String, Any> {
        val values = linkedMapOf<String, Any>()
        if (allowDisplayName && fields.displayName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME] = fields.displayName
        if (fields.namePrefix.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.PREFIX] = fields.namePrefix
        if (fields.givenName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME] = fields.givenName
        if (fields.middleName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME] = fields.middleName
        if (fields.familyName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME] = fields.familyName
        if (fields.nameSuffix.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.SUFFIX] = fields.nameSuffix
        if (fields.phoneticName.isNotBlank()) values[ContactsContract.CommonDataKinds.StructuredName.PHONETIC_NAME] = fields.phoneticName
        return values
    }

    private fun insertOptionalRows(ops: ArrayList<ContentProviderOperation>, fields: Normalized, groupId: Long) {
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
        if (fields.im.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Im.DATA to fields.im, ContactsContract.CommonDataKinds.Im.PROTOCOL to ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL to ACCOUNT_NAME))
        if (fields.note.isNotBlank()) insertRow(ops, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note))
        if (groupId > 0L) insertRow(ops, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId))
        if (fields.customText.isNotBlank()) insertRow(ops, CallReportCrmContactWriter.CRM_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.customText, ContactsContract.Data.DATA2 to "Call Report CRM", ContactsContract.Data.DATA3 to "CRM"))
    }

    private fun upsertOptionalRows(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, fields: Normalized, groupId: Long) {
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
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Im.DATA to fields.im, ContactsContract.CommonDataKinds.Im.PROTOCOL to ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM, ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL to ACCOUNT_NAME), fields.im.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Note.NOTE to fields.note), fields.note.isNotBlank())
        upsertOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID to groupId), groupId > 0L)
        upsertOrDelete(context, ops, rawId, CallReportCrmContactWriter.CRM_MIME_TYPE, mapOf(ContactsContract.Data.DATA1 to fields.customText, ContactsContract.Data.DATA2 to "Call Report CRM", ContactsContract.Data.DATA3 to "CRM"), fields.customText.isNotBlank())
    }

    private fun organizationValues(fields: Normalized): Map<String, Any> {
        return linkedMapOf<String, Any>().apply {
            put(ContactsContract.CommonDataKinds.Organization.TYPE, ContactsContract.CommonDataKinds.Organization.TYPE_WORK)
            if (fields.organization.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.COMPANY, fields.organization)
            if (fields.jobTitle.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.TITLE, fields.jobTitle)
            if (fields.department.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.DEPARTMENT, fields.department)
            if (fields.officeLocation.isNotBlank()) put(ContactsContract.CommonDataKinds.Organization.OFFICE_LOCATION, fields.officeLocation)
        }
    }

    private fun insertPhone(ops: ArrayList<ContentProviderOperation>, number: String, label: String) {
        insertRow(ops, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Phone.NUMBER to number, ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM, ContactsContract.CommonDataKinds.Phone.LABEL to label))
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

    private fun insertPostalIfPresent(ops: ArrayList<ContentProviderOperation>, fields: Normalized, type: Int) {
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

    private fun upsertPostal(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, fields: Normalized, type: Int) {
        val values = postalValues(fields, type)
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE, type, values, values.isNotEmpty())
    }

    private fun upsertEvent(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, date: String, type: Int) {
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE, type, mapOf(ContactsContract.CommonDataKinds.Event.START_DATE to date, ContactsContract.CommonDataKinds.Event.TYPE to type), date.isNotBlank())
    }

    private fun upsertRelation(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, name: String, type: Int) {
        upsertTypedOrDelete(context, ops, rawId, ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Relation.TYPE, type, mapOf(ContactsContract.CommonDataKinds.Relation.NAME to name, ContactsContract.CommonDataKinds.Relation.TYPE to type), name.isNotBlank())
    }

    private fun postalValues(fields: Normalized, type: Int): Map<String, Any> {
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

    private fun findTypedDataRowId(context: Context, rawId: Long, mime: String, typeColumn: String, typeValue: Int): Long {
        return runCatching { context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND $typeColumn=?", arrayOf(rawId.toString(), mime, typeValue.toString()), null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0L)
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
