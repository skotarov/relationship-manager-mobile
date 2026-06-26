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
    private val syncNotConfirmedBgPattern = Regex("^Синхронизацията не е потвърдена:\\s*(.+)$")
    private val syncNotConfirmedEnPattern = Regex("^Sync not confirmed:\\s*(.+)$")
    private val recordedByBgPattern = Regex("^Записал:\\s*(.+)$")
    private val recordedByEnPattern = Regex("^Recorded by:\\s*(.+)$")

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
            "Бележки и SMS", "Notes and SMS" -> R.string.history_notes_and_sms
            "+ Добави", "+ Add" -> R.string.history_add_note
            "Чака сървърна синхронизация", "Waiting for server sync" -> R.string.history_pending_server_sync
            "По-нова версия на сървъра", "Newer version on the server" -> R.string.history_newer_server_version
            "Бележка", "Note" -> R.string.history_type_note
            "Телефон", "Phone" -> R.string.history_type_phone
            "Зареждам SMS и бележки…", "Loading SMS and notes…" -> R.string.history_loading_notes_sms
            "Добавям сървърни бележки и SMS…", "Loading server notes and SMS…" -> R.string.history_loading_server_notes_sms
            "Няма SMS или бележки за този номер", "No SMS or notes for this number" -> R.string.history_no_notes_or_sms
            "входящ", "incoming" -> R.string.history_direction_incoming
            "изходящ", "outgoing" -> R.string.history_direction_outgoing
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

        (syncNotConfirmedBgPattern.matchEntire(value) ?: syncNotConfirmedEnPattern.matchEntire(value))
            ?.groupValues?.getOrNull(1)
            ?.let { failure ->
                view.text = activity.getString(R.string.history_sync_not_confirmed, failure)
                return
            }

        (recordedByBgPattern.matchEntire(value) ?: recordedByEnPattern.matchEntire(value))
            ?.groupValues?.getOrNull(1)
            ?.let { author ->
                view.text = activity.getString(R.string.history_recorded_by, author)
                return
            }

        (propertyBgPattern.matchEntire(value) ?: propertyEnPattern.matchEntire(value))
            ?.groupValues?.getOrNull(1)
            ?.let { title ->
                view.text = activity.getString(R.string.crm_history_property, title)
            }
    }
}
