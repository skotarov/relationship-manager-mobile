package com.onlineimoti.calllog

import android.content.Context

/**
 * Resolves the text that belongs to the currently selected note scope.
 * Local scope stays on-device; company scopes are refreshed from server history.
 */
internal object ContactNoteScopeTextResolver {
    fun cachedText(context: Context, draft: ContactNoteFormDraft, companyId: String): String {
        return when {
            companyId == ContactNoteTopicState.LOCAL_COMPANY_ID && draft.isGeneralNote -> {
                ContactNoteReader.generalNoteForPhone(context, draft.phone)
            }
            companyId == ContactNoteTopicState.LOCAL_COMPANY_ID -> {
                ContactNoteReader.callNoteForPhone(context, draft.phone, draft.callAt, draft.direction)
            }
            draft.isGeneralNote -> {
                CallReportCompanyGeneralNoteStore.noteFor(context, draft.phone, companyId)
            }
            else -> {
                ContactNoteReader.callNoteForPhone(context, draft.phone, draft.callAt, draft.direction)
            }
        }
    }

    /**
     * Returns every currently available server note keyed by company id. Null means
     * the server is not available, so callers should preserve their cached text.
     */
    fun loadServerTexts(context: Context, draft: ContactNoteFormDraft): Map<String, String>? {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (!CallReportRemoteAccess.isReady(config) || draft.phone.isBlank()) return null

        return if (draft.isGeneralNote) {
            val notes = CallReportCompanyGeneralNotesClient.fetch(appContext, config, draft.phone)
            val notesByCompany = linkedMapOf<String, String>()
            notes.forEach { companyNote ->
                notesByCompany[companyNote.companyId] = companyNote.note
                CallReportCompanyGeneralNoteStore.saveOrDelete(
                    appContext,
                    draft.phone,
                    companyNote.companyId,
                    companyNote.note,
                )
            }
            notesByCompany
        } else {
            val history = CallReportHistoryLookupClient.lookup(config, draft.phone)
            val phoneKey = HomeCallPageLoader.noteKey(draft.phone)
            val latestByCompany = mutableMapOf<String, CallReportHistoryEvent>()
            history.events.forEach { event ->
                if (!event.communicationType.equals("note", ignoreCase = true)) return@forEach
                if (event.companyId.isBlank() || event.note.isBlank()) return@forEach
                if (HomeCallPageLoader.noteKey(event.phone) != phoneKey) return@forEach
                if (draft.callAt > 0L && event.occurredAtMs != draft.callAt) return@forEach
                if (
                    draft.direction.isNotBlank() &&
                    event.direction.isNotBlank() &&
                    event.direction != draft.direction
                ) return@forEach
                val previous = latestByCompany[event.companyId]
                if (previous == null || event.updatedAtMs >= previous.updatedAtMs) {
                    latestByCompany[event.companyId] = event
                }
            }
            latestByCompany.mapValues { (_, event) -> event.note }
        }
    }

    fun textFor(companyId: String, draft: ContactNoteFormDraft, serverTexts: Map<String, String>?, context: Context): String {
        if (companyId == ContactNoteTopicState.LOCAL_COMPANY_ID) {
            return cachedText(context, draft, companyId)
        }
        return serverTexts?.get(companyId) ?: cachedText(context, draft, companyId)
    }
}
