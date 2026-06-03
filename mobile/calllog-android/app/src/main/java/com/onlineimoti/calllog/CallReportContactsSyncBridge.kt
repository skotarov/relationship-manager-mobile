package com.onlineimoti.calllog

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object CallReportContactsSyncBridge {
    private const val PREFS = "callreport_contacts_sync_bridge"
    private const val KEY_LAST_SYNC_REQUEST_MS = "last_sync_request_ms"
    private const val SYNC_REQUEST_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun ensureAccountAndRequestSync(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        CrmContactAccountStore.ensureAccount(appContext)
        if (!canReadAndWriteContacts(appContext)) return

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastRequest = prefs.getLong(KEY_LAST_SYNC_REQUEST_MS, 0L)
        if (!force && now - lastRequest < SYNC_REQUEST_INTERVAL_MS) return

        val account = Account(CrmContactAccountStore.ACCOUNT_NAME, CallReportContactIntegration.ACCOUNT_TYPE)
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, force)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, force)
        }

        runCatching {
            ContentResolver.requestSync(account, ContactsContract.AUTHORITY, extras)
            prefs.edit().putLong(KEY_LAST_SYNC_REQUEST_MS, now).apply()
        }
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
