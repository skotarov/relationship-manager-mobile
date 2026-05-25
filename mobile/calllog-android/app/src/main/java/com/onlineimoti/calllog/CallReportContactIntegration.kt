package com.onlineimoti.calllog

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object CallReportContactIntegration {
    const val ACCOUNT_TYPE = "com.onlineimoti.calllog.account"
    const val HISTORY_MIME_TYPE = "vnd.android.cursor.item/vnd.com.onlineimoti.calllog.history"
    private const val ACCOUNT_NAME = "Call Report"

    fun linkContact(context: Context, phone: String, displayName: String) {
        val cleanedPhone = cleanPhone(phone)
        if (cleanedPhone.isBlank()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        if (hasExistingCallReportPhoneRow(context, cleanedPhone)) return

        ensureAccount(context)
        val existingRawContactId = findExistingRawContactId(context, cleanedPhone)
        val operations = arrayListOf<ContentProviderOperation>()

        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, cleanedPhone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )

        val title = displayName.ifBlank { cleanedPhone }
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, title)
                .build()
        )

        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, cleanedPhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, "Call Report")
                .build()
        )

        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, HISTORY_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, cleanedPhone)
                .withValue(ContactsContract.Data.DATA2, "Call Report")
                .withValue(ContactsContract.Data.DATA3, "История")
                .build()
        )

        if (existingRawContactId > 0L) {
            operations.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawContactId)
                    .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, 0)
                    .build()
            )
        }

        runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations) }
    }

    fun phoneFromDataUri(context: Context, uri: Uri?): String {
        if (uri == null) return ""
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.Data.DATA1), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun ensureAccount(context: Context) {
        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
        val manager = AccountManager.get(context)
        if (manager.getAccountsByType(ACCOUNT_TYPE).none { it.name == ACCOUNT_NAME }) {
            runCatching { manager.addAccountExplicitly(account, null, null) }
        }
    }

    private fun findExistingRawContactId(context: Context, phone: String): Long {
        val contactId = context.contentResolver.query(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(),
            arrayOf(ContactsContract.PhoneLookup._ID),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        if (contactId <= 0L) return 0L

        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
    }

    private fun hasExistingCallReportPhoneRow(context: Context, phone: String): Boolean {
        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.NUMBER}=?",
            arrayOf(ACCOUNT_TYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phone),
            null,
        )?.use { it.moveToFirst() } == true
    }

    private fun cleanPhone(value: String): String {
        val keepPlus = value.trimStart().startsWith("+")
        val digits = value.filter { it.isDigit() }
        return if (keepPlus && digits.isNotBlank()) "+$digits" else digits
    }
}
