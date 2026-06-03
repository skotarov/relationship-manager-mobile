package com.onlineimoti.calllog

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract

object CrmContactAccountStore {
    const val ACCOUNT_NAME = "Relation Management"
    const val LEGACY_ACCOUNT_NAME = "Call Report"
    const val EXTRA_PHONE_LABEL = "RM доп."

    fun ensureAccount(context: Context, syncAutomatically: Boolean = false) {
        val account = Account(ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE)
        val manager = AccountManager.get(context)
        runCatching {
            val accounts = manager.getAccountsByType(CallReportContactIntegration.ACCOUNT_TYPE)
            val hasNew = accounts.any { it.name == ACCOUNT_NAME }
            val legacy = accounts.firstOrNull { it.name == LEGACY_ACCOUNT_NAME }
            when {
                hasNew -> Unit
                legacy != null -> manager.renameAccount(legacy, ACCOUNT_NAME, null, null)
                else -> manager.addAccountExplicitly(account, null, null)
            }
        }
        runCatching {
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, syncAutomatically)
        }
        ensureAccountVisibility(context, syncAutomatically)
    }

    private fun ensureAccountVisibility(context: Context, shouldSync: Boolean) {
        upsertAccountVisibility(context, ACCOUNT_NAME, shouldSync)
        runCatching {
            context.contentResolver.delete(
                ContactsContract.Settings.CONTENT_URI,
                "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=?",
                arrayOf(LEGACY_ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE),
            )
        }
    }

    private fun upsertAccountVisibility(context: Context, accountName: String, shouldSync: Boolean) {
        val values = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, accountName)
            put(ContactsContract.Settings.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            put(ContactsContract.Settings.SHOULD_SYNC, if (shouldSync) 1 else 0)
        }
        val existing = runCatching {
            context.contentResolver.query(
                ContactsContract.Settings.CONTENT_URI,
                arrayOf(ContactsContract.Settings.ACCOUNT_NAME),
                "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=?",
                arrayOf(accountName, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

        runCatching {
            if (existing) {
                context.contentResolver.update(
                    ContactsContract.Settings.CONTENT_URI,
                    values,
                    "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=?",
                    arrayOf(accountName, CallReportContactIntegration.ACCOUNT_TYPE),
                )
            } else {
                context.contentResolver.insert(ContactsContract.Settings.CONTENT_URI, values)
            }
        }
    }

    fun ensureGroup(context: Context, title: String): Long {
        val groupTitle = title.trim()
        if (groupTitle.isBlank()) return 0L
        val existingId = runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID),
                "${ContactsContract.Groups.ACCOUNT_TYPE}=? AND ${ContactsContract.Groups.ACCOUNT_NAME} IN (?, ?) AND ${ContactsContract.Groups.TITLE}=? AND ${ContactsContract.Groups.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, LEGACY_ACCOUNT_NAME, groupTitle),
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
                    put(ContactsContract.Groups.SHOULD_SYNC, 0)
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
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?) AND ${ContactsContract.RawContacts.SYNC1}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, ACCOUNT_NAME, LEGACY_ACCOUNT_NAME, phone),
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
