package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat

internal object RmContactBulkRepairer {
    private const val REPAIR_PAUSE_MS = 25L

    fun repairAll(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        if (!canReadAndWriteContacts(context)) return BulkContactRegistrationResult(0, 0, 0, 0)

        val contacts = RmContactRepairReader.collectRealContacts(context)
        val total = contacts.size
        if (contacts.isEmpty()) {
            onProgress(BulkContactRegistrationProgress(0, 0))
            return BulkContactRegistrationResult(0, 0, 0, 0)
        }

        var repaired = 0
        var skipped = 0
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
        for (contact in contacts) {
            if (shouldCancel()) {
                canceled = true
                break
            }

            val rmRawId = RmContactRepairReader.findRmRawContactId(context, contact.normalizedPhone)
            if (rmRawId <= 0L) {
                skipped += 1
                processed += 1
                report()
                continue
            }

            if (RmContactRepairWriter.repairOne(context, rmRawId, contact)) repaired += 1 else failed += 1
            processed += 1
            report(force = true)
            sleepQuietly(REPAIR_PAUSE_MS)
        }

        report(force = true)
        return BulkContactRegistrationResult(
            scanned = processed,
            created = repaired,
            skippedExisting = skipped,
            failed = failed,
            canceled = canceled,
        )
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}
