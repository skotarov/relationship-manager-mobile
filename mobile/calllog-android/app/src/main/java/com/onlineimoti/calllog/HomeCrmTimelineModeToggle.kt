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
        visibleInOverflow = visible && !DistributionCapabilities.isPlayBusinessBuild
        this.contactsMode = contactsMode
        if (contactsMode) {
            binding.crmContactsTitleText.text = activity.getString(R.string.runtime_crm_clients)
        } else if (!DistributionCapabilities.isPlayBusinessBuild && HomeCrmModeStore.isEnabled(activity)) {
            // CRM calls is a separate page: replace the wordmark with the shared back header.
            HomeScreenActionBinder.updateBrandShortcutVisibility(binding, false)
            binding.crmContactsHeader.visibility = View.VISIBLE
            binding.crmContactsTitleText.text = activity.getString(R.string.runtime_menu_crm_calls)
        }
        binding.homeStatusText.visibility = View.VISIBLE
    }

    /** Paging remains available through the bottom controls; the range label is hidden. */
    fun showRange(contactsMode: Boolean, pageIndex: Int, pageSize: Int, itemCount: Int) {
        prepare(true, contactsMode)
        if (itemCount <= 0) return
        binding.homeStatusText.text = ""
        binding.homeStatusText.visibility = View.GONE
    }

    /** Empty/loading CRM messages are rendered in the result area, not before firm buttons. */
    fun showEmpty(contactsMode: Boolean) {
        prepare(true, contactsMode)
        binding.homeStatusText.text = ""
        binding.homeStatusText.visibility = View.GONE
    }

    companion object {
        private var activeInstance: WeakReference<HomeCrmTimelineModeToggle>? = null
        fun isOverflowActionVisible() = activeInstance?.get()?.visibleInOverflow == true
        fun isContactsMode() = activeInstance?.get()?.contactsMode == true
        fun toggleFromOverflow() { activeInstance?.get()?.takeIf { it.visibleInOverflow }?.onToggle?.invoke() }
    }
}
