package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal data class BulkContactRegistrationResult(
    val scanned: Int,
    val created: Int,
    val skippedExisting: Int,
    val failed: Int,
    val canceled: Boolean = false,
)

data class BulkContactRegistrationProgress(
    val processed: Int,
    val total: Int,
) {
    val percent: Int = if (total <= 0) 100 else ((processed * 100f) / total).toInt().coerceIn(0, 100)
}

internal object CallReportBulkContactRegistrar {
    private const val MAX_CONTACTS_PER_RUN = 2500
    private const val APP_LINK_BATCH_SIZE = 25
    private const val APP_LINK_BATCH_PAUSE_MS = 80L
    private const val FALLBACK_CONTACT_PAUSE_MS = 15L

    fun registerPhoneOnlyLinks(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        if (!canReadAndWriteContacts(context)) return BulkContactRegistrationResult(0, 0, 0, 0)
        val mode = ConfigStore.load(context).contactLinkMode
        val contacts = collectUniqueContacts(context)
        val existingPhoneRawIds = findCallReportPhoneRawContactIds(context)
        return if (mode == ConfigStore.CONTACT_LINK_MODE_CONTACT) {
            registerOneByOneLikeModal(
                context = context,
                contacts = contacts,
                existingPhones = existingPhoneRawIds.keys,
                mode = mode,
                onProgress = onProgress,
                shouldCancel = shouldCancel,
            )
        } else {
            registerAppLinksBatched(
                context = context,
                contacts = contacts,
                existingPhones = existingPhoneRawIds.keys,
                onProgress = onProgress,
                shouldCancel = shouldCancel,
            )
        }
    }

    private fun registerAppLinksBatched(
        context: Context,
        contacts: List<BulkContactCandidate>,
        existingPhones: Set<String>,
        onProgress: (BulkContactRegistrationProgress) -> Unit,
        shouldCancel: () -> Boolean,
    ): BulkContactRegistrationResult {
        val total = contacts.size
        if (contacts.isEmpty()) {
            onProgress(BulkContactRegistrationProgress(0, 0))
            return BulkContactRegistrationResult(0, 0, 0, 0)
        }

        CrmContactAccountStore.ensureAccount(context)

        var created = 0
        var skippedExisting = 0
        var failed = 0
        var processed = 0
        var canceled = false
        var lastPercent = -1
        val pending = arrayListOf<BulkContactCandidate>()

        fun report(force: Boolean = false) {
            val progress = BulkContactRegistrationProgress(processed, total)
            if (!force && progress.percent == lastPercent && processed != total) return
            lastPercent = progress.percent
            onProgress(progress)
        }

        fun flushPending() {
            if (pending.isEmpty()) return
            val batch = pending.toList()
            pending.clear()
            val result = applyAppLinkCreateBatch(context, batch)
            created += result.created
            failed += result.failed
            processed += batch.size
            report(force = true)
            sleepQuietly(APP_LINK_BATCH_PAUSE_MS)
        }

        report(force = true)
        for (contact in contacts) {
            if (shouldCancel()) {
                canceled = true
                break
            }
            if (existingPhones.contains(contact.phone)) {
                skippedExisting += 1
                processed += 1
                report()
                if (processed % APP_LINK_BATCH_SIZE == 0) sleepQuietly(APP_LINK_BATCH_PAUSE_MS)
                continue
            }

            pending.add(contact)
            if (pending.size >= APP_LINK_BATCH_SIZE) {
                flushPending()
            }
        }

        if (!canceled) flushPending()
        report(force = true)

        return BulkContactRegistrationResult(
            scanned = processed,
            created = created,
            skippedExisting = skippedExisting,
            failed = failed,
            canceled = canceled,
        )
    }

