package com.onlineimoti.calllog

import android.app.Service
import android.os.Handler
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast

/** Bridges the shared note workflow into the floating overlay editors. */
internal class OverlayContactNoteFormController(
    private val service: Service,
    private val handler: Handler,
    private val dp: (Int) -> Int,
    private val draft: ContactNoteFormDraft,
) {
    private val topicFieldUi by lazy { ContactNoteTopicFieldUi(service, dp) }
    private var topicState = ContactNoteFormWorkflow.initialTopicState(service, draft)
    private var topicSpinner: Spinner? = null
    private var noteInput: EditText? = null
    private var serverScopeTexts: Map<String, String>? = null
    private var serverScopeTextLoading = false
    private var displayedScopeId = ""
    private var displayedScopeText = ""

    fun addTopicFieldTo(container: LinearLayout, input: EditText) {
        noteInput = input
        topicFieldUi.create(
            state = topicState,
            onSelected = { selected ->
                topicState = topicState.copy(selectedCompanyId = selected)
                if (draft.isGeneralNote) refreshTextForScope(selected)
            },
            onSpinnerReady = { spinner -> topicSpinner = spinner },
        )?.let(container::addView)
        if (draft.isGeneralNote) refreshTextForScope(topicState.selectedCompanyId)
        if (topicState.visible) loadTopics()
    }

    fun save(noteText: String): ContactNoteFormSaveResult? {
        val topicId = ContactNoteFormWorkflow.selectedTopicOrLocalFallback(topicState) ?: run {
            Toast.makeText(service, service.getString(R.string.note_company_required), Toast.LENGTH_SHORT).show()
            return null
        }
        return ContactNoteFormWorkflow.save(
            context = service,
            draft = draft,
            noteText = noteText,
            topicCompanyId = topicId,
            localOnlyFallback = topicState.loadError.isNotBlank(),
        )
    }

    private fun loadTopics() {
        val stateAtStart = topicState
        Thread {
            val loadedState = ContactNoteFormWorkflow.loadTopics(service.applicationContext, stateAtStart)
            handler.post {
                topicState = loadedState
                topicSpinner?.let { spinner ->
                    ContactNoteTopicSelector.bind(service, spinner, topicState) { selected ->
                        topicState = topicState.copy(selectedCompanyId = selected)
                        if (draft.isGeneralNote) refreshTextForScope(selected)
                    }
                }
                if (draft.isGeneralNote) refreshTextForScope(topicState.selectedCompanyId)
            }
        }.start()
    }

    private fun refreshTextForScope(companyId: String) {
        val input = noteInput ?: return
        val safeCompanyId = companyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val value = ContactNoteScopeTextResolver.textFor(
            companyId = safeCompanyId,
            draft = draft,
            serverTexts = serverScopeTexts,
            context = service,
        )
        replaceInputText(input, safeCompanyId, value)
        if (safeCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID && serverScopeTexts == null) {
            loadServerScopeTexts()
        }
    }

    private fun loadServerScopeTexts() {
        if (serverScopeTextLoading) return
        serverScopeTextLoading = true
        Thread {
            val values = runCatching {
                ContactNoteScopeTextResolver.loadServerTexts(service.applicationContext, draft)
            }.getOrNull()
            handler.post {
                serverScopeTextLoading = false
                if (values == null) return@post
                serverScopeTexts = values
                val input = noteInput ?: return@post
                val selectedCompanyId = topicState.selectedCompanyId
                if (
                    selectedCompanyId.isNotBlank() &&
                    selectedCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
                    displayedScopeId == selectedCompanyId &&
                    input.text?.toString().orEmpty() == displayedScopeText
                ) {
                    refreshTextForScope(selectedCompanyId)
                }
            }
        }.start()
    }

    private fun replaceInputText(input: EditText, companyId: String, value: String) {
        if (input.text?.toString().orEmpty() != value) {
            input.setText(value)
            input.setSelection(input.text?.length ?: 0)
        }
        displayedScopeId = companyId
        displayedScopeText = value
    }
}
