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
        val localOnly = visible && !ContactServerCompanyScope.isAvailable(context, draft.phone)
        return ContactNoteTopicState(
            visible = visible,
            loading = visible && !localOnly,
            // Non-CRM known contacts are deliberately local-only. CRM contacts and
            // unknown numbers must explicitly choose Local or one server company.
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

    /** Returns null while an eligible CRM/unknown contact still needs a destination selection. */
    fun selectedTopicOrLocalFallback(state: ContactNoteTopicState): String? {
        if (!state.visible || state.localOnly) return ContactNoteTopicState.LOCAL_COMPANY_ID
        if (state.loadError.isNotBlank()) return ContactNoteTopicState.LOCAL_COMPANY_ID
        if (state.loading || state.selectedCompanyId.isBlank()) return null
        return state.selectedCompanyId
    }

    fun willEnableServerSync(context: Context, draft: ContactNoteFormDraft, state: ContactNoteTopicState): Boolean {
        return state.selectedCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
            state.selectedCompanyId.isNotBlank() &&
            state.loadError.isBlank() && shouldAutoEnableServerSync(context, draft)
    }

    fun save(
        context: Context,
        draft: ContactNoteFormDraft,
        noteText: String,
        topicCompanyId: String,
        localOnlyFallback: Boolean = false,
    ): ContactNoteFormSaveResult {
        val appContext = context.applicationContext
        val isLocalSelection = topicCompanyId == ContactNoteTopicState.LOCAL_COMPANY_ID
        val serverCompanyId = if (isLocalSelection) "" else topicCompanyId
        val activateUnknownSync = noteText.trim().isNotBlank() &&
            serverCompanyId.isNotBlank() &&
            shouldAutoEnableServerSync(appContext, draft)
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

        // Selecting Local for a concrete call note removes its previous server
        // firm assignment. Main notes remain independent per company.
        if (isLocalSelection && !draft.isGeneralNote && writeResult.target.hasCall && CallReportRemoteAccess.isReady(ConfigStore.load(appContext))) {
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

        val syncEnabled = if (activateUnknownSync) {
            RmContactSyncLayerStore.setEnabled(
                context = appContext,
                phone = draft.phone,
                title = draft.title,
                enabled = true,
                enqueueExistingNotes = false,
            )
        } else {
            false
        }
        return ContactNoteFormSaveResult(
            writeResult = writeResult,
            localOnlyFallback = localOnlyFallback,
            serverSyncActivationAttempted = activateUnknownSync,
            serverSyncEnabled = syncEnabled,
        )
    }

    private fun shouldShowTopicSelector(context: Context, draft: ContactNoteFormDraft): Boolean {
        return CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))
    }

    private fun shouldAutoEnableServerSync(context: Context, draft: ContactNoteFormDraft): Boolean {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))) return false
        if (CrmContactSyncStore.isEnabled(context, draft.phone)) return false
        return ContactServerCompanyScope.isUnknownNumber(context, draft.phone)
    }

    private const val TOPIC_REQUEST_FAILED = "topic_request_failed"
}
