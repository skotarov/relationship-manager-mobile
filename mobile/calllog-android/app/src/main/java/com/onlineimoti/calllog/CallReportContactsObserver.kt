package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object CallReportContactsObserver {
    private const val DEBOUNCE_MS = 7_500L
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var registered = false
    private var observer: ContentObserver? = null

    private val syncRunnable = Runnable {
        appContext?.let { CallReportContactIntegration.schedulePhonebookContactSync(it, force = true) }
    }

    fun start(context: Context) {
        val safeContext = context.applicationContext
        if (!hasContactsAccess(safeContext)) return
        appContext = safeContext
        if (registered) return

        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scheduleSync()
            }
        }
        safeContext.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer!!)
        safeContext.contentResolver.registerContentObserver(ContactsContract.RawContacts.CONTENT_URI, true, observer!!)
        safeContext.contentResolver.registerContentObserver(ContactsContract.Data.CONTENT_URI, true, observer!!)
        registered = true
    }

    private fun scheduleSync() {
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, DEBOUNCE_MS)
    }

    private fun hasContactsAccess(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
