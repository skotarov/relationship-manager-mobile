package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Public compatibility API for the legacy Call Report contact account. */
object CallReportContactIntegration {
    const val ACCOUNT_TYPE = "com.onlineimoti.calllog.account"
    const val HISTORY_MIME_TYPE = "vnd.android.cursor.item/vnd.com.onlineimoti.calllog.history"

    private const val PREFS = "callreport_contact_integration"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val SYNC_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val syncExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var syncRunning = false

    fun schedulePhonebookContactSync(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!CallReportLegacyContactLookup.hasContactPermissions(appContext) || syncRunning) return

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        if (!force && System.currentTimeMillis() - lastSync < SYNC_INTERVAL_MS) return

        syncRunning = true
        syncExecutor.execute {
            try {
                CallReportPhonebookRegistrar.registerAll(appContext)
                prefs.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply()
            } finally {
                syncRunning = false
            }
        }
    }

    fun removeAllCallReportContacts(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): Int {
        if (!CallReportLegacyContactLookup.hasContactPermissions(context)) return 0

        val rawContactIds = CallReportLegacyContactLookup.findAllRawContactIds(context)
        val total = rawContactIds.size
        var deleted = 0
        var processed = 0
        var lastPercent = -1

        fun report() {
            val progress = BulkContactRegistrationProgress(processed, total)
            if (progress.percent == lastPercent && processed != total) return
            lastPercent = progress.percent
            onProgress(progress)
        }

        report()
        for (rawContactId in rawContactIds) {
            if (shouldCancel()) break
            deleted += CallReportLegacyContactLookup.delete(context, rawContactId)
            processed += 1
            report()
            runCatching { Thread.sleep(15L) }
        }

        if (deleted > 0) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_SYNC_MS).apply()
        }
        return deleted
    }

    fun isContactLinked(context: Context, phone: String): Boolean {
        val cleanedPhone = PhoneNormalizer.normalize(phone)
        if (cleanedPhone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return CallReportLegacyContactLookup.findRawContactId(context, cleanedPhone) > 0L
    }

    fun removeContact(context: Context, phone: String): Int {
        val cleanedPhone = PhoneNormalizer.normalize(phone)
        if (cleanedPhone.isBlank()) return 0
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 0

        val rawContactId = CallReportLegacyContactLookup.findRawContactId(context, cleanedPhone)
        if (rawContactId <= 0L) return 0
        val deleted = CallReportLegacyContactLookup.delete(context, rawContactId)
        return if (CallReportLegacyContactLookup.findRawContactId(context, cleanedPhone) <= 0L) deleted else 0
    }

    fun linkContact(context: Context, phone: String, displayName: String): Boolean {
        return CallReportLegacyContactWriter.link(context, phone, displayName)
    }

    fun linkContactAsAppIfMissing(context: Context, phone: String): Boolean {
        return CallReportLegacyContactWriter.linkAsAppIfMissing(context, phone)
    }

    fun phoneFromDataUri(context: Context, uri: Uri?): String {
        if (uri == null) return ""
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Data.DATA1),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) PhoneNormalizer.normalize(cursor.getString(0).orEmpty()) else ""
            }.orEmpty()
        }.getOrDefault("")
    }
}
