package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

/** Coordinates Cloud Sync state for a single RM layer. */
internal object RmContactSyncLayerStore {
    fun setEnabled(context: Context, phone: String, title: String, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false

        if (!enabled) {
            CrmContactSyncStore.setEnabled(appContext, normalizedPhone, false)
            // Respect the switch immediately: unsent local note changes must not leave the device.
            CallReportNoteOutbox.removeForPhone(appContext, normalizedPhone)
            if (RmRealContactLookup.findRawContactId(appContext, normalizedPhone) <= 0L) {
                return deleteRmLayer(appContext, normalizedPhone)
            }

            val labelsRemoved = RmCloudSyncLabelStore.removeFromRmAndVisibleContacts(appContext, normalizedPhone)
            val noteUpdated = RmLayerContactDataSyncer.sync(appContext, normalizedPhone)
            return labelsRemoved && noteUpdated
        }

        CrmContactSyncStore.setEnabled(appContext, normalizedPhone, true)
        if (!ensureLayer(appContext, normalizedPhone, title)) {
            CrmContactSyncStore.setEnabled(appContext, normalizedPhone, false)
            return false
        }
        // Existing local notes are added once when the user enables server sync for this contact.
        CallReportNoteOutbox.enqueueCurrentLocalNotes(appContext, normalizedPhone)
        return true
    }

    fun refreshNoteIfEnabled(context: Context, phone: String, title: String = "") {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank() || !CrmContactSyncStore.isEnabled(appContext, normalizedPhone)) return
        ensureLayer(appContext, normalizedPhone, title)
    }

    fun noteForCurrentRules(context: Context, phone: String): String {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone.trim() }
        return RmLayerNoteSyncer.formatForCurrentRules(
            context = appContext,
            phone = normalizedPhone,
            note = ContactNoteReader.generalNoteForPhone(appContext, normalizedPhone),
        )
    }

    fun groupNameForCurrentRules(
        context: Context,
        phone: String,
        fallback: String = CrmContactAccountStore.ACCOUNT_NAME,
    ): String {
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank { phone }
        return if (CrmContactSyncStore.isEnabled(context.applicationContext, normalizedPhone)) {
            RmCloudSyncLabelStore.GROUP_NAME
        } else {
            fallback
        }
    }

    fun applyCloudSyncLabelsIfEnabled(context: Context, phone: String): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank() || !CrmContactSyncStore.isEnabled(appContext, normalizedPhone)) return true
        return RmCloudSyncLabelStore.applyToVisibleContacts(appContext, normalizedPhone)
    }

    private fun ensureLayer(context: Context, phone: String, title: String): Boolean {
        if (!RmContactPermissions.canReadAndWriteContacts(context)) return false
        val displayName = title.trim()
            .ifBlank { ContactGroupFilter.resolveDisplayName(context, phone).orEmpty() }
            .ifBlank { RmContactReader.findRmRecord(context, phone)?.displayName.orEmpty() }
            .ifBlank { phone }

        val saved = CrmContactLinkSaver.save(
            context = context,
            fields = CallReportStableCrmContactWriter.Fields(
                originalPhone = phone,
                displayName = displayName,
                note = noteForCurrentRules(context, phone),
                groupName = groupNameForCurrentRules(context, phone),
            ),
            mode = ConfigStore.load(context).contactLinkMode,
            phone = phone,
            title = displayName,
        )
        if (!saved) return false

        val dataSynced = RmLayerContactDataSyncer.sync(context, phone)
        val labelsSynced = applyCloudSyncLabelsIfEnabled(context, phone)
        return dataSynced && labelsSynced
    }

    private fun deleteRmLayer(context: Context, phone: String): Boolean {
        if (!RmContactPermissions.canReadAndWriteContacts(context)) return false
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return true

        val deleted = runCatching {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID}=?",
                arrayOf(rawId.toString()),
            )
        }.getOrDefault(0)
        return deleted > 0 || CrmContactAccountStore.findCallReportRawContactId(context, phone) <= 0L
    }
}
