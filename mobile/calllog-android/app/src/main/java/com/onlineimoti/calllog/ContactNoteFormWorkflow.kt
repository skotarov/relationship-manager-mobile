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
    /** Original cloud note id means the editor must mutate that existing note. */
    val serverClientEventId: String = "",
)

internal data class ContactNoteFormSaveResult(
    val writeResult: CallNoteWriteResult,
    val localOnlyFallback: Boolean = false,
    val serverSyncActivationAttempted: Boolean = false,
    val serverSyncEnabled: Boolean = false,
    val pendingServerSync: Boolean = false,
    val pendingCompanyChoice: Boolean = false,
) {
    val saved: Boolean get() = writeResult.saved
}

internal object ContactNoteFormWorkflow {
    fun initialTopicState(context: Context, draft: ContactNoteFormDraft): ContactNoteTopicState {
        val visible = shouldShowTopicSelector(context, draft)
        // A CRM contact and an unknown number can choose Local or a real firm.
        // A normal known contact without CRM remains strictly local.
        val localOnly = visible && !ContactServerCompanyScope.isAvailable(context, draft.phone)
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
                usingCachedCompanies = false,
                cachedCompaniesUpdatedAtMs = 0L,
            )
        }

        val result = runCatching {
            CallReportTopicCompaniesRepository.load(
                context = context.applicationContext,
                config = ConfigStore.load(context.applicationContext),
            )
        }
        val loaded = result.getOrNull()
        val companies = loaded?.companies.orEmpty()
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
            usingCachedCompanies = loaded?.source == TopicCompaniesSource.CACHED,
            cachedCompaniesUpdatedAtMs = if (loaded?.source == TopicCompaniesSource.CACHED) loaded.updatedAtMs else 0L,
        )
    }

    /** Returns null while an eligible CRM/unknown contact still needs a destination selection. */
    fun selectedTopicOrLocalFallback(state: ContactNoteTopicState): String? {
        if (!state.visible || state.localOnly) return ContactNoteTopicState.LOCAL_COMPANY_ID
        // Without a cached list, save locally and retain a durable reminder to choose
        // a firm later. The user never loses the note because of a connection error.
        if (state.loadError.isNotBlank()) return ContactNoteTopicState.LOCAL_COMPANY_ID
        if (state.loading || state.selectedCompanyId.isBlank()) return null
        return state.selectedCompanyId
    }

    fun willEnableServerSync(context: Context, draft: ContactNoteFormDraft, state: ContactNoteTopicState): Boolean {
        return state.selectedCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
            state.selectedCompanyId.isNotBlank() &&
            state.loadError.isBlank() &&
            shouldAutoEnableServerSync(context, draft)
    }

    fun save(
        context: Context,
        draft: ContactNoteFormDraft,
        noteText: String,
        topicCompanyId: String,
        localOnlyFallback: Boolean = false,
    ): ContactNoteFormSaveResult {
        val appContext = context.applicationContext
        val existingServerNoteId = draft.serverClientEventId.trim()
        if (existingServerNoteId.isNotBlank()) {
            val queued = CallReportNoteOutbox.enqueueExistingServerNote(
                context = appContext,
                phone = draft.phone,
                note = noteText,
                serverClientEventId = existingServerNoteId,
                direction = draft.direction,
                callAt = draft.callAt,
                durationSeconds = draft.durationSeconds,
            )
            return ContactNoteFormSaveResult(
                writeResult = CallNoteWriteResult(
                    saved = queued,
                    savedAsGeneralNote = false,
                    target = CallNoteTarget(draft.direction, draft.callAt, draft.durationSeconds),
                ),
                pendingServerSync = queued,
            )
        }

        // A stale UI or a direct caller cannot send an ordinary known non-CRM
        // contact to the server.
        val serverDestinationAllowed = ContactServerCompanyScope.isAvailable(appContext, draft.phone)
        val selectedTopic = topicCompanyId.trim()
        val isLocalSelection = selectedTopic == ContactNoteTopicState.LOCAL_COMPANY_ID || !serverDestinationAllowed
        val serverCompanyId = if (isLocalSelection) "" else selectedTopic
        // A selected company main note must reach the server even when the user
        // clears its text. Without this, an unknown number had sync enabled only
        // for nonblank saves and the delete remained local-only.
        val activateUnknownSync = serverCompanyId.isNotBlank() &&
            shouldAutoEnableServerSync(appContext, draft) &&
            (noteText.trim().isNotBlank() || draft.isGeneralNote)

        // Mark the unknown number locally before calling the topic writer. The writer
        // can then save and enqueue the company event even while offline, without
        // requiring Contacts write access or a live network response.
        val serverSyncEnabled = if (activateUnknownSync) {
            RmContactSyncLayerStore.enableTopicSync(appContext, draft.phone)
        } else {
            false
        }

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
        if (!writeResult.saved) {
            return ContactNoteFormSaveResult(
                writeResult = writeResult,
                localOnlyFallback = localOnlyFallback,
                serverSyncActivationAttempted = activateUnknownSync,
                serverSyncEnabled = serverSyncEnabled,
            )
        }

        val pendingCompanyChoice = localOnlyFallback &&
            isLocalSelection &&
            serverDestinationAllowed &&
            noteText.trim().isNotBlank()
        updateDeferredCompanyAssignment(
            context = appContext,
            draft = draft,
            writeResult = writeResult,
            pendingCompanyChoice = pendingCompanyChoice,
        )

        // Selecting Local for an eligible concrete call removes any previous
        // server firm assignment for that same call only. A forced offline local
        // fallback deliberately does not unassign the existing server firm.
        if (
            isLocalSelection &&
            !localOnlyFallback &&
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

        val pendingServerSync = serverCompanyId.isNotBlank() && isTopicSyncPending(
            context = appContext,
            phone = draft.phone,
            writeResult = writeResult,
            companyId = serverCompanyId,
        )
        return ContactNoteFormSaveResult(
            writeResult = writeResult,
            localOnlyFallback = localOnlyFallback,
            serverSyncActivationAttempted = activateUnknownSync,
            serverSyncEnabled = serverSyncEnabled,
            pendingServerSync = pendingServerSync,
            pendingCompanyChoice = pendingCompanyChoice,
        )
    }

    private fun updateDeferredCompanyAssignment(
        context: Context,
        draft: ContactNoteFormDraft,
        writeResult: CallNoteWriteResult,
        pendingCompanyChoice: Boolean,
    ) {
        if (writeResult.savedAsGeneralNote) {
            if (pendingCompanyChoice) {
                CallReportDeferredCompanyAssignmentStore.markGeneral(context, draft.phone)
            } else {
                CallReportDeferredCompanyAssignmentStore.clearGeneral(context, draft.phone)
            }
            return
        }

        if (!writeResult.target.hasCall) return
        if (pendingCompanyChoice) {
            CallReportDeferredCompanyAssignmentStore.markCall(
                context = context,
                phone = draft.phone,
                direction = writeResult.target.direction,
                callAtMs = writeResult.target.callAt,
                durationSeconds = writeResult.target.durationSeconds,
            )
        } else {
            CallReportDeferredCompanyAssignmentStore.clearCall(
                context = context,
                phone = draft.phone,
                direction = writeResult.target.direction,
                callAtMs = writeResult.target.callAt,
            )
        }
    }

    private fun isTopicSyncPending(
        context: Context,
        phone: String,
        writeResult: CallNoteWriteResult,
        companyId: String,
    ): Boolean {
        return if (writeResult.savedAsGeneralNote) {
            CallReportTopicNoteOutbox.isGeneralPending(context, phone, companyId)
        } else if (writeResult.target.hasCall) {
            CallReportTopicNoteOutbox.isCallPending(
                context = context,
                phone = phone,
                direction = writeResult.target.direction,
                callAt = writeResult.target.callAt,
            )
        } else {
            false
        }
    }

    private fun shouldShowTopicSelector(context: Context, @Suppress("UNUSED_PARAMETER") draft: ContactNoteFormDraft): Boolean {
        return CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))
    }

    private fun shouldAutoEnableServerSync(context: Context, draft: ContactNoteFormDraft): Boolean {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))) return false
        if (CrmContactSyncStore.isEnabled(context, draft.phone)) return false
        return ContactServerCompanyScope.isUnknownNumber(context, draft.phone)
    }

    private const val TOPIC_REQUEST_FAILED = "topic_request_failed"
}
