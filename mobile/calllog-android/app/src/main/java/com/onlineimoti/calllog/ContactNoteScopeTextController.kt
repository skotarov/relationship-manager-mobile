package com.onlineimoti.calllog

import android.app.Activity
import android.widget.EditText
import java.util.concurrent.ExecutorService

/** Loads and switches the text of a general note between local and company scopes. */
internal class ContactNoteScopeTextController(
    private val activity: Activity,
    private val executor: ExecutorService,
    private val draft: () -> ContactNoteFormDraft,
    private val selectedCompanyId: () -> String,
    private val noteInput: () -> EditText?,
    private val isActive: () -> Boolean,
    private val onTextApplied: (companyId: String, value: String) -> Unit,
) {
    private var serverTexts: Map<String, String>? = null
    private var loading = false
    private var displayedScopeId = ""
    private var displayedScopeText = ""

    fun refresh(companyId: String, input: EditText) {
        val safeCompanyId = companyId.ifBlank { ContactNoteTopicState.LOCAL_COMPANY_ID }
        val value = ContactNoteScopeTextResolver.textFor(
            companyId = safeCompanyId,
            draft = draft(),
            serverTexts = serverTexts,
            context = activity,
        )
        replaceInputText(input, safeCompanyId, value)
        if (safeCompanyId != ContactNoteTopicState.LOCAL_COMPANY_ID && serverTexts == null) loadServerTexts()
    }

    private fun loadServerTexts() {
        if (loading) return
        loading = true
        executor.execute {
            val values = runCatching {
                ContactNoteScopeTextResolver.loadServerTexts(activity.applicationContext, draft())
            }.getOrNull()
            activity.runOnUiThread {
                if (!isActive()) return@runOnUiThread
                loading = false
                if (values == null) return@runOnUiThread
                serverTexts = values
                val input = noteInput() ?: return@runOnUiThread
                val selectedId = selectedCompanyId()
                if (
                    selectedId.isNotBlank() &&
                    selectedId != ContactNoteTopicState.LOCAL_COMPANY_ID &&
                    displayedScopeId == selectedId &&
                    input.text?.toString().orEmpty() == displayedScopeText
                ) {
                    refresh(selectedId, input)
                }
            }
        }
    }

    private fun replaceInputText(input: EditText, companyId: String, value: String) {
        if (input.text?.toString().orEmpty() != value) {
            input.setText(value)
            input.setSelection(input.text?.length ?: 0)
        }
        displayedScopeId = companyId
        displayedScopeText = value
        onTextApplied(companyId, value)
    }
}
