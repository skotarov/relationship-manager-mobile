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
import java.util.concurrent.Executors

object CallReportContactIntegration {
    const val ACCOUNT_TYPE = "com.onlineimoti.calllog.account"
    const val HISTORY_MIME_TYPE = "vnd.android.cursor.item/vnd.com.onlineimoti.calllog.history"
    private const val ACCOUNT_NAME = "Call Report"
    private const val PREFS = "callreport_contact_integration"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val SYNC_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val MAX_CONTACTS_PER_SYNC = 2500
    private val syncExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var syncRunning = false

    fun schedulePhonebookContactSync(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!canReadAndWriteContacts(appContext)) return
        if (syncRunning) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        if (!force && System.currentTimeMillis() - lastSync < SYNC_INTERVAL_MS) return

        syncRunning = true
        syncExecutor.execute {
            try {
                linkPhonebookContacts(appContext)
                prefs.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply()
            } finally {
                syncRunning = false
            }
        }
    }

    fun linkContact(context: Context, phone: String, displayName: String) {
        val cleanedPhone = cleanPhone(phone)
        if (cleanedPhone.isBlank()) return
        if (!canReadAndWriteContacts(context)) return
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

    private fun linkPhonebookContacts(context: Context) {
        if (!canReadAndWriteContacts(context)) return
        ensureAccount(context)
        val seen = linkedSetOf<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (cursor.moveToNext() && seen.size < MAX_CONTACTS_PER_SYNC) {
                val phone = cleanPhone(cursor.getString(numberIndex).orEmpty())
                if (phone.isBlank() || !seen.add(phone)) continue
                val name = cursor.getString(nameIndex).orEmpty()
                linkContact(context, phone, name)
            }
        }
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
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
