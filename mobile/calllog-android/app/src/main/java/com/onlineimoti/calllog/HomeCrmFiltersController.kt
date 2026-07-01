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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One compact horizontal filter bar, shown only while CRM Mode is active. */
internal class HomeCrmFiltersController(
    private val activity: HomeActivity,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val onFilterChanged: () -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    private var state = HomeCrmFilterStore.load(activity)
    private var companies: List<CallReportTopicCompany> = emptyList()
    private var lastRequestedAccount = ""

    fun state(): HomeCrmFilterState = state

    fun hasActiveFilters(): Boolean = state.isActive

    fun updateVisibility(crmModeEnabled: Boolean) {
        binding.crmFiltersScroll.visibility = if (crmModeEnabled) View.VISIBLE else View.GONE
        if (!crmModeEnabled) return
        loadCachedCompanies()
        render()
        refreshCompaniesIfNeeded()
    }

    fun refreshCompaniesIfNeeded(force: Boolean = false) {
        val config = ConfigStore.load(activity.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return
        val account = "${config.baseUrl}|${config.accessToken}"
        if (!force && account == lastRequestedAccount) return
        lastRequestedAccount = account
        val requestGeneration = generation.incrementAndGet()
        executor.execute {
            val loaded = runCatching {
                CallReportTopicCompaniesRepository.load(activity.applicationContext, config).companies
            }.getOrNull() ?: return@execute
            handler.post {
                if (requestGeneration != generation.get() || activity.isFinishing || activity.isDestroyed) return@post
                if (companies == loaded) return@post
                companies = loaded
                render()
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun loadCachedCompanies() {
        val config = ConfigStore.load(activity.applicationContext)
        val cached = CallReportTopicCompaniesCache.read(activity.applicationContext, config)?.companies.orEmpty()
        if (cached.isNotEmpty() && cached != companies) companies = cached
    }

    private fun render() {
        if (!HomeCrmModeStore.isEnabled(activity)) return
        val container = binding.crmFiltersContainer
        container.removeAllViews()
        addChip(
            container = container,
            text = activity.getString(R.string.crm_filter_all),
            selected = state.contactScope == HomeCrmContactScope.ALL,
        ) { updateState(state.copy(contactScope = HomeCrmContactScope.ALL)) }
        addChip(
            container = container,
            text = activity.getString(R.string.crm_filter_unknown),
            selected = state.contactScope == HomeCrmContactScope.UNKNOWN,
        ) { updateState(state.copy(contactScope = HomeCrmContactScope.UNKNOWN)) }
        addChip(
            container = container,
            text = activity.getString(R.string.crm_filter_known),
            selected = state.contactScope == HomeCrmContactScope.KNOWN,
        ) { updateState(state.copy(contactScope = HomeCrmContactScope.KNOWN)) }
        addChip(
            container = container,
            text = companyChipText(),
            selected = state.isCompanyFiltered,
        ) { chip -> showCompanyMenu(chip) }
        addChip(
            container = container,
            text = directionChipText(),
            selected = state.directionScope != HomeCrmDirectionScope.ALL,
        ) { chip -> showDirectionMenu(chip) }
        addChip(
            container = container,
            text = activity.getString(R.string.crm_filter_pending),
            selected = state.pendingOnly,
        ) { updateState(state.copy(pendingOnly = !state.pendingOnly)) }
    }

    private fun showCompanyMenu(anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, COMPANY_ALL_ID, 0, activity.getString(R.string.crm_filter_company_all))
            menu.add(0, COMPANY_NONE_ID, 1, activity.getString(R.string.crm_filter_company_none))
            if (companies.isNotEmpty()) {
                menu.add(0, COMPANY_DIVIDER_ID, 2, activity.getString(R.string.crm_filter_company_choose)).isEnabled = false
            }
            val ids = linkedMapOf<Int, String>()
            companies.forEachIndexed { index, company ->
                val itemId = COMPANY_FIRST_ID + index
                ids[itemId] = company.id
                menu.add(0, itemId, index + 3, company.name)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    COMPANY_ALL_ID -> updateState(state.copy(companyId = ""))
                    COMPANY_NONE_ID -> updateState(state.copy(companyId = HomeCrmFilterState.NO_COMPANY_ID))
                    else -> ids[item.itemId]?.let { companyId -> updateState(state.copy(companyId = companyId)) }
                }
                true
            }
            show()
        }
    }

    private fun showDirectionMenu(anchor: View) {
        PopupMenu(activity, anchor).apply {
            val options = listOf(
                HomeCrmDirectionScope.ALL to R.string.crm_filter_direction_all,
                HomeCrmDirectionScope.INCOMING to R.string.crm_filter_direction_incoming,
                HomeCrmDirectionScope.OUTGOING to R.string.crm_filter_direction_outgoing,
                HomeCrmDirectionScope.MISSED to R.string.crm_filter_direction_missed,
                HomeCrmDirectionScope.SMS to R.string.crm_filter_direction_sms,
            )
            options.forEachIndexed { index, (scope, textRes) ->
                menu.add(0, DIRECTION_FIRST_ID + index, index, activity.getString(textRes)).apply {
                    isCheckable = true
                    isChecked = scope == state.directionScope
                }
            }
            setOnMenuItemClickListener { item ->
                val scope = options.getOrNull(item.itemId - DIRECTION_FIRST_ID)?.first ?: return@setOnMenuItemClickListener false
                updateState(state.copy(directionScope = scope))
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

    private fun companyChipText(): String {
        val selected = state.companyId.trim()
        return when {
            selected.isBlank() -> activity.getString(R.string.crm_filter_company_all)
            selected == HomeCrmFilterState.NO_COMPANY_ID -> activity.getString(R.string.crm_filter_company_none)
            else -> {
                val company = companies.firstOrNull { it.id == selected }?.name.orEmpty().ifBlank { selected }
                activity.getString(R.string.crm_filter_company_named, company)
            }
        }
    }

    private fun directionChipText(): String = activity.getString(
        when (state.directionScope) {
            HomeCrmDirectionScope.ALL -> R.string.crm_filter_direction_all
            HomeCrmDirectionScope.INCOMING -> R.string.crm_filter_direction_incoming
            HomeCrmDirectionScope.OUTGOING -> R.string.crm_filter_direction_outgoing
            HomeCrmDirectionScope.MISSED -> R.string.crm_filter_direction_missed
            HomeCrmDirectionScope.SMS -> R.string.crm_filter_direction_sms
        },
    )

    private fun addChip(
        container: LinearLayout,
        text: String,
        selected: Boolean,
        onClick: (View) -> Unit,
    ) {
        val fill = if (selected) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (selected) Color.TRANSPARENT else Color.rgb(203, 213, 225)
        container.addView(TextView(activity).apply {
            this.text = text
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            textSize = 13f
            setTextColor(if (selected) Color.WHITE else Color.rgb(51, 65, 85))
            background = roundedRect(fill, dp(18), border, if (selected) 0 else dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(7) }
        })
    }

    private companion object {
        const val COMPANY_ALL_ID = 1
        const val COMPANY_NONE_ID = 2
        const val COMPANY_DIVIDER_ID = 3
        const val COMPANY_FIRST_ID = 100
        const val DIRECTION_FIRST_ID = 500
    }
}
