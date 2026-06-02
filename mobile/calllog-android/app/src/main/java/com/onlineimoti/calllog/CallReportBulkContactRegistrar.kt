package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal data class BulkContactRegistrationResult(
    val scanned: Int,
    val created: Int,
    val skippedExisting: Int,
    val failed: Int,
)

internal data class BulkContactRegistrationProgress(
    val processed: Int,
    val total: Int,
) {
    val percent: Int = if (total <= 0) 100 else ((processed * 100f) / total).toInt().coerceIn(0, 100)
}

internal object CallReportBulkContactRegistrar {
    private const val MAX_CONTACTS_PER_RUN = 2500

    fun registerPhoneOnlyLinks(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
    ): BulkContactRegistrationResult {
        if (!canReadAndWriteContacts(context)) return BulkContactRegistrationResult(0, 0, 0, 0)
        var created = 0
        var skippedExisting = 0
        var failed = 0
        val phones = collectUniquePhones(context)
        val total = phones.size
        var lastPercent = -1

        fun report(processed: Int) {
            val progress = BulkContactRegistrationProgress(processed, total)
            if (progress.percent == lastPercent && processed != total) return
            lastPercent = progress.percent
            onProgress(progress)
        }

        report(0)
        phones.forEachIndexed { index: Int, phone: String ->
            if (CallReportContactIntegration.isContactLinked(context, phone)) {
                skippedExisting += 1
            } else if (CallReportContactIntegration.linkContactAsAppIfMissing(context, phone)) {
                created += 1
            } else {
                failed += 1
            }
            report(index + 1)
        }
        return BulkContactRegistrationResult(total, created, skippedExisting, failed)
    }

    private fun collectUniquePhones(context: Context): List<String> {
        val seen = linkedSetOf<String>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.NUMBER + " ASC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && seen.size < MAX_CONTACTS_PER_RUN) {
                val phone = PhoneNormalizer.normalize(cursor.getString(numberIndex).orEmpty())
                if (phone.isNotBlank()) seen.add(phone)
            }
        }
        return seen.toList()
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}