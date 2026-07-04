package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.material.button.MaterialButton
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Google Play one-time company-license purchase and restore screen.
 * The purchase is useful only after the server validates its purchase token and
 * stores a short-lived company-creation activation.
 */
class CompanyLicenseActivity : AppCompatActivity(), PurchasesUpdatedListener {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var billingClient: BillingClient
    private var billingReady = false
    private var currentProduct: ProductDetails? = null

    private lateinit var statusText: TextView
    private lateinit var priceText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buyButton: MaterialButton
    private lateinit var restoreButton: MaterialButton
    private lateinit var createCompanyButton: MaterialButton
    private lateinit var settingsButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        title = "Фирмен лиценз"
        setContentView(createContent())
        refreshActivationUi()

        if (!BuildConfig.PLAY_BILLING_ENABLED) {
            setStatus("Фирмен лиценз се купува през версията на приложението от Google Play.")
            buyButton.isEnabled = false
            restoreButton.isEnabled = false
            return
        }
        if (ConfigStore.load(this).baseUrl.isBlank()) {
            setStatus("Преди покупка въведи Server URL в Настройки, за да бъде лицензът потвърден към фирмения профил.")
            buyButton.isEnabled = false
            restoreButton.isEnabled = false
            return
        }
        startBilling()
    }

    override fun onDestroy() {
        if (::billingClient.isInitialized) billingClient.endConnection()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::processPurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> setStatus("Покупката беше отменена.")
            else -> setStatus("Google Play не можа да завърши покупката: ${billingResult.debugMessage.ifBlank { "непозната грешка" }}")
        }
    }

    private fun createContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val padding = dp(20)
        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, dp(28))
        }
        root.addView(column)

        column.addView(TextView(this).apply {
            text = "Създай своя фирма"
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = "Еднократният фирмен лиценз отключва създаване на една организация. Поканените колеги влизат без отделна покупка."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(18))
        })
        priceText = TextView(this).apply {
            text = "Проверяваме цената в Google Play…"
            textSize = 18f
            setPadding(0, 0, 0, dp(12))
        }
        column.addView(priceText)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
        column.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        buyButton = MaterialButton(this).apply {
            text = "Купи фирмен лиценз"
            isEnabled = false
            setOnClickListener { launchPurchase() }
        }
        column.addView(buyButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        restoreButton = MaterialButton(this).apply {
            text = "Възстанови покупка"
            isEnabled = false
            setOnClickListener { restorePurchases() }
        }
        column.addView(restoreButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        createCompanyButton = MaterialButton(this).apply {
            text = "Създай фирма"
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(this@CompanyLicenseActivity, CompanyAccountActivity::class.java).apply {
                    putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_REGISTER)
                })
            }
        }
        column.addView(createCompanyButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })

        settingsButton = MaterialButton(this).apply {
            text = "Отвори настройки"
            setOnClickListener { startActivity(Intent(this@CompanyLicenseActivity, MainActivity::class.java)) }
        }
        column.addView(settingsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(18), 0, 0)
        }
        column.addView(statusText)
        return root
    }

    private fun startBilling() {
        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build(),
            )
            .enableAutoServiceReconnection()
            .build()
        showProgress(true)
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                billingReady = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                if (!billingReady) {
                    showProgress(false)
                    setStatus("Неуспешна връзка с Google Play: ${billingResult.debugMessage.ifBlank { "опитай отново" }}")
                    return
                }
                queryLicenseProduct()
                restorePurchases(silent = true)
            }

            override fun onBillingServiceDisconnected() {
                billingReady = false
                buyButton.isEnabled = false
                restoreButton.isEnabled = false
                setStatus("Връзката с Google Play е прекъсната. Отвори екрана отново.")
            }
        })
    }

    private fun queryLicenseProduct() {
        if (!billingReady) return
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        billingClient.queryProductDetailsAsync(params) { billingResult, result ->
            showProgress(false)
            currentProduct = result.productDetailsList.firstOrNull()
            val details = currentProduct
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
                buyButton.isEnabled = false
                priceText.text = "Фирменият лиценз още не е наличен в Google Play."
                setStatus("Създай еднократен продукт с ID ${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID} в Play Console и го активирай за този тест/релийз.")
                return@queryProductDetailsAsync
            }
            val price = details.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty()
            priceText.text = if (price.isBlank()) "Еднократен фирмен лиценз" else "Цена: $price"
            buyButton.isEnabled = CompanyLicenseStore.loadValid(this) == null
            restoreButton.isEnabled = true
            if (CompanyLicenseStore.loadValid(this) == null) {
                setStatus("Лицензът се потвърждава от сървъра преди да можеш да създадеш фирма.")
            }
        }
    }

    private fun launchPurchase() {
        val product = currentProduct ?: run {
            setStatus("Лицензът още не е зареден от Google Play.")
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(obfuscatedInstallationId())
            .build()
        val result = billingClient.launchBillingFlow(this, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            setStatus("Google Play не можа да отвори плащането: ${result.debugMessage.ifBlank { "опитай отново" }}")
        }
    }

    private fun restorePurchases(silent: Boolean = false) {
        if (!billingReady) {
            if (!silent) setStatus("Google Play още не е готов.")
            return
        }
        showProgress(true)
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            showProgress(false)
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                if (!silent) setStatus("Неуспешно възстановяване на покупката: ${billingResult.debugMessage.ifBlank { "опитай отново" }}")
                return@queryPurchasesAsync
            }
            val licenses = purchases.filter { purchase ->
                purchase.products.contains(BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID)
            }
            if (licenses.isEmpty()) {
                if (!silent) setStatus("Няма намерен фирмен лиценз за този Google Play профил.")
                return@queryPurchasesAsync
            }
            licenses.forEach(::processPurchase)
        }
    }

    private fun processPurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> verifyWithServer(purchase)
            Purchase.PurchaseState.PENDING -> setStatus("Плащането се обработва от Google Play. Лицензът ще се активира след потвърждение.")
            else -> setStatus("Покупката не е завършена.")
        }
    }

    private fun verifyWithServer(purchase: Purchase) {
        showProgress(true)
        buyButton.isEnabled = false
        executor.execute {
            val result = CompanyLicenseApi.verifyPurchase(
                context = applicationContext,
                productId = BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID,
                purchaseToken = purchase.purchaseToken,
            )
            runOnUiThread {
                result.onSuccess { activation ->
                    CompanyLicenseStore.save(applicationContext, activation.activationToken, activation.expiresAtMs, activation.productId)
                    acknowledgePurchase(purchase)
                    showProgress(false)
                    refreshActivationUi()
                    setStatus("Лицензът е потвърден. Вече можеш да създадеш фирма.")
                }.onFailure { error ->
                    showProgress(false)
                    buyButton.isEnabled = currentProduct != null
                    setStatus("Покупката е направена, но сървърната проверка не успя: ${error.message ?: "опитай Възстанови покупка"}")
                }
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged || !billingReady) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                setStatus("Лицензът е потвърден, но Google Play acknowledgement ще бъде повторен при Възстанови покупка.")
            }
        }
    }

    private fun refreshActivationUi() {
        val activation = CompanyLicenseStore.loadValid(this)
        createCompanyButton.visibility = if (activation == null) View.GONE else View.VISIBLE
        if (activation != null) {
            priceText.text = "Фирменият лиценз е потвърден до ${android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", activation.expiresAtMs)}"
            buyButton.isEnabled = false
        }
    }

    private fun setStatus(value: String) {
        statusText.text = value
    }

    private fun showProgress(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun obfuscatedInstallationId(): String {
        val prefs = getSharedPreferences("relationship_manager_billing", MODE_PRIVATE)
        val raw = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString("installation_id", id).apply()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
