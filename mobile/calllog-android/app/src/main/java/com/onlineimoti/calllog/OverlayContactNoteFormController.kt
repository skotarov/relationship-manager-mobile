package com.onlineimoti.calllog

import android.app.Service
import android.os.Handler
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

    fun addTopicFieldTo(container: LinearLayout) {
        topicFieldUi.create(
            state = topicState,
            onSelected = { selected -> topicState = topicState.copy(selectedCompanyId = selected) },
            onSpinnerReady = { spinner -> topicSpinner = spinner },
        )?.let(container::addView)
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
                    }
                }
            }
        }.start()
    }
}
