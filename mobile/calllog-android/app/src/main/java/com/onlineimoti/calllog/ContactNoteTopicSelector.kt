package com.onlineimoti.calllog

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

internal data class ContactNoteTopicState(
    val visible: Boolean,
    val loading: Boolean = false,
    val companies: List<CallReportTopicCompany> = emptyList(),
    val selectedCompanyId: String = "",
)

internal object ContactNoteTopicSelector {
    fun bind(
        activity: Activity,
        spinner: Spinner,
        state: ContactNoteTopicState,
        onSelected: (String) -> Unit,
    ) {
        val labels = when {
            state.loading -> listOf("Зареждане на теми…")
            state.companies.isEmpty() -> listOf("Няма налични теми")
            else -> listOf("Избери фирма") + state.companies.map { it.name }
        }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.isEnabled = !state.loading && state.companies.isNotEmpty()

        val selectedIndex = state.companies.indexOfFirst { it.id == state.selectedCompanyId }
            .let { if (it >= 0) it + 1 else 0 }
        spinner.setSelection(selectedIndex, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelected(state.companies.getOrNull(position - 1)?.id.orEmpty())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = onSelected("")
        }
    }
}
