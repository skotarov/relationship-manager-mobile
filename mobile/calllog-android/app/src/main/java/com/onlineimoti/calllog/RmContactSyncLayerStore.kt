package com.onlineimoti.calllog

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

internal object RmContactSyncLayerStore {
    private const val CLOUD_SYNC_GROUP_NAME = "Cloud Sync"

    fun setEnabled(context: Context, phone: String, title: String, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false

        if (!enabled) {
            CrmContactSyncStore.setEnabled(appContext, normalizedPhone, false)
            return clearCloudData(appContext, normalizedPhone)
        }

        val updated = ensureLayerWithCurrentNote(appContext, normalizedPhone, title)
        if (updated) CrmContactSyncStore.setEnabled(appContext, normalizedPhone, true)
        return updated
    }

    fun refreshNoteIfEnabled(context: Context, phone: String, title: String = "") {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return
        if (!CrmContactSyncStore.isEnabled(appContext, normalizedPhone)) return
        ensureLayerWithCurrentNote(appContext, normalizedPhone, title)
    }

    private fun ensureLayerWithCurrentNote(context: Context, phone: String, title: String): Boolean {
        if (!RmContactPermissions.canReadAndWriteContacts(context)) return false
        val displayName = title.trim()
            .ifBlank { ContactGroupFilter.resolveDisplayName(context, phone).orEmpty() }
            .ifBlank { RmContactReader.findRmRecord(context, phone)?.displayName.orEmpty() }
            .ifBlank { phone }
        val note = ContactNoteReader.generalNoteForPhone(context, phone)
        val fields = CallReportStableCrmContactWriter.Fields(
            originalPhone = phone,
            displayName = displayName,
            note = note,
            groupName = CLOUD_SYNC_GROUP_NAME,
        )
        return CrmContactLinkSaver.save(
            context = context,
            fields = fields,
            mode = ConfigStore.load(context).contactLinkMode,
            phone = phone,
            title = displayName,
        )
    }

    private fun clearCloudData(context: Context, phone: String): Boolean {
        if (!RmContactPermissions.canReadAndWriteContacts(context)) return false
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return true
        val keepMimes = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            CallReportContactIntegration.HISTORY_MIME_TYPE,
        )
        val placeholders = keepMimes.joinToString(",") { "?" }
        val ops = arrayListOf(
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} NOT IN ($placeholders)",
                    arrayOf(rawId.toString(), *keepMimes),
                )
                .build()
        )
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess
    }
}
