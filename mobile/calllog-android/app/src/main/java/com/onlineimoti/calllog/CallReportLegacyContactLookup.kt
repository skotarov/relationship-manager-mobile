package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CallReportLegacyContactLookup {
    fun hasContactPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    fun findRawContactId(context: Context, phone: String): Long {
        val names = CrmContactAccountStore.accountNames()
        val bySync = runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?, ?) AND " +
                    "${ContactsContract.RawContacts.SYNC1}=? AND " +
                    "${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, names[0], names[1], names[2], phone),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (bySync > 0L) return bySync

        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.DELETED}=0 AND " +
                    "${ContactsContract.Data.MIMETYPE}=? AND " +
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER}=?",
                arrayOf(
                    CallReportContactIntegration.ACCOUNT_TYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    phone,
                ),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    fun findAllRawContactIds(context: Context): List<Long> {
        val names = CrmContactAccountStore.accountNames()
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?, ?) AND " +
                    "${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, names[0], names[1], names[2]),
                ContactsContract.RawContacts._ID + " ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun delete(context: Context, rawContactId: Long): Int {
        if (rawContactId <= 0L) return 0
        val names = CrmContactAccountStore.accountNames()
        return runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?, ?) AND " +
                    "${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(rawContactId.toString(), CallReportContactIntegration.ACCOUNT_TYPE, names[0], names[1], names[2]),
            )
        }.getOrDefault(0)
    }

    fun findExistingRawContactId(context: Context, phone: String): Long {
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
                "${ContactsContract.Data.CONTACT_ID}=? AND " +
                    "${ContactsContract.Data.MIMETYPE}=? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? AND " +
                    "${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    CallReportContactIntegration.ACCOUNT_TYPE,
                ),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }
}
