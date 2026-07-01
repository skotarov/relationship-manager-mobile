package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** One compact dropdown for the four company-scoped CRM phases. */
internal class HomeCrmFiltersController(
    private val activity: HomeActivity,
    private val binding: ActivityHomeBinding,
    @Suppress("UNUSED_PARAMETER") handler: Handler,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val onFilterChanged: () -> Unit,
) {
    private var state = HomeCrmFilterStore.load(activity)

    fun state(): HomeCrmFilterState = state

    fun hasActiveFilters(): Boolean = state.isActive

    fun updateVisibility(crmModeEnabled: Boolean) {
        binding.crmFiltersScroll.visibility = if (crmModeEnabled) View.VISIBLE else View.GONE
        if (crmModeEnabled) render()
    }

    /** Retained for the Home lifecycle; phase options are static and require no separate preload. */
    fun refreshCompaniesIfNeeded(force: Boolean = false) {
        if (force && HomeCrmModeStore.isEnabled(activity)) render()
    }

    fun release() = Unit

    private fun render() {
        if (!HomeCrmModeStore.isEnabled(activity)) return
        val container = binding.crmFiltersContainer
        container.removeAllViews()
        addPhaseDropdown(container)
    }

    private fun addPhaseDropdown(container: LinearLayout) {
        val selected = state.isActive
        val fill = if (selected) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (selected) Color.TRANSPARENT else Color.rgb(203, 213, 225)
        container.addView(TextView(activity).apply {
            text = phaseText() + " ▾"
            contentDescription = text
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            textSize = 13f
            setTextColor(if (selected) Color.WHITE else Color.rgb(51, 65, 85))
            background = roundedRect(fill, dp(18), border, if (selected) 0 else dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener(::showPhaseMenu)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
    }

    private fun showPhaseMenu(anchor: View) {
        val options = listOf(
            ContactNegotiationPhaseStore.NONE to R.string.crm_filter_phase_all,
            ContactNegotiationPhaseStore.PHASE_1 to R.string.contact_phase_1,
            ContactNegotiationPhaseStore.PHASE_2 to R.string.contact_phase_2,
            ContactNegotiationPhaseStore.PHASE_3 to R.string.contact_phase_3,
            ContactNegotiationPhaseStore.PHASE_4 to R.string.contact_phase_4,
        )
        PopupMenu(activity, anchor).apply {
            menu.setGroupCheckable(PHASE_MENU_GROUP, true, true)
            options.forEachIndexed { index, (phase, titleRes) ->
                menu.add(PHASE_MENU_GROUP, PHASE_MENU_BASE + phase, index, activity.getString(titleRes)).apply {
                    isCheckable = true
                    isChecked = phase == state.phase
                }
            }
            setOnMenuItemClickListener { item ->
                val phase = item.itemId - PHASE_MENU_BASE
                if (phase !in ContactNegotiationPhaseStore.NONE..ContactNegotiationPhaseStore.PHASE_4) {
                    return@setOnMenuItemClickListener false
                }
                updateState(HomeCrmFilterState(phase = phase))
                true
            }
            show()
        }
    }

    private fun updateState(next: HomeCrmFilterState) {
        if (next == state) return
        state = next
        HomeCrmFilterStore.save(activity, state)
        render()
        onFilterChanged()
    }

    private fun phaseText(): String = when (state.phase) {
        ContactNegotiationPhaseStore.PHASE_1 -> activity.getString(
            R.string.crm_filter_phase_named,
            activity.getString(R.string.contact_phase_1),
        )
        ContactNegotiationPhaseStore.PHASE_2 -> activity.getString(
            R.string.crm_filter_phase_named,
            activity.getString(R.string.contact_phase_2),
        )
        ContactNegotiationPhaseStore.PHASE_3 -> activity.getString(
            R.string.crm_filter_phase_named,
            activity.getString(R.string.contact_phase_3),
        )
        ContactNegotiationPhaseStore.PHASE_4 -> activity.getString(
            R.string.crm_filter_phase_named,
            activity.getString(R.string.contact_phase_4),
        )
        else -> activity.getString(R.string.crm_filter_phase_all)
    }

    private companion object {
        const val PHASE_MENU_GROUP = 20
        const val PHASE_MENU_BASE = 1_000
    }
}
