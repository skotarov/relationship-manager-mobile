package com.onlineimoti.calllog

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract

object CrmContactAccountStore {
    const val ACCOUNT_NAME = "Call Report"
    const val EXTRA_PHONE_LABEL = "Call Report доп."

    fun ensureAccount(context: Context) {
        val account = Account(ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE)
        val manager = AccountManager.get(context)
        runCatching {
            if (manager.getAccountsByType(CallReportContactIntegration.ACCOUNT_TYPE).none { it.name == ACCOUNT_NAME }) {
                manager.addAccountExplicitly(account, null, null)
            }
        }
        runCatching {
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        }
    }

    fun ensureGroup(context: Context, title: String): Long {
        val groupTitle = title.trim()
        if (groupTitle.isBlank()) return 0L
        val existingId = runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID),
                "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.ACCOUNT_NAME}=? AND ${ContactsContract.Groups.TITLE}=? AND ${ContactsContract.Groups.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, groupTitle),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (existingId > 0L) return existingId

        return runCatching {
            val uri = context.contentResolver.insert(
                ContactsContract.Groups.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Groups.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                    put(ContactsContract.Groups.ACCOUNT_NAME, ACCOUNT_NAME)
                    put(ContactsContract.Groups.TITLE, groupTitle)
                    put(ContactsContract.Groups.GROUP_VISIBLE, 1)
                    put(ContactsContract.Groups.SHOULD_SYNC, 1)
                },
            )
            if (uri == null) 0L else ContentUris.parseId(uri)
        }.getOrDefault(0L)
    }

    fun findCallReportRawContactId(context: Context, phone: String): Long {
        val bySync = runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.SYNC1}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, phone),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (bySync > 0L) return bySync

        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0 AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.NUMBER}=?",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phone),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    fun findExistingRawContactId(context: Context, phone: String): Long {
        val contactId = runCatching {
            context.contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(),
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
        if (contactId <= 0L) return 0L

        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }
}
