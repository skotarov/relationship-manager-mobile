package com.onlineimoti.calllog

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService

internal class CompanyLicenseStatusController(
    private val activity: CompanyLicenseActivity,
    private val executor: ExecutorService,
    private val status: TextView,
    private val price: TextView,
    private val paymentOptionsBox: LinearLayout,
    private val playResponse: TextView,
    private val spinner: ProgressBar,
    private val buy: MaterialButton,
    private val restore: MaterialButton,
    private val create: MaterialButton,
    private val product: () -> ProductDetails?,
    private val offerToken: () -> String,
) {
    private var serverLicenseStatus: CompanyLicenseApi.LicenseStatus? = null

    fun sync(diagnostics: CompanyLicenseApi.PlayDiagnostics) {
        if (ConfigStore.load(activity).baseUrl.isBlank()) return
        executor.execute {
            val result = CompanyLicenseApi.licenseStatus(activity.applicationContext, diagnostics)
            activity.runOnUiThread {
                result.onSuccess(::render)
                    .onFailure { error ->
                        if (serverLicenseStatus == null) {
                            status.text = "Сървърният статус на лиценза не се зареди: ${error.message ?: "опитай отново"}"
                        }
                    }
            }
        }
    }

    fun googlePlayOption(): CompanyLicenseApi.PaymentOption? =
        serverLicenseStatus?.googlePlayOption()

    fun message(): String = serverLicenseStatus?.message.orEmpty()

    fun renderGoogleButton() {
        val activation = CompanyLicenseStore.loadValid(activity)
        val googleOption = googlePlayOption()
        if (googleOption != null) buy.text = googleOption.title.ifBlank { "Плати през Google Play" }
        buy.isEnabled = activation == null &&
            product() != null &&
            offerToken().isNotBlank() &&
            googleOption?.enabled != false
    }

    fun refreshActivation() {
        val activation = CompanyLicenseStore.loadValid(activity)
        create.visibility = if (activation == null) View.GONE else View.VISIBLE
        if (activation != null) {
            price.text = "Фирменият лиценз е потвърден до ${android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", activation.expiresAtMs)}"
            buy.isEnabled = false
        }
    }

    fun loading(value: Boolean) {
        spinner.visibility = if (value) View.VISIBLE else View.GONE
    }

    fun showPlayResponse(stage: String, result: BillingResult, extra: String = "") {
        playResponse.text = buildString {
            appendLine(stage)
            appendLine(
                "responseCode=${result.responseCode} (${CompanyLicenseBillingDebug.billingCodeName(result.responseCode)})",
            )
            appendLine("debugMessage=${result.debugMessage.ifBlank { "-" }}")
            if (extra.isNotBlank()) append(extra.trim())
        }
    }

    private fun render(value: CompanyLicenseApi.LicenseStatus) {
        serverLicenseStatus = value
        if (value.title.isNotBlank()) price.text = value.title
        if (value.message.isNotBlank()) status.text = value.message
        if (value.state == "active") {
            buy.isEnabled = false
            restore.isEnabled = false
        }
        CompanyLicensePaymentOptionsUi.render(
            activity = activity,
            container = paymentOptionsBox,
            options = value.paymentOptions,
            onSelected = ::handlePaymentOption,
        )
        renderGoogleButton()
    }

    private fun handlePaymentOption(option: CompanyLicenseApi.PaymentOption) {
        when (option.type) {
            "webview", "web_url" -> if (option.url.isBlank()) {
                status.text = option.description.ifBlank { "Сървърът не върна адрес за този начин на плащане." }
            } else {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(option.url)))
            }
            "instructions", "manual", "contact" -> {
                status.text = listOf(option.title, option.description)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }
            else -> status.text = "Неподдържан начин на плащане: ${option.type}"
        }
    }
}
