package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Compact color-only controls for one company's negotiation phase. */
internal class ContactNegotiationPhaseUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun phaseBar(
        phone: String,
        companyId: String,
        showControls: Boolean,
        onChanged: () -> Unit,
    ): LinearLayout {
        if (!showControls || companyId.isBlank()) {
            return LinearLayout(activity).apply {
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                )
            }
        }

        reconcileWithServer(phone, companyId, onChanged)
        val selectedPhase = CompanyNegotiationPhaseStore.selectedPhase(activity, phone, companyId)
        val phases = phaseDefinitions()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rectangle(COLOR_INACTIVE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(BAR_HEIGHT_DP),
            ).apply {
                topMargin = 0
                bottomMargin = dp(8)
            }

            phases.forEachIndexed { index, phase ->
                addView(
                    phaseButton(
                        phone = phone,
                        companyId = companyId,
                        phase = phase,
                        selected = selectedPhase == phase.number,
                        onChanged = onChanged,
                    ),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
                if (index < phases.lastIndex) {
                    addView(View(activity).apply {
                        setBackgroundColor(COLOR_SURFACE)
                        layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
                    })
                }
            }
        }
    }

    private fun phaseButton(
        phone: String,
        companyId: String,
        phase: PhaseDefinition,
        selected: Boolean,
        onChanged: () -> Unit,
    ): TextView {
        return TextView(activity).apply {
            text = ""
            contentDescription = activity.getString(phase.labelRes)
            background = rectangle(if (selected) phase.activeColor else COLOR_INACTIVE)
            isClickable = true
            isFocusable = true
            isSelected = selected
            setOnClickListener {
                CompanyNegotiationPhaseStore.togglePhase(activity, phone, companyId, phase.number)
                // A phase filter may have cached this phone before the local value
                // is uploaded. Force its next render to read the fresh server state.
                HomeCrmPhaseLookup.invalidate()
                onChanged()
            }
        }
    }

    private fun reconcileWithServer(phone: String, companyId: String, onChanged: () -> Unit) {
        CompanyNegotiationPhaseSyncDispatcher.synchronize(activity, phone, companyId) { changed ->
            if (changed && !activity.isFinishing && !activity.isDestroyed) {
                HomeCrmPhaseLookup.invalidate()
                onChanged()
            }
        }
    }

    private fun phaseDefinitions(): List<PhaseDefinition> = listOf(
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_1, R.string.contact_phase_1, COLOR_GREEN),
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_2, R.string.contact_phase_2, COLOR_BLUE),
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_3, R.string.contact_phase_3, COLOR_YELLOW),
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_4, R.string.contact_phase_4, COLOR_RED),
    )

    private fun rectangle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(color)
    }

    private data class PhaseDefinition(
        val number: Int,
        val labelRes: Int,
        val activeColor: Int,
    )

    private companion object {
        const val BAR_HEIGHT_DP = 14
        val COLOR_SURFACE: Int = Color.WHITE
        val COLOR_INACTIVE: Int = Color.rgb(203, 213, 225)
        val COLOR_GREEN: Int = Color.rgb(22, 163, 74)
        val COLOR_BLUE: Int = Color.rgb(37, 99, 235)
        val COLOR_YELLOW: Int = Color.rgb(250, 204, 21)
        val COLOR_RED: Int = Color.rgb(220, 38, 38)
    }
}
