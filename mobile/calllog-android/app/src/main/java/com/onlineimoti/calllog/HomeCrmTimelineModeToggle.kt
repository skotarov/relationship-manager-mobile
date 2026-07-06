package com.onlineimoti.calllog

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.lang.ref.WeakReference

internal class HomeCrmTimelineModeToggle(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
    private val onToggle: () -> Unit,
) {
    private var visibleInOverflow = false
    private var contactsMode = false

    init { activeInstance = WeakReference(this) }

    fun prepare(visible: Boolean, contactsMode: Boolean) {
        visibleInOverflow = visible
        this.contactsMode = contactsMode
        if (contactsMode) binding.crmContactsTitleText.text = activity.getString(R.string.runtime_crm_clients)
        binding.homeStatusText.visibility = View.VISIBLE
    }

    fun showRange(contactsMode: Boolean, pageIndex: Int, pageSize: Int, itemCount: Int) {
        prepare(true, contactsMode)
        if (itemCount <= 0) return
        val first = pageIndex * pageSize + 1
        binding.homeStatusText.text = "${modeLabel(contactsMode)} $first–${first + itemCount - 1}"
        binding.homeStatusText.visibility = View.VISIBLE
    }

    fun showEmpty(contactsMode: Boolean) {
        prepare(true, contactsMode)
        binding.homeStatusText.visibility = View.VISIBLE
    }

    private fun modeLabel(contactsMode: Boolean) = activity.getString(
        if (contactsMode) R.string.runtime_crm_clients else R.string.crm_filter_all,
    )

    companion object {
        private var activeInstance: WeakReference<HomeCrmTimelineModeToggle>? = null
        fun isOverflowActionVisible() = activeInstance?.get()?.visibleInOverflow == true
        fun isContactsMode() = activeInstance?.get()?.contactsMode == true
        fun toggleFromOverflow() { activeInstance?.get()?.takeIf { it.visibleInOverflow }?.onToggle?.invoke() }
    }
}
