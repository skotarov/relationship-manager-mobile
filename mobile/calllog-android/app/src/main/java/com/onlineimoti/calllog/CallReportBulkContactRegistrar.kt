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

    fun registerPhoneOnlyLinks(
        context: Context,
        onProgress: (BulkContactRegistrationProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): BulkContactRegistrationResult {
        if (!canReadAndWriteContacts(context)) return BulkContactRegistrationResult(0, 0, 0, 0)
        val mode = ConfigStore.load(context).contactLinkMode
        val contacts = collectUniqueContacts(context)
        val existingPhones = findCallReportPhones(context)
        return if (mode == ConfigStore.CONTACT_LINK_MODE_CONTACT) {
            registerContactMode(context, contacts, existingPhones, onProgress, shouldCancel)
        } else {
            registerLinkedAppMode(context, contacts, existingPhones, onProgress, shouldCancel)
        }
    }

    private fun registerLinkedAppMode(
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

        var created = 0
        var relinkedExisting = 0
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

            val alreadyLinked = existingPhones.contains(contact.phone)
            val title = contact.displayName.ifBlank { contact.phone }
            if (CallReportContactIntegration.linkContact(context, contact.phone, title)) {
                if (alreadyLinked) relinkedExisting += 1 else created += 1
            } else {
                failed += 1
            }

            processed += 1
            report()
            Thread.sleep(25L)
        }

        return BulkContactRegistrationResult(
            scanned = processed,
            created = created,
            skippedExisting = relinkedExisting,
            failed = failed,
            canceled = canceled,
        )
    }

    private fun registerContactMode(
        context: Context,
        contacts: List<BulkContactCandidate>,
        existingPhones: Set<String>,
        onProgress: (BulkContactRegistrationProgress) -> Unit,
        shouldCancel: () -> Boolean,
    ): BulkContactRegistrationResult {
        val contactsToCreate = contacts.filterNot { existingPhones.contains(it.phone) }
        val total = contacts.size
        var created = 0
        val skippedExisting = total - contactsToCreate.size
        var failed = 0
        var processed = skippedExisting
        var canceled = false
        var lastPercent = -1

        fun report() {
            val progress = BulkContactRegistrationProgress(processed, total)
            if (progress.percent == lastPercent && processed != total) return
            lastPercent = progress.percent
            onProgress(progress)
        }

        report()
        for (contact in contactsToCreate) {
            if (shouldCancel()) {
                canceled = true
                break
            }
            val saved = CallReportStableCrmContactWriter.save(
                context,
                CallReportStableCrmContactWriter.Fields(
                    originalPhone = contact.phone,
                    displayName = contact.displayName.ifBlank { contact.phone },
                ),
            )
            if (saved) created += 1 else failed += 1
            processed += 1
            report()
            Thread.sleep(25L)
        }
        return BulkContactRegistrationResult(processed, created, skippedExisting, failed, canceled)
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
            while (cursor.moveToNext() && contactsByPhone.size < MAX_CONTACTS_PER_RUN) {
                val rawContactId = if (rawContactIndex >= 0) cursor.getLong(rawContactIndex) else 0L
                if (rawContactId > 0L && callReportRawContactIds.contains(rawContactId)) continue
                val phone = PhoneNormalizer.normalize(cursor.getString(numberIndex).orEmpty())
                if (phone.isBlank() || contactsByPhone.containsKey(phone)) continue
                val displayName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                contactsByPhone[phone] = BulkContactCandidate(
                    phone = phone,
                    displayName = displayName,
                )
            }
        }
        return contactsByPhone.values.toList()
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

    private fun findCallReportPhones(context: Context): Set<String> {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.SYNC1),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND ${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND ${ContactsContract.RawContacts.DELETED}=0",
                arrayOf(CallReportContactIntegration.ACCOUNT_TYPE, CrmContactAccountStore.ACCOUNT_NAME),
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        val phone = PhoneNormalizer.normalize(cursor.getString(0).orEmpty())
                        if (phone.isNotBlank()) add(phone)
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptySet())
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
