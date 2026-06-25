package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Flat, mutually exclusive phase controls rendered below the contact identity header. */
internal class ContactNegotiationPhaseUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun phaseBar(phone: String, onChanged: () -> Unit): LinearLayout {
        val selectedPhase = ContactNegotiationPhaseStore.selectedPhase(activity, phone)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rectangle(COLOR_SURFACE, COLOR_BORDER, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(BAR_HEIGHT_DP),
            ).apply { bottomMargin = dp(12) }

            phaseDefinitions().forEachIndexed { index, phase ->
                addView(
                    phaseButton(
                        phone = phone,
                        phase = phase,
                        selected = selectedPhase == phase.number,
                        onChanged = onChanged,
                    ),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                )
                if (index < phaseDefinitions().lastIndex) {
                    addView(View(activity).apply {
                        setBackgroundColor(COLOR_BORDER)
                        layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
                    })
                }
            }
        }
    }

    private fun phaseButton(
        phone: String,
        phase: PhaseDefinition,
        selected: Boolean,
        onChanged: () -> Unit,
    ): TextView {
        val textColor = if (selected) phase.activeTextColor else COLOR_INACTIVE_TEXT
        val backgroundColor = if (selected) phase.activeColor else Color.TRANSPARENT
        return TextView(activity).apply {
            text = activity.getString(phase.labelRes)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = rectangle(backgroundColor)
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = text
            setOnClickListener {
                ContactNegotiationPhaseStore.togglePhase(activity, phone, phase.number)
                onChanged()
            }
        }
    }

    private fun phaseDefinitions(): List<PhaseDefinition> = listOf(
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_1, R.string.contact_phase_1, COLOR_GREEN, Color.WHITE),
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_2, R.string.contact_phase_2, COLOR_BLUE, Color.WHITE),
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_3, R.string.contact_phase_3, COLOR_YELLOW, COLOR_DARK_TEXT),
        PhaseDefinition(ContactNegotiationPhaseStore.PHASE_4, R.string.contact_phase_4, COLOR_RED, Color.WHITE),
    )

    private fun rectangle(color: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(color)
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private data class PhaseDefinition(
        val number: Int,
        val labelRes: Int,
        val activeColor: Int,
        val activeTextColor: Int,
    )

    private companion object {
        const val BAR_HEIGHT_DP = 42
        val COLOR_SURFACE: Int = Color.WHITE
        val COLOR_BORDER: Int = Color.rgb(203, 213, 225)
        val COLOR_INACTIVE_TEXT: Int = Color.rgb(100, 116, 139)
        val COLOR_DARK_TEXT: Int = Color.rgb(30, 41, 59)
        val COLOR_GREEN: Int = Color.rgb(22, 163, 74)
        val COLOR_BLUE: Int = Color.rgb(37, 99, 235)
        val COLOR_YELLOW: Int = Color.rgb(250, 204, 21)
        val COLOR_RED: Int = Color.rgb(220, 38, 38)
    }
}
