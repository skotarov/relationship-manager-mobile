package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object RmContactOrphanCleaner {
    private const val CLEANUP_PAUSE_MS = 25L

    fun cleanOrphans(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        if (!canReadAndWriteContacts(context)) return BulkContactRegistrationResult(0, 0, 0, 0)

        val rmRecords = findRmRecords(context)
        val total = rmRecords.size
        if (rmRecords.isEmpty()) {
            onProgress(BulkContactRegistrationProgress(0, 0))
            return BulkContactRegistrationResult(0, 0, 0, 0)
        }

        val realPhones = findRealContactPhones(context)
        var deleted = 0
        var kept = 0
        var failed = 0
        var processed = 0
        var canceled = false
        var lastPercent = -1

        fun report(force: Boolean = false) {
            val progress = BulkContactRegistrationProgress(processed, total)
            if (!force && progress.percent == lastPercent && processed != total) return
            lastPercent = progress.percent
            onProgress(progress)
        }

        report(force = true)
        for (record in rmRecords) {
            if (shouldCancel()) {
                canceled = true
                break
            }

            val hasRealContact = record.normalizedPhones.any { realPhones.contains(it) }
            if (hasRealContact) {
                kept += 1
            } else {
                val removed = deleteRmRawContact(context, record.rawContactId)
                if (removed > 0) deleted += 1 else failed += 1
            }

            processed += 1
            report(force = true)
            sleepQuietly(CLEANUP_PAUSE_MS)
        }

        report(force = true)
        return BulkContactRegistrationResult(
            scanned = processed,
            created = deleted,
            skippedExisting = kept,
            failed = failed,
            canceled = canceled,
        )
    }

    private fun findRmRecords(context: Context): List<RmRecord> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, CrmContactAccountStore.ACCOUNT_NAME),
                ContactsContract.RawContacts._ID + " ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val rawId = cursor.getLong(0)
                        val phones = linkedSetOf<String>()
                        PhoneNormalizer.normalize(cursor.getString(1).orEmpty()).takeIf { it.isNotBlank() }?.let { phones.add(it) }
                        phones.addAll(findRmVisiblePhones(context, rawId))
                        add(RmRecord(rawContactId = rawId, normalizedPhones = phones.toSet()))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun findRmVisiblePhones(context: Context, rawId: Long): Set<String> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        PhoneNormalizer.normalize(cursor.getString(0).orEmpty()).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun findRealContactPhones(context: Context): Set<String> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}!=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        PhoneNormalizer.normalize(cursor.getString(0).orEmpty()).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun deleteRmRawContact(context: Context, rawContactId: Long): Int {
        return runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(rawContactId.toString(), CallReportContactIntegration.ACCOUNT_TYPE, CrmContactAccountStore.ACCOUNT_NAME),
            )
        }.getOrDefault(0)
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private data class RmRecord(
        val rawContactId: Long,
        val normalizedPhones: Set<String>,
    )
}
