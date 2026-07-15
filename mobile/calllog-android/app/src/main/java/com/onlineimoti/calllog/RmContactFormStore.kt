package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal data class RmContactFormValues(
    val displayName: String = "",
    val givenName: String = "",
    val middleName: String = "",
    val familyName: String = "",
    val additionalPhone: String = "",
    val organization: String = "",
    val jobTitle: String = "",
    val emailWork: String = "",
    val note: String = "",
) {
    fun withFallbackName(fallbackTitle: String, phone: String): RmContactFormValues {
        val personName = listOf(givenName, middleName, familyName).filter { it.isNotBlank() }.joinToString(" ")
        val fallback = fallbackTitle.trim().takeUnless { it.isBlank() || it == phone }
        return copy(displayName = displayName.ifBlank { personName }.ifBlank { fallback.orEmpty() }.ifBlank { phone })
    }
}

internal object RmContactFormStore {
    fun read(context: Context, phone: String, fallbackTitle: String): RmContactFormValues {
        val fallbackName = fallbackTitle.trim().takeUnless { it.isBlank() || it == phone }.orEmpty()
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return RmContactFormValues(displayName = fallbackName)

        var values = RmContactFormValues(displayName = fallbackName)
        val mimes = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        )
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString(",") { "?" }})"
        val args = arrayOf(rawId.toString(), *mimes)
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3,
                    ContactsContract.Data.DATA4,
                    ContactsContract.Data.DATA5,
                ),
                selection,
                args,
                null,
            )?.use { cursor ->
                val mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                val data1Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                val data2Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
                val data3Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
                val data4Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
                val data5Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA5)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val data1 = cursor.getString(data1Index).orEmpty()
                    val data2 = cursor.getString(data2Index).orEmpty()
                    val data3 = cursor.getString(data3Index).orEmpty()
                    val data4 = cursor.getString(data4Index).orEmpty()
                    val data5 = cursor.getString(data5Index).orEmpty()
                    values = when (mime) {
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> values.copy(
                            displayName = data1.ifBlank { values.displayName },
                            givenName = data2,
                            familyName = data3,
                            middleName = data5,
                        )
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            val isAdditionalPhone = data3 == CrmContactAccountStore.EXTRA_PHONE_LABEL
                            if (isAdditionalPhone) values.copy(additionalPhone = data1) else values
                        }
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> values.copy(
                            organization = data1,
                            jobTitle = data4,
                        )
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            val type = data2.toIntOrNull() ?: ContactsContract.CommonDataKinds.Email.TYPE_OTHER
                            when {
                                type == ContactsContract.CommonDataKinds.Email.TYPE_WORK -> values.copy(emailWork = data1)
                                values.emailWork.isBlank() -> values.copy(emailWork = data1)
                                else -> values
                            }
                        }
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> values.copy(note = data1)
                        else -> values
                    }
                }
            }
        }
        return values
    }
}
