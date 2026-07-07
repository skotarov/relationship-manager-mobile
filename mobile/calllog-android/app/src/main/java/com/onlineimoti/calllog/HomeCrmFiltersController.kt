package com.onlineimoti.calllog

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** CRM Home filter controls: full-width phase buttons and in-row company toggles. */
internal class HomeCrmFiltersController(
    private val activity: HomeActivity,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val dp: (Int) -> Int,
    @Suppress("UNUSED_PARAMETER") roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val onFilterChanged: () -> Unit,
) {
    private val companyExecutor = Executors.newSingleThreadExecutor()
    private val companyGeneration = AtomicInteger(0)
    private var state = HomeCrmFilterStore.load(activity)
    private var companies: List<CallReportTopicCompany> = emptyList()
    private var lastRequestedAccount = ""

    private val companyButtonsContainer = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
    }
    private val companyButtonsScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(
            companyButtonsContainer,
            HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        visibility = View.GONE
    }

    init {
        binding.crmPhase1Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_1) }
        binding.crmPhase2Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_2) }
        binding.crmPhase3Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_3) }
        binding.crmPhase4Button.setOnClickListener { togglePhase(ContactNegotiationPhaseStore.PHASE_4) }
        replaceCompanyPickerWithButtons()
    }

    fun state(): HomeCrmFilterState = state

    fun hasActiveFilters(): Boolean = state.isActive

    fun updateVisibility(filtersEnabled: Boolean) {
        binding.crmPhaseFilterRow.visibility = if (filtersEnabled) View.VISIBLE else View.GONE
        binding.filteredStatusContainer.gravity = if (filtersEnabled) {
            Gravity.CENTER_VERTICAL or Gravity.END
        } else {
            Gravity.CENTER_VERTICAL
        }
        if (!filtersEnabled) {
            showCompanyButtons(false)
            return
        }
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

    private fun replaceCompanyPickerWithButtons() {
        val placeholder = binding.crmCompanyFilterButton.parent as? View ?: return
        val statusContainer = placeholder.parent as? LinearLayout ?: return
        val position = statusContainer.indexOfChild(placeholder)
        if (position < 0) return
        statusContainer.removeView(placeholder)
        statusContainer.addView(
            companyButtonsScroll,
            position,
            LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) },
        )
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

    private fun toggleCompany(companyId: String) {
        val selected = state.companyIds.toMutableSet()
        if (!selected.add(companyId)) selected.remove(companyId)
        // Empty selection intentionally means no company filter: show all companies.
        updateState(state.copy(companyIds = selected))
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
        renderCompanyButtons()
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

    private fun renderCompanyButtons() {
        val available = companies
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
        companyButtonsContainer.removeAllViews()
        available.forEach { company ->
            companyButtonsContainer.addView(companyButton(company))
        }
        showCompanyButtons(
            shouldShow = binding.crmPhaseFilterRow.visibility == View.VISIBLE && available.isNotEmpty(),
        )
    }

    private fun companyButton(company: CallReportTopicCompany): MaterialButton =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = company.name
            contentDescription = company.name
            isAllCaps = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(152)
            textSize = 12f
            minHeight = 0
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(16)
            setPadding(dp(9), 0, dp(9), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36),
            ).apply { marginEnd = dp(4) }
            styleCompanyButton(this, company.id in state.companyIds)
            setOnClickListener { toggleCompany(company.id) }
        }

    private fun showCompanyButtons(shouldShow: Boolean) {
        companyButtonsScroll.visibility = if (shouldShow) View.VISIBLE else View.GONE
        val parameters = binding.homeStatusText.layoutParams as? LinearLayout.LayoutParams ?: return
        val expectedWidth = if (shouldShow) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        val expectedWeight = if (shouldShow) 0f else 1f
        if (parameters.width != expectedWidth || parameters.weight != expectedWeight) {
            parameters.width = expectedWidth
            parameters.weight = expectedWeight
            binding.homeStatusText.layoutParams = parameters
        }
        binding.homeStatusText.maxWidth = if (shouldShow) dp(112) else Int.MAX_VALUE
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

    private fun styleCompanyButton(button: MaterialButton, active: Boolean) {
        val fill = if (active) activity.getColor(R.color.callreport_icon_background) else Color.WHITE
        val border = if (active) activity.getColor(R.color.callreport_icon_background) else COLOR_INACTIVE
        button.isSelected = active
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
