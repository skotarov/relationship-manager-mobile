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

data class BulkContactRegistrationProgress(
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
        val contacts = collectUniqueContacts(context)
        val total = contacts.size
        val mode = ConfigStore.load(context).contactLinkMode
        var lastPercent = -1

        fun report(processed: Int) {
            val progress = BulkContactRegistrationProgress(processed, total)
            if (progress.percent == lastPercent && processed != total) return
            lastPercent = progress.percent
            onProgress(progress)
        }

        report(0)
        contacts.forEachIndexed { index: Int, contact: BulkContactCandidate ->
            if (CallReportContactIntegration.isContactLinked(context, contact.phone)) {
                skippedExisting += 1
            } else if (saveCrmLink(context, mode, contact)) {
                created += 1
            } else {
                failed += 1
            }
            report(index + 1)
        }
        return BulkContactRegistrationResult(total, created, skippedExisting, failed)
    }

    private fun saveCrmLink(context: Context, mode: String, contact: BulkContactCandidate): Boolean {
        return when (mode) {
            ConfigStore.CONTACT_LINK_MODE_CONTACT -> CallReportStableCrmContactWriter.save(
                context,
                CallReportStableCrmContactWriter.Fields(
                    originalPhone = contact.phone,
                    displayName = contact.displayName.ifBlank { contact.phone },
                ),
            )
            else -> CallReportContactIntegration.linkContact(
                context = context,
                phone = contact.phone,
                displayName = contact.displayName.ifBlank { contact.phone },
            )
        }
    }

    private fun collectUniqueContacts(context: Context): List<BulkContactCandidate> {
        val contactsByPhone = linkedMapOf<String, BulkContactCandidate>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
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
            if (numberIndex < 0) return@use
            while (cursor.moveToNext() && contactsByPhone.size < MAX_CONTACTS_PER_RUN) {
                val phone = PhoneNormalizer.normalize(cursor.getString(numberIndex).orEmpty())
                if (phone.isBlank() || contactsByPhone.containsKey(phone)) continue
                val displayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                contactsByPhone[phone] = BulkContactCandidate(phone, displayName)
            }
        }
        return contactsByPhone.values.toList()
    }

    private fun canReadAndWriteContacts(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private data class BulkContactCandidate(
        val phone: String,
        val displayName: String,
    )
}
