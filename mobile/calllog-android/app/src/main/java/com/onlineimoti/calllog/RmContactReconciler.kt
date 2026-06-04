package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal enum class RmContactReconcileAction {
    ADDED,
    UPDATED,
    DELETED,
    UNCHANGED,
    SKIPPED,
    FAILED,
}

internal data class RmContactReconcileResult(
    val action: RmContactReconcileAction,
    val phone: String,
)

internal object RmContactReconciler {
    private const val CONTACT_PAUSE_MS = 12L

    fun reconcileOne(
        context: Context,
        phone: String,
        displayName: String = "",
    ): RmContactReconcileResult {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return RmContactReconcileResult(RmContactReconcileAction.SKIPPED, normalizedPhone)
        if (!canReadAndWriteContacts(appContext)) return RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone)

        return runCatching {
            CrmContactAccountStore.ensureAccount(appContext)
            val rmRawId = CrmContactAccountStore.findCallReportRawContactId(appContext, normalizedPhone)
            val realRawId = CrmContactAccountStore.findExistingRawContactId(appContext, normalizedPhone)

            if (realRawId <= 0L) {
                if (rmRawId > 0L) {
                    val deleted = deleteRmRawContact(appContext, rmRawId)
                    return@runCatching RmContactReconcileResult(
                        action = if (deleted > 0) RmContactReconcileAction.DELETED else RmContactReconcileAction.FAILED,
                        phone = normalizedPhone,
                    )
                }
                return@runCatching RmContactReconcileResult(RmContactReconcileAction.SKIPPED, normalizedPhone)
            }

            val fields = CallReportStableCrmContactWriter.Fields(
                originalPhone = normalizedPhone,
                displayName = displayName.ifBlank { normalizedPhone },
            )
            val saved = CallReportStableCrmContactWriter.save(appContext, fields)
            if (!saved) {
                RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone)
            } else if (rmRawId > 0L) {
                RmContactReconcileResult(RmContactReconcileAction.UPDATED, normalizedPhone)
            } else {
                RmContactReconcileResult(RmContactReconcileAction.ADDED, normalizedPhone)
            }
        }.getOrDefault(RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone))
    }

    fun reconcileAll(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val appContext = context.applicationContext
        if (!canReadAndWriteContacts(appContext)) return BulkContactRegistrationResult(0, 0, 0, 0)

        val contacts = BulkContactCollector.collectUniqueContacts(appContext)
        val total = contacts.size
        if (contacts.isEmpty()) {
            onProgress(BulkContactRegistrationProgress(0, 0))
            return RmContactOrphanCleaner.cleanOrphans(appContext)
        }

        var changed = 0
        var unchanged = 0
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

            when (reconcileOne(appContext, contact.phone, contact.displayName).action) {
                RmContactReconcileAction.ADDED,
                RmContactReconcileAction.UPDATED,
                RmContactReconcileAction.DELETED -> changed += 1
                RmContactReconcileAction.UNCHANGED,
                RmContactReconcileAction.SKIPPED -> unchanged += 1
                RmContactReconcileAction.FAILED -> failed += 1
            }

            processed += 1
            report(force = true)
            sleepQuietly(CONTACT_PAUSE_MS)
        }

        if (!canceled && !shouldCancel()) {
            val cleanup = RmContactOrphanCleaner.cleanOrphans(appContext)
            changed += cleanup.created
            unchanged += cleanup.skippedExisting
            failed += cleanup.failed
        }

        report(force = true)
        return BulkContactRegistrationResult(
            scanned = processed,
            created = changed,
            skippedExisting = unchanged,
            failed = failed,
            canceled = canceled,
        )
    }

    private fun deleteRmRawContact(context: Context, rawContactId: Long): Int {
        return runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?) AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(
                    rawContactId.toString(),
                    CallReportContactIntegration.ACCOUNT_TYPE,
                    CrmContactAccountStore.ACCOUNT_NAME,
                    CrmContactAccountStore.LEGACY_ACCOUNT_NAME,
                ),
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
}
