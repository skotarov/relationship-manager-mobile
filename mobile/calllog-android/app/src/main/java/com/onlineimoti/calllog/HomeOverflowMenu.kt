package com.onlineimoti.calllog

import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu

/** Keeps HomeActivity focused on state coordination rather than menu plumbing. */
internal object HomeOverflowMenu {
    fun show(activity: AppCompatActivity, anchor: View, openSettings: () -> Unit) {
        PopupMenu(activity, anchor).apply {
            // AppCompat allows consistently visible menu icons across Android skins.
            setForceShowIcon(true)
            val contactsMode = HomeCrmTimelineModeToggle.isContactsMode()
            if (!contactsMode) {
                menu.add(0, MENU_PHONE_CALL_LOG, 10, activity.getString(R.string.home_overflow_phone_log))
                    .setIcon(R.drawable.ic_menu_call_history)
            }
            if (HomeCrmTimelineModeToggle.isOverflowActionVisible() && !contactsMode) {
                menu.add(0, MENU_CRM_TIMELINE, 20, "Клиенти")
                    .setIcon(R.drawable.ic_menu_contacts)
            }
            menu.add(0, MENU_PHONE_CONTACTS, 30, "Телефонни контакти")
                .setIcon(R.drawable.ic_menu_contacts)
            menu.add(0, MENU_SMS, 40, "SMS")
                .setIcon(R.drawable.ic_menu_sms)
            menu.add(0, MENU_CALENDAR, 50, "Календар")
                .setIcon(R.drawable.ic_menu_calendar)
            menu.add(0, MENU_SETTINGS, 60, activity.getString(R.string.home_overflow_settings))
                .setIcon(R.drawable.ic_menu_settings)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_PHONE_CALL_LOG -> {
                        activity.startActivity(
                            Intent(activity, SystemCallHistoryActivity::class.java)
                                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
                        )
                        true
                    }
                    MENU_CRM_TIMELINE -> {
                        HomeCrmTimelineModeToggle.toggleFromOverflow()
                        true
                    }
                    MENU_PHONE_CONTACTS -> {
                        openDefaultContacts(activity)
                        true
                    }
                    MENU_SMS -> {
                        activity.startActivity(Intent(activity, SmsHistoryActivity::class.java))
                        true
                    }
                    MENU_CALENDAR -> {
                        openDefaultCalendar(activity)
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

    /** Opens the system/default calendar directly on today's date. */
    private fun openDefaultCalendar(activity: AppCompatActivity) {
        val todayIntent = Intent(Intent.ACTION_VIEW).setData(
            CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(System.currentTimeMillis().toString())
                .build(),
        )
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        val legacyIntent = Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI)
        val opened = tryStart(activity, todayIntent) ||
            tryStart(activity, launcherIntent) ||
            tryStart(activity, legacyIntent)
        if (!opened) {
            Toast.makeText(activity, "Няма налично приложение за календар", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStart(activity: AppCompatActivity, intent: Intent): Boolean = runCatching {
        activity.startActivity(intent)
        true
    }.getOrDefault(false)

    private const val MENU_PHONE_CALL_LOG = 1
    private const val MENU_CRM_TIMELINE = 2
    private const val MENU_PHONE_CONTACTS = 3
    private const val MENU_SMS = 4
    private const val MENU_CALENDAR = 5
    private const val MENU_SETTINGS = 6
}
