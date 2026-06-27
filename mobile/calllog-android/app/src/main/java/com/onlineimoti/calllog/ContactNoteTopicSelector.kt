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
    /** Non-empty only when the topic request itself failed. Empty companies is a valid server response. */
    val loadError: String = "",
)

internal object ContactNoteTopicSelector {
    fun bind(
        context: Context,
        spinner: Spinner,
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
    ) {
        val labels = when {
            state.loading -> listOf("Зареждане на теми…")
            state.loadError.isNotBlank() -> listOf(context.getString(R.string.note_topics_unavailable_local_only))
            state.companies.isEmpty() -> listOf("Няма налични теми")
            else -> listOf("Избери") + state.companies.map { it.name }
        }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.isEnabled = !state.loading && state.loadError.isBlank() && state.companies.isNotEmpty()

        val selectedIndex = state.companies.indexOfFirst { it.id == state.selectedCompanyId }
            .let { if (it >= 0) it + 1 else 0 }
        spinner.setSelection(selectedIndex, false)
        updateValidationBorder(context, spinner, state, state.selectedCompanyId)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedCompanyId = state.companies.getOrNull(position - 1)?.id.orEmpty()
                updateValidationBorder(context, spinner, state, selectedCompanyId)
                onSelected(selectedCompanyId)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateValidationBorder(context, spinner, state, "")
                onSelected("")
            }
        }
    }

    private fun updateValidationBorder(
        context: Context,
        spinner: Spinner,
        state: ContactNoteTopicState,
        selectedCompanyId: String,
    ) {
        val field = spinner.parent as? LinearLayout ?: return
        if (field.tag != ContactNoteTopicFieldUi.FIELD_TAG) return

        val selectionRequired = !state.loading && state.loadError.isBlank() && state.companies.isNotEmpty()
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
}
