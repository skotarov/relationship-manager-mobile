package com.onlineimoti.calllog

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.provider.ContactsContract

internal object RmContactReconciler {
    private const val CONTACT_PAUSE_MS = 4L
    private const val PROGRESS_UPDATE_INTERVAL_MS = 350L

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
        var lastProgressAt = 0L

        fun report(force: Boolean = false) {
            val progress = BulkContactRegistrationProgress(processed, total)
            val now = SystemClock.elapsedRealtime()
            val percentChanged = progress.percent != lastPercent
            val intervalPassed = now - lastProgressAt >= PROGRESS_UPDATE_INTERVAL_MS
            if (!force && !percentChanged && !intervalPassed && processed != total) return
            lastPercent = progress.percent
            lastProgressAt = now
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
            report()
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
                if (isRmRecordCurrentWithoutNote(rm, real)) {
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
                    val created = saveWithStablePath(context, real)
                    RmContactReconcileResult(if (created) RmContactReconcileAction.ADDED else RmContactReconcileAction.FAILED, phone)
                }
                real != null && rm != null -> {
                    if (isRmRecordCurrent(context, rm, real)) {
                        val markersOk = RmContactSyncLayerStore.applyCloudSyncLabelsIfEnabled(context, real.phone)
                        RmContactReconcileResult(if (markersOk) RmContactReconcileAction.UNCHANGED else RmContactReconcileAction.FAILED, phone)
                    } else {
                        val updated = saveWithStablePath(context, real)
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

    private fun saveWithStablePath(context: Context, real: BulkContactCandidate): Boolean {
        val title = RmContactNameResolver.titleFor(real)
        val parts = RmContactNameResolver.structuredParts(title)
        val saved = CrmContactLinkSaver.save(
            context = context,
            fields = CallReportStableCrmContactWriter.Fields(
                originalPhone = real.phone,
                displayName = title,
                organization = "Relationship Management",
                jobTitle = "RM auto",
                groupName = RmContactSyncLayerStore.groupNameForCurrentRules(context, real.phone),
                customText = "RM auto link",
                note = RmLayerNoteSyncer.formatForCurrentRules(
                    context,
                    real.phone,
                    ContactNoteReader.generalNoteForPhone(context, real.phone),
                ),
                givenName = parts.givenName,
                middleName = parts.middleName,
                familyName = parts.familyName,
            ),
            mode = ConfigStore.load(context).contactLinkMode,
            phone = real.phone,
            title = title,
        )
        if (!saved) return false
        return RmContactSyncLayerStore.applyCloudSyncLabelsIfEnabled(context, real.phone)
    }

    private fun isRmRecordCurrent(context: Context, rm: RmRecord, real: BulkContactCandidate): Boolean {
        return isRmRecordCurrentWithoutNote(rm, real) && isRmNoteCurrent(context, rm.rawContactId, real.phone)
    }

    private fun isRmRecordCurrentWithoutNote(rm: RmRecord, real: BulkContactCandidate): Boolean {
        val title = RmContactNameResolver.titleFor(real)
        val sameName = namesEqual(rm.displayName, title)
        val sameParts = if (sameName) true else {
            val parts = RmContactNameResolver.structuredParts(title)
            rm.givenName == parts.givenName &&
                rm.middleName == parts.middleName &&
                rm.familyName == parts.familyName
        }
        return rm.syncPhone == real.phone &&
            rm.normalizedPhones.contains(real.phone) &&
            sameName &&
            sameParts &&
            rm.nameRowId > 0L &&
            rm.phoneRowId > 0L &&
            rm.historyRowId > 0L
    }

    private fun isRmNoteCurrent(context: Context, rawId: Long, phone: String): Boolean {
        val expected = RmLayerNoteSyncer.formatForCurrentRules(
            context,
            phone,
            ContactNoteReader.generalNoteForPhone(context, phone),
        ).trim()
        val current = runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Note.NOTE),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" } ?: ""
        }.getOrDefault("").trim()
        return current == expected
    }

    private fun namesEqual(left: String, right: String): Boolean {
        return left.trim().replace(Regex("\\s+"), " ") == right.trim().replace(Regex("\\s+"), " ")
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(CONTACT_PAUSE_MS) }
    }
}
