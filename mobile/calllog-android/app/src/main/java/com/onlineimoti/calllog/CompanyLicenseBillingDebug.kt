package com.onlineimoti.calllog

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import java.security.MessageDigest
import java.util.UUID

internal object CompanyLicenseBillingDebug {
    fun billingCodeName(code: Int): String = when (code) {
        BillingClient.BillingResponseCode.OK -> "OK"
        BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
        BillingClient.BillingResponseCode.ERROR -> "ERROR"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
        else -> "UNKNOWN"
    }

    fun unfetchedProductsCount(detailsResult: Any): Int = unfetchedProductsList(detailsResult).size

    fun unfetchedProductsDebug(detailsResult: Any): String {
        val list = unfetchedProductsList(detailsResult)
        return if (list.isEmpty()) {
            "unfetchedProducts=0"
        } else {
            "unfetchedProducts=${list.size}\n" + list.take(5).joinToString("\n") { "unfetched=$it" }
        }
    }

    fun purchaseDebug(purchase: Purchase): String = listOf(
        "products=${purchase.products.joinToString(",")}",
        "state=${purchaseStateName(purchase.purchaseState)}",
        "acknowledged=${purchase.isAcknowledged}",
        "token=${maskedToken(purchase.purchaseToken)}",
    ).joinToString("; ")

    fun maskedToken(token: String): String = when {
        token.isBlank() -> "-"
        token.length <= 16 -> token
        else -> token.take(8) + "…" + token.takeLast(6)
    }

    fun obfuscatedInstallationId(context: Context): String {
        val prefs = context.getSharedPreferences("relationship_manager_billing", Context.MODE_PRIVATE)
        val raw = prefs.getString("installation_id", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("installation_id", it).apply()
            }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun unfetchedProductsList(detailsResult: Any): List<*> = runCatching {
        detailsResult.javaClass.methods
            .firstOrNull { it.name == "getUnfetchedProductList" && it.parameterCount == 0 }
            ?.invoke(detailsResult) as? List<*>
    }.getOrNull().orEmpty()

    private fun purchaseStateName(state: Int): String = when (state) {
        Purchase.PurchaseState.PURCHASED -> "PURCHASED"
        Purchase.PurchaseState.PENDING -> "PENDING"
        Purchase.PurchaseState.UNSPECIFIED_STATE -> "UNSPECIFIED_STATE"
        else -> "UNKNOWN"
    }
}
