package com.onlineimoti.calllog

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal object RmContactBulkRepairer {
    private const val MAX_CONTACTS_PER_RUN = 2500
    private const val REPAIR_PAUSE_MS = 25L

    fun repairAll(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        if (!canReadAndWriteContacts(context)) return BulkContactRegistrationResult(0, 0, 0, 0)

        val contacts = collectRealContacts(context)
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
            val rmRawId = findRmRawContactId(context, contact.normalizedPhone)
            if (rmRawId <= 0L) {
                skipped += 1
                processed += 1
                report()
                continue
            }
            if (repairOne(context, rmRawId, contact)) repaired += 1 else failed += 1
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

    private fun repairOne(context: Context, rmRawId: Long, real: RealContactCandidate): Boolean {
        return runCatching {
            val ops = arrayListOf<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                    .withSelection("${ContactsContract.RawContacts._ID}=?", arrayOf(rmRawId.toString()))
                    .withValue(ContactsContract.RawContacts.SYNC1, real.normalizedPhone)
                    .build()
            )
            upsertStructuredName(context, ops, rmRawId, real.name)
            upsertVisiblePhone(context, ops, rmRawId, real.visiblePhone.ifBlank { real.normalizedPhone })
            upsertHistoryRow(context, ops, rmRawId, real.normalizedPhone)
            if (real.rawContactId > 0L && real.rawContactId != rmRawId) {
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                        .withValue(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, real.rawContactId)
                        .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, rmRawId)
                        .build()
                )
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        }.getOrDefault(false)
    }

    private fun upsertStructuredName(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, name: StructuredContactName) {
        val rowId = findDataRowId(context, rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        val builder = if (rowId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(rowId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        }
        ops.add(
            builder
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.displayName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, name.givenName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, name.middleName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, name.familyName)
                .build()
        )
    }

    private fun upsertVisiblePhone(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, visiblePhone: String) {
        val rowId = findPrimaryRmPhoneRowId(context, rawId)
        val builder = if (rowId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(rowId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
        }
        ops.add(
            builder
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, visiblePhone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, CrmContactAccountStore.ACCOUNT_NAME)
                .build()
        )
    }

    private fun upsertHistoryRow(context: Context, ops: ArrayList<ContentProviderOperation>, rawId: Long, normalizedPhone: String) {
        val rowId = findDataRowId(context, rawId, CallReportContactIntegration.HISTORY_MIME_TYPE)
        val builder = if (rowId > 0L) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID}=?", arrayOf(rowId.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, CallReportContactIntegration.HISTORY_MIME_TYPE)
        }
        ops.add(
            builder
                .withValue(ContactsContract.Data.DATA1, normalizedPhone)
                .withValue(ContactsContract.Data.DATA2, CrmContactAccountStore.ACCOUNT_NAME)
                .withValue(ContactsContract.Data.DATA3, "История")
                .build()
        )
    }

    private fun collectRealContacts(context: Context): List<RealContactCandidate> {
        val rmRawIds = findRmRawContactIds(context)
        val contactsByPhone = linkedMapOf<String, RealContactCandidate>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.Data.RAW_CONTACT_ID,
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && contactsByPhone.size < MAX_CONTACTS_PER_RUN) {
                val rawId = cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                if (rawId > 0L && rmRawIds.contains(rawId)) continue
                val visiblePhone = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val normalizedPhone = PhoneNormalizer.normalize(visiblePhone)
                if (normalizedPhone.isBlank() || contactsByPhone.containsKey(normalizedPhone)) continue
                val fallbackName = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                contactsByPhone[normalizedPhone] = RealContactCandidate(
                    rawContactId = rawId,
                    normalizedPhone = normalizedPhone,
                    visiblePhone = visiblePhone,
                    name = structuredName(context, rawId, fallbackName),
                )
            }
        }
        return contactsByPhone.values.toList()
    }

    private fun structuredName(context: Context, rawId: Long, fallback: String): StructuredContactName {
        if (rawId <= 0L) return StructuredContactName.fromDisplayName(fallback)
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use StructuredContactName.fromDisplayName(fallback)
                val given = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                val middle = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME)
                val family = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)
                val display = listOf(given, middle, family)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME) }
                    .ifBlank { fallback }
                StructuredContactName(givenName = given, middleName = middle, familyName = family, displayName = display)
            } ?: StructuredContactName.fromDisplayName(fallback)
        }.getOrDefault(StructuredContactName.fromDisplayName(fallback))
    }

    private fun findRmRawContactId(context: Context, normalizedPhone: String): Long {
        val bySync = CrmContactAccountStore.findCallReportRawContactId(context, normalizedPhone)
        if (bySync > 0L) return bySync
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.RAW_CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CallReportContactIntegration.ACCOUNT_TYPE),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val phone = cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (PhoneNormalizer.normalize(phone) == normalizedPhone) return@use cursor.getLongOrZero(ContactsContract.Data.RAW_CONTACT_ID)
                }
                0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun findRmRawContactIds(context: Context): Set<Long> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, CrmContactAccountStore.ACCOUNT_NAME),
                null,
            )?.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }.orEmpty()
        }.getOrDefault(emptySet())
    }

    private fun findDataRowId(context: Context, rawId: Long, mimeType: String): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), mimeType),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun findPrimaryRmPhoneRowId(context: Context, rawId: Long): Long {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.CommonDataKinds.Phone.LABEL),
                "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null,
            )?.use { cursor ->
                var first = 0L
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (first <= 0L) first = id
                    if (cursor.getStringOrEmpty(ContactsContract.CommonDataKinds.Phone.LABEL) == CrmContactAccountStore.ACCOUNT_NAME) return@use id
                }
                first
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
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

    private data class RealContactCandidate(
        val rawContactId: Long,
        val normalizedPhone: String,
        val visiblePhone: String,
        val name: StructuredContactName,
    )

    private data class StructuredContactName(
        val givenName: String = "",
        val middleName: String = "",
        val familyName: String = "",
        val displayName: String = "",
    ) {
        companion object {
            fun fromDisplayName(displayName: String): StructuredContactName = StructuredContactName(displayName = displayName)
        }
    }
}
