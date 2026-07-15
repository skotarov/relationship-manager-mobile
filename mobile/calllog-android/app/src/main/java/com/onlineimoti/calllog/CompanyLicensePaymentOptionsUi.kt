package com.onlineimoti.calllog

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton

internal object CompanyLicensePaymentOptionsUi {
    fun render(
        activity: Activity,
        container: LinearLayout,
        options: List<CompanyLicenseApi.PaymentOption>,
        onSelected: (CompanyLicenseApi.PaymentOption) -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        container.removeAllViews()
        val nonGoogle = options.filterNot { it.type == "google_play" }
        if (nonGoogle.isEmpty()) return
        container.addView(TextView(activity).apply {
            text = "Други начини за плащане"
            textSize = 16f
            setPadding(0, dp(14), 0, dp(4))
        })
        nonGoogle.forEach { option ->
            container.addView(
                MaterialButton(activity).apply {
                    text = option.title.ifBlank { "Плащане" }
                    isEnabled = option.enabled
                    setOnClickListener { onSelected(option) }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
            val detail = listOf(option.subtitle, option.description)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (detail.isNotBlank()) {
                container.addView(TextView(activity).apply {
                    text = detail
                    textSize = 13f
                    setPadding(dp(4), 0, dp(4), dp(4))
                })
            }
        }
    }
}
