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
import java.util.concurrent.Executors

class CompanyLicenseActivity : AppCompatActivity(), PurchasesUpdatedListener {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var client: BillingClient
    private var connected = false
    private var product: ProductDetails? = null
    private var offerToken = ""

    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var playResponse: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var buy: MaterialButton
    private lateinit var restore: MaterialButton
    private lateinit var create: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        title = "Фирмен лиценз"
        setContentView(content())
        refresh()
        if (!BuildConfig.PLAY_BILLING_ENABLED) {
            status.text = "Фирмен лиценз се купува през версията от Google Play."
            playResponse.text = "Google Play Billing е изключен в този build."
            return
        }
        if (ConfigStore.load(this).baseUrl.isBlank()) {
            status.text = "Преди покупка въведи Server URL в Настройки."
            playResponse.text = "Google Play още не е питан, защото Server URL липсва."
            return
        }
        connect()
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.endConnection()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        showPlayResponse(
            "Резултат от покупка",
            result,
            "purchases=${purchases.orEmpty().size}\n${purchases.orEmpty().joinToString("\n") { purchaseDebug(it) }}"
        )
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::process)
            BillingClient.BillingResponseCode.USER_CANCELED -> status.text = "Покупката беше отменена."
            else -> status.text = "Google Play не можа да завърши покупката: ${result.debugMessage.ifBlank { "опитай отново" }}"
        }
    }

    private fun content(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(box)
        box.addView(TextView(this).apply { text = "Създай своя фирма"; textSize = 24f })
        box.addView(TextView(this).apply {
            text = "Еднократният фирмен лиценз отключва една организация. Поканените колеги влизат без отделна покупка."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(18))
        })
        price = TextView(this).apply { text = "Проверяваме цената в Google Play…"; textSize = 18f }
        box.addView(price)
        spinner = ProgressBar(this).apply { visibility = View.GONE }
        box.addView(spinner, LinearLayout.LayoutParams(dp(42), dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        buy = MaterialButton(this).apply { text = "Купи фирмен лиценз"; isEnabled = false; setOnClickListener { launch() } }
        box.addView(buy, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        restore = MaterialButton(this).apply { text = "Възстанови покупка"; isEnabled = false; setOnClickListener { restore() } }
        box.addView(restore, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        create = MaterialButton(this).apply {
            text = "Създай фирма"
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(this@CompanyLicenseActivity, CompanyAccountActivity::class.java).putExtra(CompanyAccountActivity.EXTRA_MODE, CompanyAccountActivity.MODE_REGISTER))
            }
        }
        box.addView(create, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        box.addView(MaterialButton(this).apply {
            text = "Отвори настройки"
            setOnClickListener { startActivity(Intent(this@CompanyLicenseActivity, MainActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(18), 0, 0) }
        box.addView(status)
        box.addView(TextView(this).apply {
            text = "Отговор от Google Play"
            textSize = 16f
            setPadding(0, dp(18), 0, dp(6))
        })
        playResponse = TextView(this).apply {
            text = "Още няма отговор от Google Play."
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(playResponse)
        return root
    }

    private fun connect() {
        client = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        loading(true)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connected = result.responseCode == BillingClient.BillingResponseCode.OK
                showPlayResponse(
                    "Свързване с Google Play",
                    result,
                    "package=${applicationContext.packageName}\nproduct=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}\nbillingEnabled=${BuildConfig.PLAY_BILLING_ENABLED}"
                )
                if (!connected) {
                    loading(false)
                    status.text = "Неуспешна връзка с Google Play: ${result.debugMessage.ifBlank { "опитай отново" }}"
                    return
                }
                loadProduct()
                restore(silent = true)
            }
            override fun onBillingServiceDisconnected() {
                connected = false
                buy.isEnabled = false
                restore.isEnabled = false
                playResponse.text = "Google Play връзката беше прекъсната."
            }
        })
    }

    private fun loadProduct() {
        val item = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(item)).build()) { result, detailsResult ->
            loading(false)
            product = detailsResult.productDetailsList.firstOrNull()
            val details = product
            val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: details?.oneTimePurchaseOfferDetails
            showPlayResponse(
                "Заявка за продукт",
                result,
                listOf(
                    "package=${applicationContext.packageName}",
                    "product=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}",
                    "type=${BillingClient.ProductType.INAPP}",
                    "fetchedProducts=${detailsResult.productDetailsList.size}",
                    unfetchedProductsDebug(detailsResult),
                    "title=${details?.title.orEmpty().ifBlank { "-" }}",
                    "price=${offer?.formattedPrice.orEmpty().ifBlank { "-" }}",
                    "offerToken=${maskedToken(offer?.offerToken.orEmpty())}",
                ).joinToString("\n")
            )
            if (result.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
                offerToken = ""
                price.text = "Фирменият лиценз още не е наличен в Google Play."
                status.text = "Създай и активирай продукт ${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID} в Play Console."
                return@queryProductDetailsAsync
            }
            offerToken = offer?.offerToken.orEmpty()
            if (offerToken.isBlank()) {
                buy.isEnabled = false
                price.text = "Няма достъпна оферта за този профил."
                status.text = "Провери настройките и тестовия профил в Google Play."
                return@queryProductDetailsAsync
            }
            price.text = offer?.formattedPrice?.let { "Цена: $it" } ?: "Еднократен фирмен лиценз"
            buy.isEnabled = CompanyLicenseStore.loadValid(this) == null
            restore.isEnabled = true
            if (CompanyLicenseStore.loadValid(this) == null) status.text = "Лицензът се потвърждава от сървъра."
        }
    }

    private fun launch() {
        val details = product ?: run { status.text = "Лицензът още не е зареден."; return }
        if (offerToken.isBlank()) { status.text = "Няма валидна оферта за покупка."; return }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val result = client.launchBillingFlow(this, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params))
            .setObfuscatedAccountId(obfuscatedInstallationId())
            .build())
        showPlayResponse(
            "Отваряне на покупка",
            result,
            "product=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}\nofferToken=${maskedToken(offerToken)}"
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) status.text = "Google Play не можа да отвори плащането: ${result.debugMessage.ifBlank { "опитай отново" }}"
    }

    private fun restore(silent: Boolean = false) {
        if (!connected) return
        loading(true)
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            loading(false)
            showPlayResponse(
                "Възстановяване на покупки",
                result,
                "purchases=${purchases.size}\n${purchases.joinToString("\n") { purchaseDebug(it) }}"
            )
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                if (!silent) status.text = "Неуспешно възстановяване: ${result.debugMessage.ifBlank { "опитай отново" }}"
                return@queryPurchasesAsync
            }
            val licenses = purchases.filter { it.products.contains(BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID) }
            if (licenses.isEmpty() && !silent) status.text = "Няма намерен фирмен лиценз за този Google Play профил."
            licenses.forEach(::process)
        }
    }

    private fun process(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> verify(purchase)
            Purchase.PurchaseState.PENDING -> status.text = "Плащането се обработва от Google Play."
            else -> status.text = "Покупката не е завършена."
        }
    }

    private fun verify(purchase: Purchase) {
        loading(true)
        buy.isEnabled = false
        executor.execute {
            val result = CompanyLicenseApi.verifyPurchase(applicationContext, BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID, purchase.purchaseToken)
            runOnUiThread {
                result.onSuccess { activation ->
                    CompanyLicenseStore.save(applicationContext, activation.activationToken, activation.expiresAtMs, activation.productId)
                    acknowledge(purchase)
                    loading(false)
                    refresh()
                    status.text = "Лицензът е потвърден. Вече можеш да създадеш фирма."
                }.onFailure { error ->
                    loading(false)
                    buy.isEnabled = product != null && offerToken.isNotBlank()
                    status.text = "Покупката е направена, но проверката не успя: ${error.message ?: "опитай Възстанови покупка"}"
                }
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged || !connected) return
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result ->
            showPlayResponse("Acknowledge покупка", result, purchaseDebug(purchase))
            if (result.responseCode != BillingClient.BillingResponseCode.OK) status.text = "Лицензът е потвърден; acknowledgement ще бъде повторен при възстановяване."
        }
    }

    private fun refresh() {
        val activation = CompanyLicenseStore.loadValid(this)
        create.visibility = if (activation == null) View.GONE else View.VISIBLE
        if (activation != null) {
            price.text = "Фирменият лиценз е потвърден до ${android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", activation.expiresAtMs)}"
            buy.isEnabled = false
        }
    }

    private fun loading(value: Boolean) { spinner.visibility = if (value) View.VISIBLE else View.GONE }

    private fun showPlayResponse(stage: String, result: BillingResult, extra: String = "") {
        playResponse.text = buildString {
            appendLine(stage)
            appendLine("responseCode=${result.responseCode} (${billingCodeName(result.responseCode)})")
            appendLine("debugMessage=${result.debugMessage.ifBlank { "-" }}")
            if (extra.isNotBlank()) append(extra.trim())
        }
    }

    private fun billingCodeName(code: Int): String = when (code) {
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

    private fun unfetchedProductsDebug(detailsResult: Any): String {
        val list = runCatching {
            detailsResult.javaClass.methods
                .firstOrNull { it.name == "getUnfetchedProductList" && it.parameterCount == 0 }
                ?.invoke(detailsResult) as? List<*>
        }.getOrNull().orEmpty()
        return if (list.isEmpty()) {
            "unfetchedProducts=0"
        } else {
            "unfetchedProducts=${list.size}\n" + list.take(5).joinToString("\n") { "unfetched=$it" }
        }
    }

    private fun purchaseDebug(purchase: Purchase): String = listOf(
        "products=${purchase.products.joinToString(",")}",
        "state=${purchaseStateName(purchase.purchaseState)}",
        "acknowledged=${purchase.isAcknowledged}",
        "token=${maskedToken(purchase.purchaseToken)}",
    ).joinToString("; ")

    private fun purchaseStateName(state: Int): String = when (state) {
        Purchase.PurchaseState.PURCHASED -> "PURCHASED"
        Purchase.PurchaseState.PENDING -> "PENDING"
        Purchase.PurchaseState.UNSPECIFIED_STATE -> "UNSPECIFIED_STATE"
        else -> "UNKNOWN"
    }

    private fun maskedToken(token: String): String = when {
        token.isBlank() -> "-"
        token.length <= 16 -> token
        else -> token.take(8) + "…" + token.takeLast(6)
    }

    private fun obfuscatedInstallationId(): String {
        val prefs = getSharedPreferences("relationship_manager_billing", MODE_PRIVATE)
        val raw = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("installation_id", it).apply() }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
