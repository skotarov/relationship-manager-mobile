package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/**
 * Keeps the CRM calls/contacts range visible without placing another control in
 * the already crowded Home header. The actual mode switch now lives in the
 * three-dot overflow menu.
 */
internal class HomeCrmTimelineModeToggle(
    private val binding: ActivityHomeBinding,
) {
    fun prepare(visible: Boolean, contactsMode: Boolean) {
        if (!visible) {
            binding.homeStatusText.visibility = View.VISIBLE
            return
        }
        binding.homeStatusText.visibility = View.VISIBLE
    }

    fun showRange(contactsMode: Boolean, pageIndex: Int, pageSize: Int, itemCount: Int) {
        prepare(visible = true, contactsMode = contactsMode)
        if (itemCount <= 0) return
        val first = pageIndex * pageSize + 1
        val last = first + itemCount - 1
        binding.homeStatusText.text = "${modeLabel(contactsMode)} $first–$last"
        binding.homeStatusText.visibility = View.VISIBLE
    }

    fun showEmpty(contactsMode: Boolean) {
        prepare(visible = true, contactsMode = contactsMode)
        binding.homeStatusText.visibility = View.VISIBLE
    }

    private fun modeLabel(contactsMode: Boolean): String = when {
        contactsMode && AppLocaleText.isBulgarian() -> "Контакти"
        contactsMode -> "Contacts"
        AppLocaleText.isBulgarian() -> "Разговори"
        else -> "Calls"
    }
}
