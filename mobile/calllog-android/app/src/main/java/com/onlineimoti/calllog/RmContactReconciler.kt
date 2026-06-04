package com.onlineimoti.calllog

import android.content.Context
import android.os.Process

internal object RmContactReconciler {
    private const val CONTACT_PAUSE_MS = 8L

    fun previewOne(
        context: Context,
        phone: String,
        displayName: String = "",
    ): RmContactReconcileResult {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return RmContactReconcileResult(RmContactReconcileAction.SKIPPED, normalizedPhone)
        if (!RmContactPermissions.canReadContacts(appContext)) return RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone)

        return runCatching {
            previewPreparedOne(
                phone = normalizedPhone,
                real = RmContactReader.findRealContact(appContext, normalizedPhone, displayName),
                rm = RmContactReader.findRmRecord(appContext, normalizedPhone),
            )
        }.getOrDefault(RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone))
    }

    fun reconcileOne(
        context: Context,
        phone: String,
        displayName: String = "",
    ): RmContactReconcileResult {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return RmContactReconcileResult(RmContactReconcileAction.SKIPPED, normalizedPhone)
        if (!RmContactPermissions.canReadAndWriteContacts(appContext)) return RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone)

        return runCatching {
            CrmContactAccountStore.ensureAccount(appContext)
            reconcilePreparedOne(
                context = appContext,
                phone = normalizedPhone,
                real = RmContactReader.findRealContact(appContext, normalizedPhone, displayName),
                rm = RmContactReader.findRmRecord(appContext, normalizedPhone),
            )
        }.getOrDefault(RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone))
    }

    fun canModifyContacts(context: Context): Boolean {
        return RmContactPermissions.canReadAndWriteContacts(context.applicationContext)
    }

    fun reconcileAll(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val appContext = context.applicationContext
        if (!RmContactPermissions.canReadAndWriteContacts(appContext)) return BulkContactRegistrationResult(0, 0, 0, 0)

        CrmContactAccountStore.ensureAccount(appContext)
        val realContactsByPhone = BulkContactCollector.collectUniqueContacts(appContext).associateBy { it.phone }
        val rmRecordsByPhone = RmContactReader.collectRmRecords(appContext)
        val phones = (realContactsByPhone.keys + rmRecordsByPhone.keys).filter { it.isNotBlank() }.toSortedSet().toList()
        val total = phones.size
        if (total == 0) {
            onProgress(BulkContactRegistrationProgress(0, 0))
            return BulkContactRegistrationResult(0, 0, 0, 0)
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
        for (phone in phones) {
            if (shouldCancel()) {
                canceled = true
                break
            }

            when (reconcilePreparedOne(appContext, phone, realContactsByPhone[phone], rmRecordsByPhone[phone]).action) {
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

        report(force = true)
        return BulkContactRegistrationResult(
            scanned = processed,
            created = changed,
            skippedExisting = unchanged,
            failed = failed,
            canceled = canceled,
        )
    }

    private fun previewPreparedOne(
        phone: String,
        real: BulkContactCandidate?,
        rm: RmRecord?,
    ): RmContactReconcileResult {
        return when {
            real != null && rm == null -> RmContactReconcileResult(RmContactReconcileAction.ADDED, phone)
            real != null && rm != null -> {
                if (isRmRecordCurrent(rm, real)) {
                    RmContactReconcileResult(RmContactReconcileAction.UNCHANGED, phone)
                } else {
                    RmContactReconcileResult(RmContactReconcileAction.UPDATED, phone)
                }
            }
            real == null && rm != null -> RmContactReconcileResult(RmContactReconcileAction.DELETED, phone)
            else -> RmContactReconcileResult(RmContactReconcileAction.SKIPPED, phone)
        }
    }

    private fun reconcilePreparedOne(
        context: Context,
        phone: String,
        real: BulkContactCandidate?,
        rm: RmRecord?,
    ): RmContactReconcileResult {
        return runCatching {
            when {
                real != null && rm == null -> {
                    val created = RmContactWriter.create(context, real)
                    RmContactReconcileResult(if (created) RmContactReconcileAction.ADDED else RmContactReconcileAction.FAILED, phone)
                }
                real != null && rm != null -> {
                    if (isRmRecordCurrent(rm, real)) {
                        RmContactReconcileResult(RmContactReconcileAction.UNCHANGED, phone)
                    } else {
                        val updated = RmContactWriter.update(context, real, rm)
                        RmContactReconcileResult(if (updated) RmContactReconcileAction.UPDATED else RmContactReconcileAction.FAILED, phone)
                    }
                }
                real == null && rm != null -> {
                    val deleted = RmContactWriter.delete(context, rm.rawContactId)
                    RmContactReconcileResult(if (deleted > 0) RmContactReconcileAction.DELETED else RmContactReconcileAction.FAILED, phone)
                }
                else -> RmContactReconcileResult(RmContactReconcileAction.SKIPPED, phone)
            }
        }.getOrDefault(RmContactReconcileResult(RmContactReconcileAction.FAILED, phone))
    }

    private fun isRmRecordCurrent(rm: RmRecord, real: BulkContactCandidate): Boolean {
        return rm.syncPhone == real.phone &&
            rm.normalizedPhones.contains(real.phone) &&
            rm.displayName == RmContactNameResolver.titleFor(real) &&
            rm.nameRowId > 0L &&
            rm.phoneRowId > 0L &&
            rm.historyRowId > 0L
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}
