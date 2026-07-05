package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** CRM Home filter controls: multi-select phases and topics. */
internal class HomeCrmFiltersController(
    private val activity: HomeActivity,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    @Suppress("UNUSED_PARAMETER") dp: (Int) -> Int,
    @Suppress("UNUSED_PARAMETER") roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val onFilterChanged: () -> Unit,
) {
    private val companyExecutor = Executors.newSingleThreadExecutor()
    private val companyGeneration = AtomicInteger(0)
    private var state = HomeCrmFilterStore.load(activity)
    private var companies: List<CallReportTopicCompany> = emptyList()
    private var lastRequestedAccount = ""

    init {
        binding.crmPhaseFilterButton.setOnClickListener { showPhaseDialog() }
        binding.crmCompanyFilterButton.setOnClickListener { showCompanyDialog() }
    }

    fun state(): HomeCrmFilterState = state

    fun hasActiveFilters(): Boolean = state.isActive

    fun updateVisibility(crmModeEnabled: Boolean) {
        binding.crmPhaseFilterButton.visibility = if (crmModeEnabled) View.VISIBLE else View.GONE
        binding.crmCompanyFilterButton.visibility = if (crmModeEnabled) View.VISIBLE else View.GONE
        binding.filteredStatusContainer.gravity = if (crmModeEnabled) {
            Gravity.CENTER_VERTICAL or Gravity.END
        } else {
            Gravity.CENTER_VERTICAL
        }
        if (!crmModeEnabled) return
        loadCachedCompanies()
        renderButtons()
        refreshCompaniesIfNeeded()
    }

    fun refreshCompaniesIfNeeded(force: Boolean = false) {
        val config = ConfigStore.load(activity.applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return
        val account = "${config.baseUrl.trim().trimEnd('/')}|${config.accessToken}"
        if (!force && account == lastRequestedAccount) return
        lastRequestedAccount = account
        val requestGeneration = companyGeneration.incrementAndGet()
        companyExecutor.execute {
            val loaded = runCatching {
                CallReportTopicCompaniesRepository.load(activity.applicationContext, config).companies
            }.getOrNull() ?: return@execute
            handler.post {
                if (requestGeneration != companyGeneration.get() || activity.isFinishing || activity.isDestroyed) return@post
                if (companies == loaded) return@post
                companies = loaded
                removeUnavailableCompanySelections()
                renderButtons()
            }
        }
    }

    fun release() {
        companyGeneration.incrementAndGet()
        companyExecutor.shutdownNow()
    }

    private fun loadCachedCompanies() {
        val config = ConfigStore.load(activity.applicationContext)
        val cached = CallReportTopicCompaniesCache.read(activity.applicationContext, config)?.companies.orEmpty()
        if (cached != companies) companies = cached
    }

    private fun showPhaseDialog() {
        val phases = listOf(
            ContactNegotiationPhaseStore.PHASE_1,
            ContactNegotiationPhaseStore.PHASE_2,
            ContactNegotiationPhaseStore.PHASE_3,
            ContactNegotiationPhaseStore.PHASE_4,
        )
        val labels = listOf(activity.getString(R.string.crm_filter_all)) + phases.map(::phaseLabel)
        val selected = state.phases.toMutableSet()
        val checked = BooleanArray(labels.size) { index ->
            if (index == 0) selected.isEmpty() else phases[index - 1] in selected
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.crm_filter_select_phases)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { dialog, which, isChecked ->
                val alert = dialog as? AlertDialog
                when (which) {
                    0 -> {
                        if (isChecked || selected.isEmpty()) {
                            selected.clear()
                            for (index in 1 until labels.size) alert?.listView?.setItemChecked(index, false)
                            alert?.listView?.setItemChecked(0, true)
                        }
                    }
                    else -> {
                        val phase = phases[which - 1]
                        if (isChecked) selected.add(phase) else selected.remove(phase)
                        alert?.listView?.setItemChecked(0, selected.isEmpty())
                    }
                }
            }
            .setNegativeButton(R.string.crm_filter_cancel, null)
            .setPositiveButton(R.string.crm_filter_apply) { _, _ ->
                updateState(state.copy(phases = selected.toSet()))
            }
            .show()
    }

    private fun showCompanyDialog() {
        val available = companies.sortedBy { it.name.lowercase() }
        val labels = listOf(activity.getString(R.string.crm_filter_all)) + available.map { it.name }
        val selected = state.companyIds.toMutableSet()
        val checked = BooleanArray(labels.size) { index ->
            if (index == 0) selected.isEmpty() else available[index - 1].id in selected
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.crm_filter_select_companies)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { dialog, which, isChecked ->
                val alert = dialog as? AlertDialog
                when (which) {
                    0 -> {
                        if (isChecked || selected.isEmpty()) {
                            selected.clear()
                            for (index in 1 until labels.size) alert?.listView?.setItemChecked(index, false)
                            alert?.listView?.setItemChecked(0, true)
                        }
                    }
                    else -> {
                        val companyId = available[which - 1].id
                        if (isChecked) selected.add(companyId) else selected.remove(companyId)
                        alert?.listView?.setItemChecked(0, selected.isEmpty())
                    }
                }
            }
            .setNegativeButton(R.string.crm_filter_cancel, null)
            .setPositiveButton(R.string.crm_filter_apply) { _, _ ->
                updateState(state.copy(companyIds = selected.toSet()))
            }
            .show()
    }

    private fun updateState(next: HomeCrmFilterState) {
        if (next == state) return
        state = next
        HomeCrmFilterStore.save(activity, state)
        renderButtons()
        onFilterChanged()
    }

    private fun removeUnavailableCompanySelections() {
        if (companies.isEmpty() || state.companyIds.isEmpty()) return
        val knownIds = companies.mapTo(hashSetOf()) { it.id }
        val availableSelection = state.companyIds.filterTo(linkedSetOf()) { it in knownIds }
        if (availableSelection != state.companyIds) {
            state = state.copy(companyIds = availableSelection)
            HomeCrmFilterStore.save(activity, state)
        }
    }

    private fun renderButtons() {
        binding.crmPhaseFilterButton.text = phaseButtonText()
        binding.crmCompanyFilterButton.text = companyButtonText()
        styleFilterButton(binding.crmPhaseFilterButton, state.hasPhaseFilter)
        styleFilterButton(binding.crmCompanyFilterButton, state.hasCompanyFilter)
    }

    private fun phaseButtonText(): String {
        if (!state.hasPhaseFilter) return activity.getString(R.string.crm_filter_phases_all)
        return activity.getString(
            R.string.crm_filter_phases_selected,
            state.phases.sorted().joinToString(", "),
        )
    }

    private fun companyButtonText(): String {
        if (!state.hasCompanyFilter) return activity.getString(R.string.crm_filter_companies_all)
        if (state.companyIds.size == 1) {
            val id = state.companyIds.first()
            val name = companies.firstOrNull { it.id == id }?.name.orEmpty().ifBlank { id }
            return activity.getString(R.string.crm_filter_topic_named, name)
        }
        return activity.getString(R.string.crm_filter_companies_selected, state.companyIds.size)
    }

    private fun phaseLabel(phase: Int): String = activity.getString(
        when (phase) {
            ContactNegotiationPhaseStore.PHASE_1 -> R.string.contact_phase_1
            ContactNegotiationPhaseStore.PHASE_2 -> R.string.contact_phase_2
            ContactNegotiationPhaseStore.PHASE_3 -> R.string.contact_phase_3
            ContactNegotiationPhaseStore.PHASE_4 -> R.string.contact_phase_4
            else -> R.string.crm_filter_phase_all
        },
    )

    private fun styleFilterButton(button: MaterialButton, active: Boolean) {
        val fill = if (active) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (active) activity.getColor(R.color.callreport_icon_background) else Color.rgb(203, 213, 225)
        button.backgroundTintList = ColorStateList.valueOf(fill)
        button.strokeColor = ColorStateList.valueOf(border)
        button.setTextColor(if (active) Color.WHITE else Color.rgb(51, 65, 85))
    }
}
