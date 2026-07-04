package com.onlineimoti.calllog

import android.content.Intent
import android.provider.ContactsContract
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity

/** Keeps HomeActivity focused on state coordination rather than menu plumbing. */
internal object HomeOverflowMenu {
    fun show(activity: AppCompatActivity, anchor: View, openSettings: () -> Unit) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, MENU_PHONE_CALL_LOG, 10, activity.getString(R.string.home_overflow_phone_log))
            menu.add(0, MENU_CONTACTS, 20, "Контакти")
            menu.add(0, MENU_SMS, 30, "SMS")
            menu.add(0, MENU_SETTINGS, 40, activity.getString(R.string.home_overflow_settings))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_PHONE_CALL_LOG -> {
                        activity.startActivity(
                            Intent(activity, SystemCallHistoryActivity::class.java)
                                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
                        )
                        true
                    }
                    MENU_CONTACTS -> {
                        openDefaultContacts(activity)
                        true
                    }
                    MENU_SMS -> {
                        activity.startActivity(Intent(activity, SmsHistoryActivity::class.java))
                        true
                    }
                    MENU_SETTINGS -> {
                        openSettings()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun openDefaultContacts(activity: AppCompatActivity) {
        val contactsIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
        val fallbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
        runCatching { activity.startActivity(contactsIntent) }
            .recoverCatching { activity.startActivity(fallbackIntent) }
    }

    private const val MENU_PHONE_CALL_LOG = 1
    private const val MENU_CONTACTS = 2
    private const val MENU_SMS = 3
    private const val MENU_SETTINGS = 4
}
