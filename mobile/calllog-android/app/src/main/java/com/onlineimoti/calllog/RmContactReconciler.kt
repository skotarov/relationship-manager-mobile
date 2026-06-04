package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentProviderOperation
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
    private const val CONTACT_PAUSE_MS = 8L

    fun previewOne(
        context: Context,
        phone: String,
        displayName: String = "",
    ): RmContactReconcileResult {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return RmContactReconcileResult(RmContactReconcileAction.SKIPPED, normalizedPhone)
        if (!canReadContacts(appContext)) return RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone)

        return runCatching {
            previewPreparedOne(
                phone = normalizedPhone,
                real = findRealContact(appContext, normalizedPhone, displayName),
                rm = findRmRecord(appContext, normalizedPhone),
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
        if (!canReadAndWriteContacts(appContext)) return RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone)

        return runCatching {
            CrmContactAccountStore.ensureAccount(appContext)
            reconcilePreparedOne(
                context = appContext,
                phone = normalizedPhone,
                real = findRealContact(appContext, normalizedPhone, displayName),
                rm = findRmRecord(appContext, normalizedPhone),
            )
        }.getOrDefault(RmContactReconcileResult(RmContactReconcileAction.FAILED, normalizedPhone))
    }

    fun canModifyContacts(context: Context): Boolean = canReadAndWriteContacts(context.applicationContext)

    fun reconcileAll(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val appContext = context.applicationContext
        if (!canReadAndWriteContacts(appContext)) return BulkContactRegistrationResult(0, 0, 0, 0)

        CrmContactAccountStore.ensureAccount(appContext)
        val realContactsByPhone = BulkContactCollector.collectUniqueContacts(appContext).associateBy { it.phone }
        val rmRecordsByPhone = collectRmRecords(appContext)
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
                    val created = createRmContact(context, real)
                    RmContactReconcileResult(if (created) RmContactReconcileAction.ADDED else RmContactReconcileAction.FAILED, phone)
                }
                real != null && rm != null -> {
                    if (isRmRecordCurrent(rm, real)) {
                        RmContactReconcileResult(RmContactReconcileAction.UNCHANGED, phone)
                    } else {
                        val updated = updateRmContact(context, real, rm)
                        RmContactReconcileResult(if (updated) RmContactReconcileAction.UPDATED else RmContactReconcileAction.FAILED, phone)
                    }
                }
                real == null && rm != null -> {
                    val deleted = deleteRmRawContact(context, rm.rawContactId)
                    RmContactReconcileResult(if (deleted > 0) RmContactReconcileAction.DELETED else RmContactReconcileAction.FAILED, phone)
                }
                else -> RmContactReconcileResult(RmContactReconcileAction.SKIPPED, phone)
            }
        }.getOrDefault(RmContactReconcileResult(RmContactReconcileAction.FAILED, phone))
    }

    private fun createRmContact(context: Context, real: BulkContactCandidate): Boolean {
        val ops = arrayListOf<ContentProviderOperation>()
        val rawBackRef = 0
        val title = titleFor(real)
        val visiblePhone = real.displayPhone.ifBlank { real.phone }
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withYieldAllowed(true)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, CrmContactAccountStore.ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SYNC1, real.phone)
                .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, title)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, visiblePhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, CrmContactAccountStore.ACCOUNT_NAME)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, CallReportContactIntegration.HISTORY_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, real.phone)
                .withValue(ContactsContract.Data.DATA2, CrmContactAccountStore.ACCOUNT_NAME)
                .withValue(ContactsContract.Data.DATA3, "История")
                .build()
        )
        if (real.existingRawContactId > 0L) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, real.existingRawContactId)
                    .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawBackRef)
                    .build()
            )
        }
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }

    private fun updateRmContact(context: Context, real: BulkContactCandidate, rm: RmRecord): Boolean {
        val ops = arrayListOf<ContentProviderOperation>()
        val title = titleFor(real)
        val visiblePhone = real.displayPhone.ifBlank { real.phone }
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                .withYieldAllowed(true)
                .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rm.rawContactId.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, real.phone)
                .build()
        )
        upsertDataRow(
            ops = ops,
            rawId = rm.rawContactId,
            rowId = rm.nameRowId,
            mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            values = mapOf(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to title),
        )
        upsertDataRow(
            ops = ops,
            rawId = rm.rawContactId,
            rowId = rm.phoneRowId,
            mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            values = mapOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER to visiblePhone,
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to CrmContactAccountStore.ACCOUNT_NAME,
            ),
        )
        upsertDataRow(
            ops = ops,
            rawId = rm.rawContactId,
            rowId = rm.historyRowId,
            mimeType = CallReportContactIntegration.HISTORY_MIME_TYPE,
            values = mapOf(
                ContactsContract.Data.DATA1 to real.phone,
                ContactsContract.Data.DATA2 to CrmContactAccountStore.ACCOUNT_NAME,
                ContactsContract.Data.DATA3 to "История",
            ),
        )
        if (real.existingRawContactId > 0L && real.existingRawContactId != rm.rawContactId) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, real.existingRawContactId)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rm.rawContactId)
                    .build()
            )
        }
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }

    private fun upsertDataRow(
        ops: ArrayList<ContentProviderOperation>,
        rawId: Long,
        rowId: Long,
        mimeType: String,
        values: Map<String, Any>,
    ) {
        val builder = if (rowId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(rowId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, mimeType)
        }
        values.forEach { (key, value) -> builder.withValue(key, value) }
        ops.add(builder.build())
    }

    private fun isRmRecordCurrent(rm: RmRecord, real: BulkContactCandidate): Boolean {
        return rm.syncPhone == real.phone &&
            rm.normalizedPhones.contains(real.phone) &&
            rm.displayName == titleFor(real) &&
            rm.nameRowId > 0L &&
            rm.phoneRowId > 0L &&
            rm.historyRowId > 0L
    }

    private fun findRealContact(context: Context, phone: String, displayName: String): BulkContactCandidate? {
        val existingRawId = CrmContactAccountStore.findExistingRawContactId(context, phone)
        if (existingRawId <= 0L) return null
        return BulkContactCandidate(
            phone = phone,
            displayPhone = phone,
            displayName = displayName.ifBlank { phone },
            existingRawContactId = existingRawId,
        )
    }

    private fun findRmRecord(context: Context, phone: String): RmRecord? {
        return collectRmRecords(context, onlyPhone = phone)[phone]
    }

    private fun collectRmRecords(context: Context, onlyPhone: String = ""): Map<String, RmRecord> {
        val rawRecords = linkedMapOf<Long, MutableRmRecord>()
        val rawSelection = buildString {
            append("${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME} IN (?, ?) AND ${ContactsContract.RawContacts.DELETED}=0")
            if (onlyPhone.isNotBlank()) append(" AND ${ContactsContract.RawContacts.SYNC1}=?")
        }
        val rawArgs = buildList {
            add(CallReportContactIntegration.ACCOUNT_TYPE)
            add(CrmContactAccountStore.ACCOUNT_NAME)
            add(CrmContactAccountStore.LEGACY_ACCOUNT_NAME)
            if (onlyPhone.isNotBlank()) add(onlyPhone)
        }.toTypedArray()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SYNC1),
                rawSelection,
                rawArgs,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rawId = cursor.getLongOrZero(ContactsContract.RawContacts._ID)
                    if (rawId <= 0L) continue
                    val syncPhone = PhoneNormalizer.normalize(cursor.getStringOrEmpty(ContactsContract.RawContacts.SYNC1))
                    rawRecords[rawId] = MutableRmRecord(rawContactId = rawId, syncPhone = syncPhone)
                }
            }
        }
        if (rawRecords.isEmpty()) return emptyMap()

        val rawIds = rawRecords.keys.toList()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.RAW_CONTACT_ID,
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID} IN (${rawIds.joinToString(",") { "?" }}) AND ${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)",
                (rawIds.map { it.toString() } + listOf(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    CallReportContactIntegration.HISTORY_MIME_TYPE,
                )).toTypedArray(),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val dataId = cursor.getLongOrZero(ContactsContract.Data._ID)
                    val rawId = cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                    val record = rawRecords[rawId] ?: continue
                    when (cursor.getStringOrEmpty(ContactsContract.Data.MIMETYPE)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            if (record.phoneRowId <= 0L) record.phoneRowId = dataId
                            PhoneNormalizer.normalize(cursor.getStringOrEmpty(ContactsContract.Data.DATA1))
                                .takeIf { it.isNotBlank() }
                                ?.let { record.normalizedPhones.add(it) }
                        }
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            record.nameRowId = dataId
                            record.displayName = cursor.getStringOrEmpty(ContactsContract.Data.DATA1)
                        }
                        CallReportContactIntegration.HISTORY_MIME_TYPE -> {
                            record.historyRowId = dataId
                            PhoneNormalizer.normalize(cursor.getStringOrEmpty(ContactsContract.Data.DATA1))
                                .takeIf { it.isNotBlank() }
                                ?.let { record.normalizedPhones.add(it) }
                        }
                    }
                }
            }
        }

        return buildMap {
            rawRecords.values.forEach { mutable ->
                if (mutable.syncPhone.isNotBlank()) mutable.normalizedPhones.add(mutable.syncPhone)
                val primaryPhone = mutable.syncPhone.ifBlank { mutable.normalizedPhones.firstOrNull().orEmpty() }
                if (primaryPhone.isNotBlank()) {
                    put(
                        primaryPhone,
                        RmRecord(
                            rawContactId = mutable.rawContactId,
                            syncPhone = mutable.syncPhone,
                            normalizedPhones = mutable.normalizedPhones.toSet(),
                            displayName = mutable.displayName.ifBlank { primaryPhone },
                            nameRowId = mutable.nameRowId,
                            phoneRowId = mutable.phoneRowId,
                            historyRowId = mutable.historyRowId,
                        ),
                    )
                }
            }
        }
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

    private fun titleFor(real: BulkContactCandidate): String {
        return real.displayName.ifBlank { real.displayPhone.ifBlank { real.phone } }
    }

    private fun canReadContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return canReadContacts(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0) getString(index).orEmpty() else ""
    }

    private fun android.database.Cursor.getLongOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0) getLong(index) else 0L
    }

    private data class MutableRmRecord(
        val rawContactId: Long,
        val syncPhone: String,
        val normalizedPhones: MutableSet<String> = linkedSetOf(),
        var displayName: String = "",
        var nameRowId: Long = 0L,
        var phoneRowId: Long = 0L,
        var historyRowId: Long = 0L,
    )

    private data class RmRecord(
        val rawContactId: Long,
        val syncPhone: String,
        val normalizedPhones: Set<String>,
        val displayName: String,
        val nameRowId: Long,
        val phoneRowId: Long,
        val historyRowId: Long,
    )
}
