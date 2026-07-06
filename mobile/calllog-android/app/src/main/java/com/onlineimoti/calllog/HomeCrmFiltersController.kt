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

/** CRM Home filter controls: full-width phase buttons and a topic picker. */
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
        binding.crmPhase1Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_1) }
        binding.crmPhase2Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_2) }
        binding.crmPhase3Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_3) }
        binding.crmPhase4Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_4) }
        binding.crmCompanyFilterButton.setOnClickListener { showCompanyDialog() }
    }

    fun state(): HomeCrmFilterState = state

    fun hasActiveFilters(): Boolean = state.isActive

    fun updateVisibility(filtersEnabled: Boolean) {
        binding.crmPhaseFilterRow.visibility = if (filtersEnabled) View.VISIBLE else View.GONE
        binding.crmCompanyFilterButton.visibility = if (filtersEnabled) View.VISIBLE else View.GONE
        binding.filteredStatusContainer.gravity = if (filtersEnabled) {
            Gravity.CENTER_VERTICAL or Gravity.END
        } else {
            Gravity.CENTER_VERTICAL
        }
        if (!filtersEnabled) return
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

    private fun togglePhase(phase: Int) {
        val phases = state.phases.toMutableSet()
        if (!phases.add(phase)) phases.remove(phase)
        updateState(state.copy(phases = phases))
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
        binding.crmCompanyFilterButton.text = companyButtonText()
        styleFilterButton(binding.crmCompanyFilterButton, state.hasCompanyFilter)
        stylePhaseButton(
            button = binding.crmPhase1Button,
            phase = ContactNegotiationPhaseStore.PHASE_1,
            activeColor = COLOR_GREEN,
            activeTextColor = Color.WHITE,
        )
        stylePhaseButton(
            button = binding.crmPhase2Button,
            phase = ContactNegotiationPhaseStore.PHASE_2,
            activeColor = COLOR_BLUE,
            activeTextColor = Color.WHITE,
        )
        stylePhaseButton(
            button = binding.crmPhase3Button,
            phase = ContactNegotiationPhaseStore.PHASE_3,
            activeColor = COLOR_YELLOW,
            activeTextColor = COLOR_DARK_TEXT,
        )
        stylePhaseButton(
            button = binding.crmPhase4Button,
            phase = ContactNegotiationPhaseStore.PHASE_4,
            activeColor = COLOR_RED,
            activeTextColor = Color.WHITE,
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

    private fun stylePhaseButton(
        button: MaterialButton,
        phase: Int,
        activeColor: Int,
        activeTextColor: Int,
    ) {
        val selected = phase in state.phases
        val color = if (selected) activeColor else COLOR_INACTIVE
        button.isSelected = selected
        button.backgroundTintList = ColorStateList.valueOf(color)
        button.strokeColor = ColorStateList.valueOf(color)
        button.setTextColor(if (selected) activeTextColor else COLOR_DARK_TEXT)
    }

    private fun styleFilterButton(button: MaterialButton, active: Boolean) {
        val fill = if (active) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (active) activity.getColor(R.color.callreport_icon_background) else COLOR_INACTIVE
        button.backgroundTintList = ColorStateList.valueOf(fill)
        button.strokeColor = ColorStateList.valueOf(border)
        button.setTextColor(if (active) Color.WHITE else COLOR_DARK_TEXT)
    }

    private companion object {
        val COLOR_INACTIVE: Int = Color.rgb(203, 213, 225)
        val COLOR_DARK_TEXT: Int = Color.rgb(51, 65, 85)
        val COLOR_GREEN: Int = Color.rgb(22, 163, 74)
        val COLOR_BLUE: Int = Color.rgb(37, 99, 235)
        val COLOR_YELLOW: Int = Color.rgb(250, 204, 21)
        val COLOR_RED: Int = Color.rgb(220, 38, 38)
    }
}
