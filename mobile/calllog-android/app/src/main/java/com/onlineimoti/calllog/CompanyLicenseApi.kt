package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object CompanyLicenseApi {
    private const val BILLING_PATH = "/relationship-manager/api/billing.php"

    data class ActivationResult(
        val activationToken: String,
        val expiresAtMs: Long,
        val productId: String,
    )

    fun verifyPurchase(context: Context, productId: String, purchaseToken: String): Result<ActivationResult> = runCatching {
        val config = ConfigStore.load(context)
        require(config.baseUrl.isNotBlank()) { "Първо задай Server URL в Настройки." }
        require(productId.isNotBlank()) { "Липсва продукт за фирмен лиценз." }
        require(purchaseToken.isNotBlank()) { "Липсва Google Play purchase token." }

        val url = URL(buildEndpoint(config.baseUrl, BILLING_PATH, emptyMap()))
        val request = JSONObject()
            .put("action", "verify_purchase")
            .put("product_id", productId)
            .put("purchase_token", purchaseToken)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(request) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val response = JSONObject(responseText.ifBlank { "{}" })
            if (connection.responseCode !in 200..299 || !response.optBoolean("ok", false)) {
                val error = response.optJSONObject("error")
                throw IllegalStateException(error?.optString("message").orEmpty().ifBlank { "Покупката не беше потвърдена от сървъра." })
            }
            val activationToken = response.optString("activation_token").trim()
            val expiresAtMs = response.optLong("activation_expires_at_ms", 0L)
            val verifiedProductId = response.optString("product_id", productId).trim()
            require(activationToken.isNotBlank() && expiresAtMs > System.currentTimeMillis()) {
                "Сървърът не издаде валиден активиращ код."
            }
            ActivationResult(activationToken, expiresAtMs, verifiedProductId)
        } finally {
            connection.disconnect()
        }
    }
}
