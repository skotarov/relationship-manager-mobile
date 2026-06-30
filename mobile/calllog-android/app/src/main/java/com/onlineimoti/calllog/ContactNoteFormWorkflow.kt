package com.onlineimoti.calllog

import android.content.Context

/**
 * Shared business flow for every note editor, whether it is full-screen or an
 * overlay popup. UI implementations only render [ContactNoteTopicState].
 */
internal data class ContactNoteFormDraft(
    val phone: String,
    val title: String,
    val direction: String = "",
    val callAt: Long = 0L,
    val durationSeconds: Long = 0L,
    val actionIssuedAt: Long = 0L,
    val isGeneralNote: Boolean = false,
)

internal data class ContactNoteFormSaveResult(
    val writeResult: CallNoteWriteResult,
    val localOnlyFallback: Boolean = false,
    val serverSyncActivationAttempted: Boolean = false,
    val serverSyncEnabled: Boolean = false,
) {
    val saved: Boolean get() = writeResult.saved
}

internal object ContactNoteFormWorkflow {
    fun initialTopicState(context: Context, draft: ContactNoteFormDraft): ContactNoteTopicState {
        val visible = shouldShowTopicSelector(context, draft)
        // A server company can be selected only for an explicitly CRM-marked
        // contact. Unknown and ordinary contacts remain local-only.
        val localOnly = visible && !canUseServerDestination(context, draft.phone)
        return ContactNoteTopicState(
            visible = visible,
            loading = visible && !localOnly,
            selectedCompanyId = if (localOnly) ContactNoteTopicState.LOCAL_COMPANY_ID else "",
            includeLocalOption = visible,
            localOnly = localOnly,
        )
    }

    fun loadTopics(context: Context, previous: ContactNoteTopicState): ContactNoteTopicState {
        if (!previous.visible) return previous
        if (previous.localOnly) {
            return previous.copy(
                loading = false,
                companies = emptyList(),
                selectedCompanyId = ContactNoteTopicState.LOCAL_COMPANY_ID,
                loadError = "",
            )
        }

        val result = runCatching {
            CallReportTopicCompaniesClient.fetch(ConfigStore.load(context.applicationContext))
        }
        val companies = result.getOrDefault(emptyList())
        val loadFailed = result.isFailure
        val selectedCompanyId = when {
            previous.selectedCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID -> {
                ContactNoteTopicState.LOCAL_COMPANY_ID
            }
            loadFailed -> ""
            else -> previous.selectedCompanyId.takeIf { selected -> companies.any { it.id == selected } }.orEmpty()
        }
        return previous.copy(
            loading = false,
            companies = companies,
            selectedCompanyId = selectedCompanyId,
            loadError = if (loadFailed) TOPIC_REQUEST_FAILED else "",
        )
    }

    /** Returns null while an eligible CRM contact still needs a destination selection. */
    fun selectedTopicOrLocalFallback(state: ContactNoteTopicState): String? {
        if (!state.visible || state.localOnly) return ContactNoteTopicState.LOCAL_COMPANY_ID
        if (state.loadError.isNotBlank()) return ContactNoteTopicState.LOCAL_COMPANY_ID
        if (state.loading || state.selectedCompanyId.isBlank()) return null
        return state.selectedCompanyId
    }

    /** CRM is already explicitly enabled for eligible contacts; never auto-enable it from a note. */
    fun willEnableServerSync(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") draft: ContactNoteFormDraft,
        @Suppress("UNUSED_PARAMETER") state: ContactNoteTopicState,
    ): Boolean = false

    fun save(
        context: Context,
        draft: ContactNoteFormDraft,
        noteText: String,
        topicCompanyId: String,
        localOnlyFallback: Boolean = false,
    ): ContactNoteFormSaveResult {
        val appContext = context.applicationContext
        // This is a second, non-UI guard: a stale form or direct caller cannot
        // send an unmarked contact to a server company.
        val serverDestinationAllowed = canUseServerDestination(appContext, draft.phone)
        val isLocalSelection = topicCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID || !serverDestinationAllowed
        val serverCompanyId = if (isLocalSelection) "" else topicCompanyId
        val writeResult = when {
            serverCompanyId.isNotBlank() && draft.isGeneralNote -> {
                CallNoteTopicWriter.writeGeneral(appContext, draft.phone, noteText, serverCompanyId)
            }
            serverCompanyId.isNotBlank() -> {
                CallNoteTopicWriter.writeCallOrGeneral(
                    context = appContext,
                    phone = draft.phone,
                    text = noteText,
                    direction = draft.direction,
                    callAt = draft.callAt,
                    durationSeconds = draft.durationSeconds,
                    actionIssuedAt = draft.actionIssuedAt,
                    companyId = serverCompanyId,
                )
            }
            draft.isGeneralNote -> {
                CallNoteWriter.writeGeneral(
                    context = appContext,
                    phone = draft.phone,
                    text = noteText,
                    syncToCrm = false,
                )
            }
            else -> CallNoteWriter.writeCallOrGeneral(
                context = appContext,
                phone = draft.phone,
                text = noteText,
                direction = draft.direction,
                callAt = draft.callAt,
                durationSeconds = draft.durationSeconds,
                actionIssuedAt = draft.actionIssuedAt,
                syncToCrm = false,
            )
        }
        if (!writeResult.saved) return ContactNoteFormSaveResult(writeResult, localOnlyFallback = localOnlyFallback)

        // Only a CRM-marked contact may change a server-side company assignment.
        if (
            isLocalSelection &&
            serverDestinationAllowed &&
            !draft.isGeneralNote &&
            writeResult.target.hasCall &&
            CallReportRemoteAccess.isReady(ConfigStore.load(appContext))
        ) {
            CallReportTopicNoteOutbox.enqueueUnassignCall(
                context = appContext,
                phone = draft.phone,
                direction = writeResult.target.direction,
                callAt = writeResult.target.callAt,
                durationSeconds = writeResult.target.durationSeconds,
                clientNoteId = LocalNotesFileStore.clientNoteIdForCall(
                    draft.phone,
                    writeResult.target.callAt,
                    writeResult.target.direction,
                ),
            )
        }

        return ContactNoteFormSaveResult(
            writeResult = writeResult,
            localOnlyFallback = localOnlyFallback,
        )
    }

    private fun shouldShowTopicSelector(context: Context, draft: ContactNoteFormDraft): Boolean {
        return CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))
    }

    private fun canUseServerDestination(context: Context, phone: String): Boolean {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))) return false
        return CrmContactSyncStore.isEnabled(context, phone)
    }

    private const val TOPIC_REQUEST_FAILED = "topic_request_failed"
}
