package com.onlineimoti.calllog

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Translates legacy dynamic CRM-history rows that are still assembled as plain text. */
internal object CrmHistoryTextLocalizer {
    private val hiddenCallsPattern = Regex("(?:Скрити са|Hidden)\\s+(\\d+).*")
    private val addNoteBgPattern = Regex("^\\+ Добави бележка към (.+)$")
    private val addNoteEnPattern = Regex("^\\+ Add a note to (.+)$")
    private val propertyBgPattern = Regex("^Обява:\\s*(.+)$")
    private val propertyEnPattern = Regex("^Property:\\s*(.+)$")

    fun apply(activity: Activity, root: View) {
        localizeNode(activity, root)
    }

    private fun localizeNode(activity: Activity, view: View) {
        when (view) {
            is TextView -> localizeText(activity, view)
            is ViewGroup -> repeat(view.childCount) { index -> localizeNode(activity, view.getChildAt(index)) }
        }
    }

    private fun localizeText(activity: Activity, view: TextView) {
        val value = view.text?.toString().orEmpty().trim()
        if (value.isBlank()) return

        val direct = when (value) {
            "Няма телефон за CRM проверка", "No phone number for CRM lookup" -> R.string.crm_history_no_phone
            "CRM връзката е изключена от Server настройките", "CRM connection is disabled in Server settings" -> R.string.crm_history_connection_disabled
            "Хронология", "History" -> R.string.crm_history_title
            "пълен лог", "Full log" -> R.string.crm_history_full_log
            "Зареждам разговори и SMS…", "Loading calls and SMS…" -> R.string.crm_history_loading_local
            "Обновявам разговори и SMS…", "Refreshing calls and SMS…" -> R.string.crm_history_refreshing_local
            "Зареждам CRM история…", "Loading CRM history…" -> R.string.crm_history_loading_server
            "CRM историята не е заредена", "CRM history was not loaded" -> R.string.crm_history_not_loaded
            "Няма бележки, SMS или CRM записи за този номер", "No notes, SMS or CRM entries for this number" -> R.string.crm_history_no_entries
            "Няма CRM записи от сървъра за този номер", "No CRM entries from the server for this number" -> R.string.crm_history_no_server_entries
            "последен разговор без бележка", "latest call without note" -> R.string.crm_history_latest_without_note
            "локална бележка", "local note" -> R.string.crm_history_local_note
            else -> null
        }
        if (direct != null) {
            view.text = activity.getString(direct)
            return
        }

        hiddenCallsPattern.matchEntire(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { count ->
            view.text = activity.getString(R.string.crm_history_hidden_calls, count)
            return
        }

        (addNoteBgPattern.matchEntire(value) ?: addNoteEnPattern.matchEntire(value))
            ?.groupValues?.getOrNull(1)
            ?.let { tail ->
                view.text = if (tail.equals("последния разговор", ignoreCase = true) || tail.equals("the latest call", ignoreCase = true)) {
                    activity.getString(R.string.crm_history_add_latest)
                } else {
                    activity.getString(R.string.crm_history_add_note_at, tail)
                }
                return
            }

        (propertyBgPattern.matchEntire(value) ?: propertyEnPattern.matchEntire(value))
            ?.groupValues?.getOrNull(1)
            ?.let { title ->
                view.text = activity.getString(R.string.crm_history_property, title)
            }
    }
}
