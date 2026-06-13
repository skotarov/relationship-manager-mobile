package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

internal object RmContactAutoSyncGate {
    private const val PREFS = "rm_contact_auto_sync_gate"
    private const val KEY_LAST_FULL_SYNC_MS = "last_full_sync_ms"
    private const val KEY_LAST_SYNCED_CONTACTS_SIGNATURE = "last_synced_contacts_signature"
    private const val KEY_LAST_SIGNATURE_CHECK_MS = "last_signature_check_ms"
    private const val KEY_LAST_SEEN_CONTACTS_SIGNATURE = "last_seen_contacts_signature"

    private const val MIN_FULL_AUTO_SYNC_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MIN_SIGNATURE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    fun shouldRunAutomaticSync(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!RmContactPermissions.canReadAndWriteContacts(appContext)) return false

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSyncedSignature = prefs.getString(KEY_LAST_SYNCED_CONTACTS_SIGNATURE, null)
        val lastSignatureCheck = prefs.getLong(KEY_LAST_SIGNATURE_CHECK_MS, 0L)

        if (lastSyncedSignature != null && now - lastSignatureCheck < MIN_SIGNATURE_CHECK_INTERVAL_MS) {
            return false
        }

        val currentSignature = currentContactsSignature(appContext)
        if (currentSignature.isBlank()) return false

        prefs.edit()
            .putLong(KEY_LAST_SIGNATURE_CHECK_MS, now)
            .putString(KEY_LAST_SEEN_CONTACTS_SIGNATURE, currentSignature)
            .apply()

        if (lastSyncedSignature == null) return true
        if (currentSignature == lastSyncedSignature) return false

        val lastFullSync = prefs.getLong(KEY_LAST_FULL_SYNC_MS, 0L)
        return now - lastFullSync >= MIN_FULL_AUTO_SYNC_INTERVAL_MS
    }

    fun markFullSyncFinished(context: Context) {
        val appContext = context.applicationContext
        if (!RmContactPermissions.canReadContacts(appContext)) return

        val currentSignature = currentContactsSignature(appContext)
        if (currentSignature.isBlank()) return

        val now = System.currentTimeMillis()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_FULL_SYNC_MS, now)
            .putLong(KEY_LAST_SIGNATURE_CHECK_MS, now)
            .putString(KEY_LAST_SYNCED_CONTACTS_SIGNATURE, currentSignature)
            .putString(KEY_LAST_SEEN_CONTACTS_SIGNATURE, currentSignature)
            .apply()
    }

    private fun currentContactsSignature(context: Context): String {
        val phoneRows = queryPhoneRowCount(context)
        val latestContactUpdate = queryLatestContactUpdate(context)
        val latestRawContactId = queryLatestRawContactId(context)

        if (phoneRows < 0 && latestContactUpdate < 0L && latestRawContactId < 0L) return ""
        return "phones=${phoneRows.coerceAtLeast(0)};updated=${latestContactUpdate.coerceAtLeast(0L)};raw=${latestRawContactId.coerceAtLeast(0L)}"
    }

    private fun queryPhoneRowCount(context: Context): Int {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone._ID),
                null,
                null,
                null,
            )?.use { cursor -> cursor.count } ?: -1
        }.getOrDefault(-1)
    }

    private fun queryLatestContactUpdate(context: Context): Long {
        return runCatching {
            val uri = ContactsContract.Contacts.CONTENT_URI.buildUpon()
                .appendQueryParameter("limit", "1")
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                null,
                null,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP + " DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val index = cursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                if (index >= 0) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun queryLatestRawContactId(context: Context): Long {
        return runCatching {
            val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter("limit", "1")
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.RawContacts._ID),
                null,
                null,
                ContactsContract.RawContacts._ID + " DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val index = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                if (index >= 0) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
