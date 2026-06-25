package com.onlineimoti.calllog

import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeNavigationController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val currentFilter: () -> String,
    private val setFilter: (String) -> Unit,
    private val filterKey: (String) -> String,
    private val onFilterChanged: () -> Unit,
    private val onOpenSystemHistory: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    fun toggleFilter(value: String) {
        val current = currentFilter()
        val next = if (current.isNotBlank() && filterKey(current) == filterKey(value)) "" else value
        setFilter(next)
        onFilterChanged()
    }

    fun clearFilter() {
        if (currentFilter().isBlank()) return
        setFilter("")
        onFilterChanged()
    }

    fun isFilterOnly(searchQuery: String): Boolean {
        return currentFilter().isNotBlank() && searchQuery.isBlank()
    }

    fun showOverflowMenu() {
        PopupMenu(activity, binding.settingsButton).apply {
            menu.add(0, MENU_SYSTEM_HISTORY, 0, activity.getString(R.string.home_overflow_phone_log))
            menu.add(0, MENU_SETTINGS, 1, activity.getString(R.string.home_overflow_settings))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SYSTEM_HISTORY -> {
                        onOpenSystemHistory()
                        true
                    }
                    MENU_SETTINGS -> {
                        onOpenSettings()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private companion object {
        const val MENU_SYSTEM_HISTORY = 1
        const val MENU_SETTINGS = 2
    }
}
