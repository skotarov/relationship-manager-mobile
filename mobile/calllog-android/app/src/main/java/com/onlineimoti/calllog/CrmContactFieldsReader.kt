package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CrmContactFieldsReader {
    private const val LEGACY_SIP_MIME_TYPE = "vnd.android.cursor.item/sip_address"
    private const val LEGACY_IM_MIME_TYPE = "vnd.android.cursor.item/im"

    fun load(context: Context, phone: String): CallReportStableCrmContactWriter.Fields? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val originalPhone = PhoneNormalizer.normalize(phone)
        if (originalPhone.isBlank()) return null
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, originalPhone)
        if (rawId <= 0L) return null

        val rows = readRows(context, rawId)
        if (rows.isEmpty()) return null

        val name = rows.firstOrNull { it.mime == ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }
        val organization = rows.firstOrNull { it.mime == ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE }
        val note = rows.firstOrNull { it.mime == ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE }
        val custom = rows.firstOrNull { it.mime == CallReportCrmContactWriter.CRM_MIME_TYPE }
        val nickname = rows.firstOrNull { it.mime == ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE }
        val legacySip = rows.firstOrNull { it.mime == LEGACY_SIP_MIME_TYPE }
        val legacyIm = rows.firstOrNull { it.mime == LEGACY_IM_MIME_TYPE }

        return CallReportStableCrmContactWriter.Fields(
            originalPhone = originalPhone,
            displayName = name?.value(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME).orEmpty(),
            additionalPhone = phoneByLabel(rows, CrmContactAccountStore.EXTRA_PHONE_LABEL),
            organization = organization?.value(ContactsContract.CommonDataKinds.Organization.COMPANY).orEmpty(),
            jobTitle = organization?.value(ContactsContract.CommonDataKinds.Organization.TITLE).orEmpty(),
            website = typedWebsite(rows, ContactsContract.CommonDataKinds.Website.TYPE_WORK),
            note = note?.value(ContactsContract.CommonDataKinds.Note.NOTE).orEmpty(),
            groupName = groupName(context, rows),
            customText = custom?.value(ContactsContract.Data.DATA1).orEmpty(),
            namePrefix = name?.value(ContactsContract.CommonDataKinds.StructuredName.PREFIX).orEmpty(),
            givenName = name?.value(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME).orEmpty(),
            middleName = name?.value(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME).orEmpty(),
            familyName = name?.value(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME).orEmpty(),
            nameSuffix = name?.value(ContactsContract.CommonDataKinds.StructuredName.SUFFIX).orEmpty(),
            phoneticName = name?.value(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME).orEmpty(),
            phoneHome = typedPhone(rows, ContactsContract.CommonDataKinds.Phone.TYPE_HOME),
            phoneWork = typedPhone(rows, ContactsContract.CommonDataKinds.Phone.TYPE_WORK),
            phoneOther = typedPhone(rows, ContactsContract.CommonDataKinds.Phone.TYPE_OTHER),
            phoneFaxWork = typedPhone(rows, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK),
            phoneFaxHome = typedPhone(rows, ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME),
            phonePager = typedPhone(rows, ContactsContract.CommonDataKinds.Phone.TYPE_PAGER),
            emailHome = typedEmail(rows, ContactsContract.CommonDataKinds.Email.TYPE_HOME),
            emailWork = typedEmail(rows, ContactsContract.CommonDataKinds.Email.TYPE_WORK),
            emailOther = typedEmail(rows, ContactsContract.CommonDataKinds.Email.TYPE_OTHER),
            department = organization?.value(ContactsContract.CommonDataKinds.Organization.DEPARTMENT).orEmpty(),
            officeLocation = organization?.value(ContactsContract.CommonDataKinds.Organization.OFFICE_LOCATION).orEmpty(),
            websiteHome = typedWebsite(rows, ContactsContract.CommonDataKinds.Website.TYPE_HOME),
            websiteBlog = typedWebsite(rows, ContactsContract.CommonDataKinds.Website.TYPE_BLOG),
            websiteProfile = typedWebsite(rows, ContactsContract.CommonDataKinds.Website.TYPE_PROFILE),
            addressHomeStreet = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME, ContactsContract.CommonDataKinds.StructuredPostal.STREET),
            addressHomeCity = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME, ContactsContract.CommonDataKinds.StructuredPostal.CITY),
            addressHomeRegion = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME, ContactsContract.CommonDataKinds.StructuredPostal.REGION),
            addressHomePostcode = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME, ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE),
            addressHomeCountry = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME, ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY),
            addressWorkStreet = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK, ContactsContract.CommonDataKinds.StructuredPostal.STREET),
            addressWorkCity = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK, ContactsContract.CommonDataKinds.StructuredPostal.CITY),
            addressWorkRegion = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK, ContactsContract.CommonDataKinds.StructuredPostal.REGION),
            addressWorkPostcode = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK, ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE),
            addressWorkCountry = postal(rows, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK, ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY),
            birthday = event(rows, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY),
            anniversary = event(rows, ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY),
            otherDate = event(rows, ContactsContract.CommonDataKinds.Event.TYPE_OTHER),
            nickname = nickname?.value(ContactsContract.CommonDataKinds.Nickname.NAME).orEmpty(),
            sipAddress = custom?.value(ContactsContract.Data.DATA4).orEmpty()
                .ifBlank { legacySip?.value(ContactsContract.Data.DATA1).orEmpty() },
            im = custom?.value(ContactsContract.Data.DATA5).orEmpty()
                .ifBlank { legacyIm?.value(ContactsContract.Data.DATA1).orEmpty() },
            relationSpouse = relation(rows, ContactsContract.CommonDataKinds.Relation.TYPE_SPOUSE),
            relationAssistant = relation(rows, ContactsContract.CommonDataKinds.Relation.TYPE_ASSISTANT),
            relationManager = relation(rows, ContactsContract.CommonDataKinds.Relation.TYPE_MANAGER),
            relationReferredBy = relation(rows, ContactsContract.CommonDataKinds.Relation.TYPE_REFERRED_BY),
        )
    }

    private fun readRows(context: Context, rawId: Long): List<DataRow> {
        val projection = arrayOf(
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7,
            ContactsContract.Data.DATA8,
            ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10,
            ContactsContract.Data.DATA11,
            ContactsContract.Data.DATA12,
            ContactsContract.Data.DATA13,
            ContactsContract.Data.DATA14,
            ContactsContract.Data.DATA15,
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                "${ContactsContract.Data.RAW_CONTACT_ID}=?",
                arrayOf(rawId.toString()),
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val values = projection.associateWith { column ->
                            val index = cursor.getColumnIndex(column)
                            if (index >= 0) cursor.getString(index).orEmpty() else ""
                        }
                        add(DataRow(values[ContactsContract.Data.MIMETYPE].orEmpty(), values))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun phoneByLabel(rows: List<DataRow>, label: String): String {
        return rows.firstOrNull {
            it.mime == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE && it.value(ContactsContract.CommonDataKinds.Phone.LABEL) == label
        }?.value(ContactsContract.CommonDataKinds.Phone.NUMBER).orEmpty()
    }

    private fun typedPhone(rows: List<DataRow>, type: Int): String = typed(rows, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE, type, ContactsContract.CommonDataKinds.Phone.NUMBER)
    private fun typedEmail(rows: List<DataRow>, type: Int): String = typed(rows, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.TYPE, type, ContactsContract.CommonDataKinds.Email.ADDRESS)
    private fun typedWebsite(rows: List<DataRow>, type: Int): String = typed(rows, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Website.TYPE, type, ContactsContract.CommonDataKinds.Website.URL)
    private fun event(rows: List<DataRow>, type: Int): String = typed(rows, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE, type, ContactsContract.CommonDataKinds.Event.START_DATE)
    private fun relation(rows: List<DataRow>, type: Int): String = typed(rows, ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Relation.TYPE, type, ContactsContract.CommonDataKinds.Relation.NAME)
    private fun postal(rows: List<DataRow>, type: Int, column: String): String = typed(rows, ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE, type, column)

    private fun typed(rows: List<DataRow>, mime: String, typeColumn: String, type: Int, valueColumn: String): String {
        return rows.firstOrNull { it.mime == mime && it.value(typeColumn).toIntOrNull() == type }?.value(valueColumn).orEmpty()
    }

    private fun groupName(context: Context, rows: List<DataRow>): String {
        val groupId = rows.firstOrNull { it.mime == ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE }
            ?.value(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID)
            ?.toLongOrNull()
            ?: return ""
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups.TITLE),
                "${ContactsContract.Groups._ID}=?",
                arrayOf(groupId.toString()),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }.orEmpty()
        }.getOrDefault("")
    }

    private data class DataRow(
        val mime: String,
        val values: Map<String, String>,
    ) {
        fun value(column: String): String = values[column].orEmpty()
    }
}
