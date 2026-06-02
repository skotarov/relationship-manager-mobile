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

internal object CallReportBulkContactRegistrar {
    private const val MAX_CONTACTS_PER_RUN = 2500

    fun registerPhoneOnlyLinks(context: Context): BulkContactRegistrationResult {
        if (!canReadAndWriteContacts(context)) return BulkContactRegistrationResult(0, 0, 0, 0)
        var scanned = 0
        var created = 0
        var skippedExisting = 0
        var failed = 0
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
                if (phone.isBlank() || !seen.add(phone)) continue
                scanned += 1
                if (CallReportContactIntegration.isContactLinked(context, phone)) {
                    skippedExisting += 1
                    continue
                }
                if (CallReportContactIntegration.linkContact(context, phone, "")) {
                    created += 1
                } else {
                    failed += 1
                }
            }
        }
        return BulkContactRegistrationResult(scanned, created, skippedExisting, failed)
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}