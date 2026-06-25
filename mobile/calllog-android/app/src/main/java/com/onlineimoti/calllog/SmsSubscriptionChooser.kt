package com.onlineimoti.calllog

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Builds the SIM picker and remembers the last successfully used SIM. */
internal class SmsSubscriptionChooser(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    private val preferences by lazy {
        activity.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun addTo(root: LinearLayout): () -> Int? {
        val options = activeSubscriptions()
        val defaultId = defaultSubscriptionId()
        val initiallySelected = options.firstOrNull { it.id == lastSuccessfulSubscriptionId() }
            ?: options.firstOrNull { it.id == defaultId }
            ?: options.firstOrNull()
        if (options.isEmpty()) return { defaultId }

        root.addView(label(activity.getString(R.string.dynamic_sms_send_from)))
        if (options.size == 1) {
            root.addView(singleOption(initiallySelected?.label.orEmpty()))
            return { initiallySelected?.id ?: defaultId }
        }

        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(5))
            background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        options.forEach { option -> group.addView(optionButton(option, option.id == initiallySelected?.id)) }
        root.addView(group)
        return {
            group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Int
                ?: initiallySelected?.id
                ?: defaultId
        }
    }

    fun rememberSuccessfulSubscriptionId(subscriptionId: Int?) {
        val validId = subscriptionId?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID } ?: return
        preferences.edit().putInt(KEY_LAST_SUCCESSFUL_SUBSCRIPTION_ID, validId).apply()
    }

    private fun label(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(Color.rgb(71, 85, 105))
        setPadding(0, 0, 0, dp(4))
    }

    private fun singleOption(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 15f
        setTextColor(Color.rgb(15, 23, 42))
        setPadding(dp(12), dp(9), dp(12), dp(10))
        background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.rgb(203, 213, 225), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    private fun optionButton(option: Option, selected: Boolean): RadioButton = RadioButton(activity).apply {
        id = View.generateViewId()
        tag = option.id
        text = option.label
        textSize = 15f
        setTextColor(Color.rgb(15, 23, 42))
        setPadding(dp(4), dp(3), dp(4), dp(3))
        isChecked = selected
    }

    private fun activeSubscriptions(): List<Option> {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val manager = activity.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            manager.activeSubscriptionInfoList.orEmpty().map { info ->
                Option(
                    id = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    label = optionLabel(info),
                )
            }.sortedBy { it.slotIndex }
        }.getOrDefault(emptyList())
    }

    private fun optionLabel(info: android.telephony.SubscriptionInfo): String {
        val simLabel = if (info.simSlotIndex >= 0) {
            activity.getString(R.string.dynamic_sms_sim, info.simSlotIndex + 1)
        } else {
            activity.getString(R.string.dynamic_sms_sim_unknown)
        }
        return listOf(simLabel, info.carrierName?.toString()?.trim().orEmpty(), info.displayName?.toString()?.trim().orEmpty())
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" • ")
    }

    private fun defaultSubscriptionId(): Int? = runCatching {
        SubscriptionManager.getDefaultSmsSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }.getOrNull()

    private fun lastSuccessfulSubscriptionId(): Int? = preferences
        .getInt(KEY_LAST_SUCCESSFUL_SUBSCRIPTION_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    private data class Option(val id: Int, val label: String, val slotIndex: Int)

    private companion object {
        const val PREFS_NAME = "sms_compose_dialog"
        const val KEY_LAST_SUCCESSFUL_SUBSCRIPTION_ID = "last_successful_subscription_id"
    }
}
