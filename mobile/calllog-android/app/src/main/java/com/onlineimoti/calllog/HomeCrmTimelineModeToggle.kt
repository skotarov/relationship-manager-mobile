package com.onlineimoti.calllog

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.lang.ref.WeakReference

/**
 * Keeps the CRM calls/contacts range visible without placing another control in
 * the already crowded Home header. The actual mode switch now lives in the
 * three-dot overflow menu.
 */
internal class HomeCrmTimelineModeToggle(
    @Suppress("unused") private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    @Suppress("unused") private val dp: (Int) -> Int,
    private val onToggle: () -> Unit,
) {
    private var visibleInOverflow = false
    private var contactsMode = false

    init {
        activeInstance = WeakReference(this)
    }

    fun prepare(visible: Boolean, contactsMode: Boolean) {
        visibleInOverflow = visible
        this.contactsMode = contactsMode
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

    companion object {
        private var activeInstance: WeakReference<HomeCrmTimelineModeToggle>? = null

        fun isOverflowActionVisible(): Boolean = activeInstance?.get()?.visibleInOverflow == true

        fun isContactsMode(): Boolean = activeInstance?.get()?.contactsMode == true

        fun toggleFromOverflow() {
            activeInstance?.get()
                ?.takeIf { it.visibleInOverflow }
                ?.onToggle
                ?.invoke()
        }
    }
}
