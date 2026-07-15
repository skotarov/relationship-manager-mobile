package com.onlineimoti.calllog

import android.widget.TextView
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService

internal class CompanyLicensePurchaseVerifier(
    private val activity: CompanyLicenseActivity,
    private val executor: ExecutorService,
    private val billingClient: () -> BillingClient,
    private val connected: () -> Boolean,
    private val status: TextView,
    private val buy: MaterialButton,
    private val licenseStatus: CompanyLicenseStatusController,
) {
    fun process(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> verify(purchase)
            Purchase.PurchaseState.PENDING -> status.text = "Плащането се обработва от Google Play."
            else -> status.text = "Покупката не е завършена."
        }
    }

    private fun verify(purchase: Purchase) {
        licenseStatus.loading(true)
        buy.isEnabled = false
        executor.execute {
            val result = CompanyLicenseApi.verifyPurchase(
                activity.applicationContext,
                BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID,
                purchase.purchaseToken,
            )
            activity.runOnUiThread {
                result.onSuccess { activation ->
                    CompanyLicenseStore.save(
                        activity.applicationContext,
                        activation.activationToken,
                        activation.expiresAtMs,
                        activation.productId,
                    )
                    acknowledge(purchase)
                    licenseStatus.loading(false)
                    licenseStatus.refreshActivation()
                    status.text = "Лицензът е потвърден. Вече можеш да създадеш фирма."
                    licenseStatus.sync(CompanyLicenseApi.PlayDiagnostics(
                        stage = "verify_purchase",
                        hasPurchaseToken = true,
                    ))
                }.onFailure { error ->
                    licenseStatus.loading(false)
                    licenseStatus.renderGoogleButton()
                    status.text = "Покупката е направена, но проверката не успя: ${error.message ?: "опитай Възстанови покупка"}"
                }
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged || !connected()) return
        billingClient().acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { result ->
            licenseStatus.showPlayResponse(
                "Acknowledge покупка",
                result,
                CompanyLicenseBillingDebug.purchaseDebug(purchase),
            )
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                status.text = "Лицензът е потвърден; acknowledgement ще бъде повторен при възстановяване."
            }
        }
    }
}
