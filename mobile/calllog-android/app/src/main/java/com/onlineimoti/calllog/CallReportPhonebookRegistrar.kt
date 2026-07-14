package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object CallReportPhonebookRegistrar {
    private const val MAX_CONTACTS_PER_SYNC = 2500

    fun registerAll(context: Context) {
        if (!ConfigStore.load(context).useLinkedContactIntegration) return
        if (!CallReportLegacyContactLookup.hasContactPermissions(context)) return
        CrmContactAccountStore.ensureAccount(context)

        val seenPhones = linkedSetOf<String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cursor ->
                val phoneIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (cursor.moveToNext() && seenPhones.size < MAX_CONTACTS_PER_SYNC) {
                    val phone = PhoneNormalizer.normalize(cursor.getString(phoneIndex).orEmpty())
                    if (phone.isBlank() || !seenPhones.add(phone)) continue
                    CallReportLegacyContactWriter.link(context, phone, cursor.getString(nameIndex).orEmpty())
                }
            }
        }
    }
}
