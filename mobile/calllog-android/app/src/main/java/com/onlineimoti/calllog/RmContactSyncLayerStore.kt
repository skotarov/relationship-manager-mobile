package com.onlineimoti.calllog

import android.content.Context
import android.provider.ContactsContract

/** Coordinates Cloud Sync state for a single RM layer. */
internal object RmContactSyncLayerStore {
    /**
     * Enables or disables the RM/server-sync layer for one phone.
     *
     * [enqueueExistingNotes] stays true for normal manual activation. Topic-note
     * activation passes false because the note already has its own company-aware
     * outbox record and must not be duplicated in the generic note outbox.
     */
    fun setEnabled(
        context: Context,
        phone: String,
        title: String,
        enabled: Boolean,
        enqueueExistingNotes: Boolean = true,
    ): Boolean {
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
        if (enqueueExistingNotes) {
            // Existing local notes and phone/SMS history are now eligible for the normal catch-up sync.
            CallReportNoteOutbox.enqueueCurrentLocalNotes(appContext, normalizedPhone)
            CallReportSyncScheduler.enqueueCatchUp(appContext, reason = "contact_sync_enabled")
        }
        return true
    }

    /**
     * Enables the local CRM marker required by the company-note outbox, without
     * creating or updating a Contacts raw record. This lets an unknown number be
     * classified under a firm while the device is offline or Contacts permission is unavailable.
     */
    fun enableTopicSync(context: Context, phone: String): Boolean {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false
        CrmContactSyncStore.setEnabled(context.applicationContext, normalizedPhone, true)
        return true
    }

    /**
     * Changes only the CRM/server-sync switch. It never creates, updates or deletes
     * the RM raw contact layer. Use this for the CRM button in the contact header.
     */
    fun setCloudSyncWithoutRmLayer(context: Context, phone: String, enabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return false

        CrmContactSyncStore.setEnabled(appContext, normalizedPhone, enabled)
        if (enabled) {
            // The generic server worker reads the enabled flag directly; it has no
            // dependency on an Android RM raw contact.
            CallReportNoteOutbox.enqueueCurrentLocalNotes(appContext, normalizedPhone)
            CallReportSyncScheduler.enqueueCatchUp(appContext, reason = "crm_toggle_without_rm_layer")
        } else {
            // Do not send pending local changes once CRM has been switched off.
            CallReportNoteOutbox.removeForPhone(appContext, normalizedPhone)
        }
        return true
    }

    /**
     * Deletes only this app's RM raw contact for the number. A normal Android
     * contact is never touched. Cloud Sync is disabled first, otherwise a later
     * sync could recreate the deleted RM layer.
     */
    fun deleteRmContact(context: Context, phone: String): Boolean {
        val appContext = context.applicationContext
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank() || !RmContactPermissions.canReadAndWriteContacts(appContext)) return false

        CrmContactSyncStore.setEnabled(appContext, normalizedPhone, false)
        CallReportNoteOutbox.removeForPhone(appContext, normalizedPhone)
        RmCloudSyncLabelStore.removeFromRmAndVisibleContacts(appContext, normalizedPhone)
        return deleteRmLayer(appContext, normalizedPhone)
    }

    fun isEnabled(context: Context, phone: String): Boolean {
        return groupNameForCurrentRules(context, phone) == RmCloudSyncLabelStore.GROUP_NAME
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
