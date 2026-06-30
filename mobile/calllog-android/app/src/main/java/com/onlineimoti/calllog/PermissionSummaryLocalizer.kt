package com.onlineimoti.calllog

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.onlineimoti.calllog.databinding.ActivityMainBinding

/**
 * Keeps the existing permission-state renderer intact and translates its dynamic rows after render.
 * The rows were originally assembled from hardcoded labels, so this layer ensures they follow the
 * app language just like XML resources do.
 */
internal object PermissionSummaryLocalizer {
    fun apply(activity: MainActivity, binding: ActivityMainBinding) {
        val root = binding.settingsApplicationGroup.permissionsSection.permissionsSummaryText.parent as? ViewGroup ?: return
        localizeTree(activity, root)
    }

    private fun localizeTree(activity: MainActivity, view: View) {
        when (view) {
            is TextView -> localizeText(activity, view)
            is ViewGroup -> repeat(view.childCount) { index -> localizeTree(activity, view.getChildAt(index)) }
        }
    }

    private fun localizeText(activity: MainActivity, view: TextView) {
        val current = view.text?.toString().orEmpty().trim()
        if (current.isBlank()) return

        when (current) {
            "Включи", "Enable" -> {
                view.text = activity.getString(R.string.permission_action_enable)
                return
            }
            "Изключи", "Disable" -> {
                view.text = activity.getString(R.string.permission_action_disable)
                return
            }
        }

        val separator = current.indexOf(": ")
        if (separator <= 0) return
        val label = current.substring(0, separator)
        val state = current.substring(separator + 2)
        val localizedLabel = labelRes(label)?.let(activity::getString) ?: return
        val localizedState = stateRes(state)?.let(activity::getString) ?: return
        view.text = "$localizedLabel: $localizedState"
    }

    private fun labelRes(value: String): Int? = when (value) {
        "Notifications", "Известия" -> R.string.permission_label_notifications
        "Phone", "Телефон" -> R.string.permission_label_phone
        "Call report log", "Call log", "Дневник на разговорите" -> R.string.permission_label_call_log
        "Contacts read", "Четене на контакти" -> R.string.permission_label_contacts_read
        "Contacts write", "Запис в контакти" -> R.string.permission_label_contacts_write
        "Public notes storage", "Shared notes storage", "Общо съхранение на бележките" -> R.string.permission_label_public_notes_storage
        "Private notes storage", "Частно съхранение на бележките" -> R.string.permission_label_private_notes_storage
        "Popup над други приложения", "Display over other apps", "Показване над други приложения" -> R.string.permission_label_overlay
        "Call screening", "Филтриране на обажданията" -> R.string.permission_label_call_screening
        "Default SMS", "SMS приложение по подразбиране" -> R.string.permission_label_default_sms
        "SMS receive", "Получаване на SMS" -> R.string.permission_label_sms_receive
        "SMS read", "Четене на SMS" -> R.string.permission_label_sms_read
        "SMS send", "Изпращане на SMS" -> R.string.permission_label_sms_send
        "Full-screen popup", "Popup на цял екран" -> R.string.permission_label_fullscreen_popup
        else -> null
    }

    private fun stateRes(value: String): Int? = when (value.lowercase()) {
        "active", "активно" -> R.string.permission_state_active
        "missing", "липсва" -> R.string.permission_state_missing
        "disabled", "изключено" -> R.string.permission_state_disabled
        else -> null
    }
}
