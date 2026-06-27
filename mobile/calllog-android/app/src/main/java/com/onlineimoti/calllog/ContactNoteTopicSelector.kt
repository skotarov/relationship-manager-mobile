package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner

internal data class ContactNoteTopicState(
    val visible: Boolean,
    val loading: Boolean = false,
    val companies: List<CallReportTopicCompany> = emptyList(),
    val selectedCompanyId: String = "",
    /** Main-note forms expose a local-only option before server companies. */
    val includeLocalOption: Boolean = false,
    /** No server company is available for this form; keep only the Local option visible. */
    val localOnly: Boolean = false,
    /** Non-empty only when the topic request itself failed. Empty companies is a valid server response. */
    val loadError: String = "",
) {
    companion object {
        /** Synthetic selection only; never sent to the server as a company id. */
        const val LOCAL_COMPANY_ID = "__callreport_local__"
    }
}

internal object ContactNoteTopicSelector {
    fun bind(
        context: Context,
        spinner: Spinner,
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
    ) {
        val options = selectableOptions(context, state)
        val hasPlaceholder = !state.loading && state.loadError.isBlank() && !state.localOnly && options.isNotEmpty()
        val labels = when {
            state.loading && state.includeLocalOption -> listOf(context.getString(R.string.note_local_company))
            state.loading -> listOf("Зареждане на теми…")
            state.loadError.isNotBlank() && state.includeLocalOption -> listOf(context.getString(R.string.note_local_company))
            state.loadError.isNotBlank() -> listOf(context.getString(R.string.note_topics_unavailable_local_only))
            state.localOnly -> listOf(context.getString(R.string.note_local_company))
            options.isEmpty() -> listOf("Няма налични теми")
            hasPlaceholder -> listOf("Избери") + options.map { it.label }
            else -> options.map { it.label }
        }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.isEnabled = !state.loading && state.loadError.isBlank() && !state.localOnly && state.companies.isNotEmpty()

        val optionIndex = options.indexOfFirst { it.id == state.selectedCompanyId }
        val selectedIndex = when {
            optionIndex < 0 -> 0
            hasPlaceholder -> optionIndex + 1
            else -> optionIndex
        }
        spinner.setSelection(selectedIndex, false)
        updateValidationBorder(context, spinner, state, state.selectedCompanyId, options.isNotEmpty())
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val optionPosition = if (hasPlaceholder) position - 1 else position
                val selectedCompanyId = options.getOrNull(optionPosition)?.id.orEmpty()
                updateValidationBorder(context, spinner, state, selectedCompanyId, options.isNotEmpty())
                onSelected(selectedCompanyId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateValidationBorder(context, spinner, state, "", options.isNotEmpty())
                onSelected("")
            }
        }
    }

    private fun selectableOptions(context: Context, state: ContactNoteTopicState): List<TopicOption> {
        val serverOptions = state.companies.map { TopicOption(it.id, it.name) }
        return if (state.includeLocalOption) {
            listOf(TopicOption(ContactNoteTopicState.LOCAL_COMPANY_ID, context.getString(R.string.note_local_company))) + serverOptions
        } else {
            serverOptions
        }
    }

    private fun updateValidationBorder(
        context: Context,
        spinner: Spinner,
        state: ContactNoteTopicState,
        selectedCompanyId: String,
        hasOptions: Boolean,
    ) {
        val field = spinner.parent as? LinearLayout ?: return
        if (field.tag != ContactNoteTopicFieldUi.FIELD_TAG) return

        val selectionRequired = !state.loading && state.loadError.isBlank() && !state.localOnly && hasOptions
        val missingSelection = selectionRequired && selectedCompanyId.isBlank()
        val density = context.resources.displayMetrics.density
        val strokeWidth = (if (missingSelection) 2 else 1) * density
        field.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * density
            setColor(Color.WHITE)
            setStroke(
                strokeWidth.toInt(),
                if (missingSelection) Color.rgb(220, 38, 38) else Color.rgb(209, 213, 219),
            )
        }
    }

    private data class TopicOption(
        val id: String,
        val label: String,
    )
}
