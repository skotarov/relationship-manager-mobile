package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
        return ContactNoteTopicState(visible = visible, loading = visible)
    }

    fun loadTopics(context: Context, previous: ContactNoteTopicState): ContactNoteTopicState {
        if (!previous.visible) return previous
        val result = runCatching {
            CallReportTopicCompaniesClient.fetch(ConfigStore.load(context.applicationContext))
        }
        val companies = result.getOrDefault(emptyList())
        val loadFailed = result.isFailure
        val selectedCompanyId = if (loadFailed) {
            ""
        } else {
            previous.selectedCompanyId.takeIf { selected -> companies.any { it.id == selected } }
                ?: companies.singleOrNull()?.id.orEmpty()
        }
        return previous.copy(
            loading = false,
            companies = companies,
            selectedCompanyId = selectedCompanyId,
            loadError = if (loadFailed) TOPIC_REQUEST_FAILED else "",
        )
    }

    /** Returns null only when a normally available topic selector still needs a selection. */
    fun selectedTopicOrLocalFallback(state: ContactNoteTopicState): String? {
        if (!state.visible || state.loadError.isNotBlank()) return ""
        if (state.loading || state.selectedCompanyId.isBlank()) return null
        return state.selectedCompanyId
    }

    fun willEnableServerSync(context: Context, draft: ContactNoteFormDraft, state: ContactNoteTopicState): Boolean {
        return state.loadError.isBlank() && shouldAutoEnableServerSync(context, draft)
    }

    fun save(
        context: Context,
        draft: ContactNoteFormDraft,
        noteText: String,
        topicCompanyId: String,
        localOnlyFallback: Boolean = false,
    ): ContactNoteFormSaveResult {
        val appContext = context.applicationContext
        val activateUnknownSync = noteText.trim().isNotBlank() &&
            topicCompanyId.isNotBlank() &&
            shouldAutoEnableServerSync(appContext, draft)
        val writeResult = when {
            topicCompanyId.isNotBlank() && draft.isGeneralNote -> {
                CallNoteTopicWriter.writeGeneral(appContext, draft.phone, noteText, topicCompanyId)
            }
            topicCompanyId.isNotBlank() -> {
                CallNoteTopicWriter.writeCallOrGeneral(
                    context = appContext,
                    phone = draft.phone,
                    text = noteText,
                    direction = draft.direction,
                    callAt = draft.callAt,
                    durationSeconds = draft.durationSeconds,
                    actionIssuedAt = draft.actionIssuedAt,
                    companyId = topicCompanyId,
                )
            }
            draft.isGeneralNote -> {
                CallNoteWriter.writeGeneral(
                    context = appContext,
                    phone = draft.phone,
                    text = noteText,
                    syncToCrm = !localOnlyFallback,
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
                syncToCrm = !localOnlyFallback,
            )
        }
        if (!writeResult.saved) return ContactNoteFormSaveResult(writeResult, localOnlyFallback = localOnlyFallback)

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
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))) return false
        // Main notes are scoped to one firm whenever a server is configured.
        // Call notes retain the previous contact-sync behavior.
        if (draft.isGeneralNote) return true
        return CrmContactSyncStore.isEnabled(context, draft.phone) || shouldAutoEnableServerSync(context, draft)
    }

    private fun shouldAutoEnableServerSync(context: Context, draft: ContactNoteFormDraft): Boolean {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(context.applicationContext))) return false
        if (CrmContactSyncStore.isEnabled(context, draft.phone)) return false
        if (draft.phone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return true
        }
        return RmRealContactLookup.findContactId(context, draft.phone) <= 0L
    }

    private const val TOPIC_REQUEST_FAILED = "topic_request_failed"
}
