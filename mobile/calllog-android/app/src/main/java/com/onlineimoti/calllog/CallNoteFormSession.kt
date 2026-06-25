package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Shared state and save rules for every local note form, regardless of whether
 * it is displayed full-screen or inside an overlay popup.
 */
internal class CallNoteFormSession(
    context: Context,
    private val phone: String,
    private val title: String,
    private val isGeneralNote: Boolean,
    private val direction: String,
    private val callAt: Long,
    private val durationSeconds: Long,
    private val actionIssuedAt: Long,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var topicState: ContactNoteTopicState = initialTopicState()
        private set

    fun selectTopic(companyId: String) {
        topicState = topicState.copy(selectedCompanyId = companyId)
    }

    fun loadTopicsAsync(onLoaded: (ContactNoteTopicState) -> Unit) {
        if (!topicState.visible) return
        Thread {
            val loaded = loadTopics()
            mainHandler.post { onLoaded(loaded) }
        }.start()
    }

    fun willEnableServerSyncForUnknown(): Boolean {
        return shouldAutoEnableServerSyncForUnknown() && topicState.loadError.isBlank()
    }

    fun save(noteText: String): CallNoteFormSaveOutcome {
        val state = topicState
        if (state.visible && state.loadError.isBlank() && (state.loading || state.selectedCompanyId.isBlank())) {
            return CallNoteFormSaveOutcome(topicRequired = true)
        }

        val companyId = state.selectedCompanyId.takeIf {
            state.visible && state.loadError.isBlank() && it.isNotBlank()
        }.orEmpty()
        val localOnlyFallback = state.visible && state.loadError.isNotBlank()
        val writeResult = writeNote(noteText, companyId, localOnlyFallback)
        if (!writeResult.saved) return CallNoteFormSaveOutcome(saved = false)

        val activateUnknownSync = noteText.trim().isNotBlank() &&
            companyId.isNotBlank() &&
            shouldAutoEnableServerSyncForUnknown()
        val syncEnabled = if (activateUnknownSync) {
            RmContactSyncLayerStore.setEnabled(
                context = appContext,
                phone = phone,
                title = title,
                enabled = true,
                enqueueExistingNotes = false,
            )
        } else {
            false
        }

        return CallNoteFormSaveOutcome(
            saved = true,
            savedAsGeneralNote = writeResult.savedAsGeneralNote,
            target = writeResult.target,
            localOnlyFallback = localOnlyFallback,
            serverSyncActivationAttempted = activateUnknownSync,
            serverSyncEnabled = syncEnabled,
        )
    }

    private fun initialTopicState(): ContactNoteTopicState {
        val visible = shouldShowTopicSelector()
        return ContactNoteTopicState(visible = visible, loading = visible)
    }

    private fun loadTopics(): ContactNoteTopicState {
        val current = topicState
        if (!current.visible) return current
        val result = runCatching {
            CallReportTopicCompaniesClient.fetch(ConfigStore.load(appContext))
        }
        val companies = result.getOrDefault(emptyList())
        val loadError = result.exceptionOrNull()?.let { TOPIC_REQUEST_FAILED }.orEmpty()
        val selectedCompanyId = if (loadError.isBlank()) {
            current.selectedCompanyId.takeIf { selected -> companies.any { it.id == selected } }
                ?: companies.singleOrNull()?.id.orEmpty()
        } else {
            ""
        }
        return current.copy(
            loading = false,
            companies = companies,
            selectedCompanyId = selectedCompanyId,
            loadError = loadError,
        ).also { topicState = it }
    }

    private fun writeNote(
        noteText: String,
        companyId: String,
        localOnlyFallback: Boolean,
    ): CallNoteWriteResult {
        if (companyId.isNotBlank()) {
            return if (isGeneralNote) {
                CallNoteTopicWriter.writeGeneral(appContext, phone, noteText, companyId)
            } else {
                CallNoteTopicWriter.writeCallOrGeneral(
                    context = appContext,
                    phone = phone,
                    text = noteText,
                    direction = direction,
                    callAt = callAt,
                    durationSeconds = durationSeconds,
                    actionIssuedAt = actionIssuedAt,
                    companyId = companyId,
                )
            }
        }
        return if (isGeneralNote) {
            CallNoteWriter.writeGeneral(appContext, phone, noteText, syncToCrm = !localOnlyFallback)
        } else {
            CallNoteWriter.writeCallOrGeneral(
                context = appContext,
                phone = phone,
                text = noteText,
                direction = direction,
                callAt = callAt,
                durationSeconds = durationSeconds,
                actionIssuedAt = actionIssuedAt,
                syncToCrm = !localOnlyFallback,
            )
        }
    }

    private fun shouldShowTopicSelector(): Boolean {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(appContext))) return false
        return CrmContactSyncStore.isEnabled(appContext, phone) || shouldAutoEnableServerSyncForUnknown()
    }

    private fun shouldAutoEnableServerSyncForUnknown(): Boolean {
        if (!CallReportRemoteAccess.isReady(ConfigStore.load(appContext))) return false
        if (CrmContactSyncStore.isEnabled(appContext, phone)) return false
        return !hasDefaultContact()
    }

    private fun hasDefaultContact(): Boolean {
        if (phone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return RmRealContactLookup.findContactId(appContext, phone) > 0L
    }

    private companion object {
        const val TOPIC_REQUEST_FAILED = "topic_request_failed"
    }
}

internal data class CallNoteFormSaveOutcome(
    val saved: Boolean = false,
    val topicRequired: Boolean = false,
    val savedAsGeneralNote: Boolean = false,
    val target: CallNoteTarget = CallNoteTarget("", 0L, 0L),
    val localOnlyFallback: Boolean = false,
    val serverSyncActivationAttempted: Boolean = false,
    val serverSyncEnabled: Boolean = false,
)
