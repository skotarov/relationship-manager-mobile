package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object RmRealContactLookup {
    fun findRawContactId(context: Context, phone: String): Long {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return 0L
        val direct = CrmContactAccountStore.findExistingRawContactId(context, normalizedPhone)
        if (direct > 0L) return direct
        return findByNormalizedPhoneRow(context, normalizedPhone)
    }

    private fun findByNormalizedPhoneRow(context: Context, normalizedPhone: String): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.RAW_CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                var found = 0L
                while (cursor.moveToNext() && found <= 0L) {
                    val rowPhone = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (samePhone(rowPhone, normalizedPhone)) {
                        found = cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                    }
                }
                found
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun samePhone(left: String, right: String): Boolean {
        val a = PhoneNormalizer.normalize(left)
        val b = PhoneNormalizer.normalize(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val ad = a.filter { it.isDigit() }
        val bd = b.filter { it.isDigit() }
        return ad.length >= 9 && bd.length >= 9 && ad.takeLast(9) == bd.takeLast(9)
    }
}
