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

    fun removeAllCallReportContacts(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 0
        val deleted = runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=?",
                arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME),
            )
        }.getOrDefault(0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_SYNC_MS).apply()
        return deleted
    }

    fun isContactLinked(context: Context, phone: String): Boolean {
        val cleanedPhone = cleanPhone(phone)
        if (cleanedPhone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return findCallReportRawContactId(context, cleanedPhone) > 0L
    }

    fun removeContact(context: Context, phone: String): Int {
        val cleanedPhone = cleanPhone(phone)
        if (cleanedPhone.isBlank()) return 0
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 0
        val rawContactId = findCallReportRawContactId(context, cleanedPhone)
        if (rawContactId <= 0L) return 0
        return runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=?",
                arrayOf(rawContactId.toString(), ACCOUNT_TYPE, ACCOUNT_NAME),
            )
        }.getOrDefault(0)
    }

    fun linkContact(context: Context, phone: String, displayName: String) {
        val cleanedPhone = cleanPhone(phone)
        if (cleanedPhone.isBlank()) return
        if (!canReadAndWriteContacts(context)) return

        ensureAccount(context)
        val title = displayName.ifBlank { cleanedPhone }
        val callReportRawContactId = findCallReportRawContactId(context, cleanedPhone)
        val existingRawContactId = findExistingRawContactId(context, cleanedPhone)
        val operations = arrayListOf<ContentProviderOperation>()

        if (callReportRawContactId > 0L) {
            addUpdateExistingCallReportRawContactOperations(operations, callReportRawContactId, cleanedPhone, title, context)
            if (existingRawContactId > 0L && existingRawContactId != callReportRawContactId) {
                operations.add(
                    ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                        .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, existingRawContactId)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, callReportRawContactId)
                        .build()
                )
            }
        } else {
            addCreateCallReportRawContactOperations(operations, cleanedPhone, title, existingRawContactId)
        }

        if (operations.isNotEmpty()) {
            runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations) }
        }
    }

    fun phoneFromDataUri(context: Context, uri: Uri?): String {
        if (uri == null) return ""
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.Data.DATA1), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun addCreateCallReportRawContactOperations(
        operations: ArrayList<ContentProviderOperation>,
        phone: String,
        title: String,
        existingRawContactId: Long,
    ) {
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, phone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )
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
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, "Call Report")
                .build()
        )
        operations.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, HISTORY_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, phone)
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
    }

    private fun addUpdateExistingCallReportRawContactOperations(
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        phone: String,
        title: String,
        context: Context,
    ) {
        operations.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rawContactId.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, phone)
                .build()
        )
        upsertDataRow(
            context = context,
            operations = operations,
            rawContactId = rawContactId,
            mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            insertValues = mapOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to title),
        )
        upsertDataRow(
            context = context,
            operations = operations,
            rawContactId = rawContactId,
            mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            insertValues = mapOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER to phone,
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to "Call Report",
            ),
        )
        upsertDataRow(
            context = context,
            operations = operations,
            rawContactId = rawContactId,
            mimeType = HISTORY_MIME_TYPE,
            insertValues = mapOf(
                ContactsContract.Data.DATA1 to phone,
                ContactsContract.Data.DATA2 to "Call Report",
                ContactsContract.Data.DATA3 to "История",
            ),
        )
    }

    private fun upsertDataRow(
        context: Context,
        operations: ArrayList<ContentProviderOperation>,
        rawContactId: Long,
        mimeType: String,
        insertValues: Map<String, Any>,
    ) {
        val dataId = findDataRowId(context, rawContactId, mimeType)
        val builder = if (dataId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(dataId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        }
        insertValues.forEach { (key, value) -> builder.withValue(key, value) }
        operations.add(builder.build())
    }

    private fun findDataRowId(context: Context, rawContactId: Long, mimeType: String): Long {
        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(rawContactId.toString(), mimeType),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
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

    private fun findCallReportRawContactId(context: Context, phone: String): Long {
        val bySync = context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.SYNC1}=?",
            arrayOf(ACCOUNT_TYPE, ACCOUNT_NAME, phone),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
        if (bySync > 0L) return bySync

        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.RAW_CONTACT_ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Phone.NUMBER}=?",
            arrayOf(ACCOUNT_TYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, phone),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
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
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}!=?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ACCOUNT_TYPE),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
    }

    private fun cleanPhone(value: String): String {
        val keepPlus = value.trimStart().startsWith("+")
        val digits = value.filter { it.isDigit() }
        return if (keepPlus && digits.isNotBlank()) "+$digits" else digits
    }
}