    private fun applyAppLinkCreateBatch(context: Context, contacts: List<BulkContactCandidate>): BatchCreateResult {
        if (contacts.isEmpty()) return BatchCreateResult(0, 0)
        val batchOps = arrayListOf<ContentProviderOperation>()
        contacts.forEach { contact -> addAppLinkCreateOperations(batchOps, contact, allowYield = batchOps.isEmpty()) }
        if (runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, batchOps) }.isSuccess) {
            return BatchCreateResult(created = contacts.size, failed = 0)
        }

        var created = 0
        var failed = 0
        contacts.forEach { contact ->
            val ops = arrayListOf<ContentProviderOperation>()
            addAppLinkCreateOperations(ops, contact, allowYield = true)
            if (runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess) {
                created += 1
            } else {
                failed += 1
            }
            sleepQuietly(FALLBACK_CONTACT_PAUSE_MS)
        }
        return BatchCreateResult(created = created, failed = failed)
    }

    private fun addAppLinkCreateOperations(
        ops: ArrayList<ContentProviderOperation>,
        contact: BulkContactCandidate,
        allowYield: Boolean,
    ) {
        val rawContactBackRef = ops.size
        val title = contact.displayName.ifBlank { contact.displayPhone.ifBlank { contact.phone } }
        val visiblePhone = contact.displayPhone.ifBlank { contact.phone }
        val rawInsert = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, CallReportContactIntegration.ACCOUNT_TYPE)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, CrmContactAccountStore.ACCOUNT_NAME)
            .withValue(ContactsContract.RawContacts.SYNC1, contact.phone)
            .withValue(ContactsContract.RawContacts.AGGREGATION_MODE, ContactsContract.RawContacts.AGGREGATION_MODE_DEFAULT)
        if (allowYield) rawInsert.withYieldAllowed(true)
        ops.add(rawInsert.build())

        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, title)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, visiblePhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, CrmContactAccountStore.ACCOUNT_NAME)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactBackRef)
                .withValue(ContactsContract.Data.MIMETYPE, CallReportContactIntegration.HISTORY_MIME_TYPE)
                .withValue(ContactsContract.Data.DATA1, contact.phone)
                .withValue(ContactsContract.Data.DATA2, CrmContactAccountStore.ACCOUNT_NAME)
                .withValue(ContactsContract.Data.DATA3, "История")
                .build()
        )
        if (contact.existingRawContactId > 0L) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, contact.existingRawContactId)
                    .withValueBackReference(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rawContactBackRef)
                    .build()
            )
        }
    }

    private fun registerOneByOneLikeModal(
        context: Context,
        contacts: List<BulkContactCandidate>,
        existingPhones: Set<String>,
        mode: String,
        onProgress: (BulkContactRegistrationProgress) -> Unit,
        shouldCancel: () -> Boolean,
    ): BulkContactRegistrationResult {
        val total = contacts.size
        if (contacts.isEmpty()) {
            onProgress(BulkContactRegistrationProgress(0, 0))
            return BulkContactRegistrationResult(0, 0, 0, 0)
        }

        var created = 0
        var updatedExisting = 0
        var failed = 0
        var processed = 0
        var canceled = false
        var lastPercent = -1

        fun report() {
            val progress = BulkContactRegistrationProgress(processed, total)
            if (progress.percent == lastPercent && processed != total) return
            lastPercent = progress.percent
            onProgress(progress)
        }

        report()
        for (contact in contacts) {
            if (shouldCancel()) {
                canceled = true
                break
            }

            val title = contact.displayName.ifBlank { contact.displayPhone.ifBlank { contact.phone } }
            val fields = CallReportStableCrmContactWriter.Fields(
                originalPhone = contact.phone,
                displayName = title,
            )
            val saved = CrmContactLinkSaver.save(
                context = context,
                fields = fields,
                mode = mode,
                phone = contact.phone,
                title = title,
            )

            if (saved) {
                if (existingPhones.contains(contact.phone)) updatedExisting += 1 else created += 1
            } else {
                failed += 1
            }

            processed += 1
            report()
            sleepQuietly(25L)
        }

        return BulkContactRegistrationResult(
            scanned = processed,
            created = created,
            skippedExisting = updatedExisting,
            failed = failed,
            canceled = canceled,
        )
    }

    private fun collectUniqueContacts(context: Context): List<BulkContactCandidate> {
        val callReportRawContactIds = findCallReportRawContactIds(context)
        val contactsByPhone = linkedMapOf<String, BulkContactCandidate>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.Data.RAW_CONTACT_ID,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val rawContactIndex = cursor.getColumnIndex(ContactsContract.Data.RAW_CONTACT_ID)
            if (numberIndex < 0) return@use
            var scanned = 0
            while (cursor.moveToNext() && contactsByPhone.size < MAX_CONTACTS_PER_RUN) {
                val rawContactId = if (rawContactIndex >= 0) cursor.getLong(rawContactIndex) else 0L
                if (rawContactId > 0L && callReportRawContactIds.contains(rawContactId)) continue
                val originalPhone = cursor.getString(numberIndex).orEmpty()
                val phone = PhoneNormalizer.normalize(originalPhone)
                if (phone.isBlank() || contactsByPhone.containsKey(phone)) continue
                val fallbackDisplayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                val displayName = exactStructuredDisplayName(context, rawContactId, fallbackDisplayName)
                contactsByPhone[phone] = BulkContactCandidate(
                    phone = phone,
                    displayPhone = originalPhone,
                    displayName = displayName,
                    existingRawContactId = rawContactId,
                )
                scanned += 1
                if (scanned % 250 == 0) Thread.yield()
            }
        }
        return contactsByPhone.values.toList()
    }

    private fun exactStructuredDisplayName(context: Context, rawContactId: Long, fallback: String): String {
        if (rawContactId <= 0L) return fallback
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use fallback
                val firstSecondThird = listOf(
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME),
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME),
                    cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME),
                ).filter { it.isNotBlank() }.joinToString(" ")
                if (firstSecondThird.isNotBlank()) return@use firstSecondThird
                cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME).ifBlank { fallback }
            }.orEmpty().ifBlank { fallback }
        }.getOrDefault(fallback)
    }

    private fun android.database.Cursor.getStringOrEmpty(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0) getString(index).orEmpty() else ""
    }

    private fun findCallReportRawContactIds(context: Context): Set<Long> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, CrmContactAccountStore.ACCOUNT_NAME),
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }.orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun findCallReportPhoneRawContactIds(context: Context): Map<String, Long> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.SYNC1, ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, CrmContactAccountStore.ACCOUNT_NAME),
                null,
            )?.use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val phone = PhoneNormalizer.normalize(cursor.getString(0).orEmpty())
                        if (phone.isNotBlank()) put(phone, cursor.getLong(1))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private data class BulkContactCandidate(
        val phone: String,
        val displayPhone: String,
        val displayName: String,
        val existingRawContactId: Long,
    )

    private data class BatchCreateResult(
        val created: Int,
        val failed: Int,
    )
}
