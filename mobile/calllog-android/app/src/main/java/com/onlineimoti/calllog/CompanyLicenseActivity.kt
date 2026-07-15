package com.onlineimoti.calllog

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import java.util.concurrent.Executors

class CompanyLicenseActivity : AppCompatActivity(), PurchasesUpdatedListener {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var client: BillingClient
    private var connected = false
    private var product: ProductDetails? = null
    private var offerToken = ""
    private var serverLicenseStatus: CompanyLicenseApi.LicenseStatus? = null

    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var paymentOptionsBox: LinearLayout
    private lateinit var playResponse: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var buy: MaterialButton
    private lateinit var restore: MaterialButton
    private lateinit var create: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        title = "Фирмен лиценз"
        val views = CompanyLicenseContentUi.create(
            activity = this,
            launchPurchase = ::launch,
            restorePurchase = { restore() },
        )
        status = views.status
        price = views.price
        paymentOptionsBox = views.paymentOptionsBox
        playResponse = views.playResponse
        spinner = views.spinner
        buy = views.buy
        restore = views.restore
        create = views.create
        setContentView(views.root)
        refresh()
        if (ConfigStore.load(this).baseUrl.isBlank()) {
            status.text = "Преди покупка въведи Server URL в Настройки."
            playResponse.text = "Google Play още не е питан, защото Server URL липсва."
            return
        }
        syncLicenseStatus(CompanyLicenseApi.PlayDiagnostics(stage = "initial"))
        if (!BuildConfig.PLAY_BILLING_ENABLED) {
            status.text = "Фирмен лиценз се купува през версията от Google Play."
            playResponse.text = "Google Play Billing е изключен в този build."
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
        val owned = purchases.orEmpty()
        showPlayResponse(
            "Резултат от покупка",
            result,
            "purchases=${owned.size}\n${owned.joinToString("\n") { CompanyLicenseBillingDebug.purchaseDebug(it) }}",
        )
        syncLicenseStatus(CompanyLicenseApi.PlayDiagnostics(
            stage = "purchase_update",
            responseCode = result.responseCode,
            debugMessage = result.debugMessage,
            purchaseCount = owned.size,
            hasPurchaseToken = owned.any { it.purchaseToken.isNotBlank() },
        ))
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> owned.forEach(::process)
            BillingClient.BillingResponseCode.USER_CANCELED -> status.text = "Покупката беше отменена."
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore(silent = false)
            else -> status.text = "Google Play не можа да завърши покупката: ${result.debugMessage.ifBlank { "опитай отново" }}"
        }
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
                    "package=${applicationContext.packageName}\nproduct=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}\nbillingEnabled=${BuildConfig.PLAY_BILLING_ENABLED}",
                )
                syncLicenseStatus(CompanyLicenseApi.PlayDiagnostics(
                    stage = "billing_setup",
                    responseCode = result.responseCode,
                    debugMessage = result.debugMessage,
                ))
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
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(item)).build(),
        ) { result, detailsResult ->
            loading(false)
            product = detailsResult.productDetailsList.firstOrNull()
            val details = product
            val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
                ?: details?.oneTimePurchaseOfferDetails
            val unfetchedCount = CompanyLicenseBillingDebug.unfetchedProductsCount(detailsResult)
            showPlayResponse(
                "Заявка за продукт",
                result,
                listOf(
                    "package=${applicationContext.packageName}",
                    "product=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}",
                    "type=${BillingClient.ProductType.INAPP}",
                    "fetchedProducts=${detailsResult.productDetailsList.size}",
                    CompanyLicenseBillingDebug.unfetchedProductsDebug(detailsResult),
                    "title=${details?.title.orEmpty().ifBlank { "-" }}",
                    "price=${offer?.formattedPrice.orEmpty().ifBlank { "-" }}",
                    "offerToken=${CompanyLicenseBillingDebug.maskedToken(offer?.offerToken.orEmpty())}",
                ).joinToString("\n"),
            )
            syncLicenseStatus(CompanyLicenseApi.PlayDiagnostics(
                stage = "query_product",
                responseCode = result.responseCode,
                debugMessage = result.debugMessage,
                fetchedProducts = detailsResult.productDetailsList.size,
                unfetchedProducts = unfetchedCount,
            ))
            if (result.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
                offerToken = ""
                price.text = "Фирменият лиценз още не е наличен в Google Play."
                status.text = "Сървърът ще покаже точния статус според отговора на Google Play."
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
            restore.isEnabled = true
            renderGoogleButton()
            if (CompanyLicenseStore.loadValid(this) == null && status.text.isBlank()) {
                status.text = "Лицензът се потвърждава от сървъра."
            }
        }
    }

    private fun launch() {
        val details = product ?: run {
            status.text = "Лицензът още не е зареден."
            return
        }
        if (offerToken.isBlank()) {
            status.text = "Няма валидна оферта за покупка."
            return
        }
        val googleOption = serverLicenseStatus?.googlePlayOption()
        if (googleOption?.enabled == false) {
            status.text = googleOption.description.ifBlank { "Google Play плащането не е активно от сървъра." }
            return
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val result = client.launchBillingFlow(
            this,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(params))
                .setObfuscatedAccountId(CompanyLicenseBillingDebug.obfuscatedInstallationId(this))
                .build(),
        )
        showPlayResponse(
            "Отваряне на покупка",
            result,
            "product=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}\nofferToken=${CompanyLicenseBillingDebug.maskedToken(offerToken)}",
        )
        syncLicenseStatus(CompanyLicenseApi.PlayDiagnostics(
            stage = "launch_billing_flow",
            responseCode = result.responseCode,
            debugMessage = result.debugMessage,
        ))
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            status.text = "Google Play не можа да отвори плащането: ${result.debugMessage.ifBlank { "опитай отново" }}"
        }
    }

    private fun restore(silent: Boolean = false) {
        if (!connected) return
        loading(true)
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            loading(false)
            showPlayResponse(
                "Възстановяване на покупки",
                result,
                "purchases=${purchases.size}\n${purchases.joinToString("\n") { CompanyLicenseBillingDebug.purchaseDebug(it) }}",
            )
            syncLicenseStatus(CompanyLicenseApi.PlayDiagnostics(
                stage = "query_purchases",
                responseCode = result.responseCode,
                debugMessage = result.debugMessage,
                purchaseCount = purchases.size,
                hasPurchaseToken = purchases.any { it.purchaseToken.isNotBlank() },
            ))
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                if (!silent) status.text = "Неуспешно възстановяване: ${result.debugMessage.ifBlank { "опитай отново" }}"
                return@queryPurchasesAsync
            }
            val licenses = purchases.filter { it.products.contains(BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID) }
            if (licenses.isEmpty() && !silent) {
                status.text = serverLicenseStatus?.message?.takeIf { it.isNotBlank() }
                    ?: "Няма намерен фирмен лиценз за този Google Play профил."
            }
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
            val result = CompanyLicenseApi.verifyPurchase(
                applicationContext,
                BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID,
                purchase.purchaseToken,
            )
            runOnUiThread {
                result.onSuccess { activation ->
                    CompanyLicenseStore.save(
                        applicationContext,
                        activation.activationToken,
                        activation.expiresAtMs,
                        activation.productId,
                    )
                    acknowledge(purchase)
                    loading(false)
                    refresh()
                    status.text = "Лицензът е потвърден. Вече можеш да създадеш фирма."
                    syncLicenseStatus(CompanyLicenseApi.PlayDiagnostics(
                        stage = "verify_purchase",
                        hasPurchaseToken = true,
                    ))
                }.onFailure { error ->
                    loading(false)
                    renderGoogleButton()
                    status.text = "Покупката е направена, но проверката не успя: ${error.message ?: "опитай Възстанови покупка"}"
                }
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged || !connected) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { result ->
            showPlayResponse("Acknowledge покупка", result, CompanyLicenseBillingDebug.purchaseDebug(purchase))
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                status.text = "Лицензът е потвърден; acknowledgement ще бъде повторен при възстановяване."
            }
        }
    }

    private fun syncLicenseStatus(diagnostics: CompanyLicenseApi.PlayDiagnostics) {
        if (ConfigStore.load(this).baseUrl.isBlank()) return
        executor.execute {
            val result = CompanyLicenseApi.licenseStatus(applicationContext, diagnostics)
            runOnUiThread {
                result.onSuccess { renderLicenseStatus(it) }
                    .onFailure { error ->
                        if (serverLicenseStatus == null) {
                            status.text = "Сървърният статус на лиценза не се зареди: ${error.message ?: "опитай отново"}"
                        }
                    }
            }
        }
    }

    private fun renderLicenseStatus(value: CompanyLicenseApi.LicenseStatus) {
        serverLicenseStatus = value
        if (value.title.isNotBlank()) price.text = value.title
        if (value.message.isNotBlank()) status.text = value.message
        if (value.state == "active") {
            buy.isEnabled = false
            restore.isEnabled = false
        }
        CompanyLicensePaymentOptionsUi.render(
            activity = this,
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
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(option.url)))
            }
            "instructions", "manual", "contact" -> {
                status.text = listOf(option.title, option.description)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }
            else -> status.text = "Неподдържан начин на плащане: ${option.type}"
        }
    }

    private fun renderGoogleButton() {
        val activation = CompanyLicenseStore.loadValid(this)
        val googleOption = serverLicenseStatus?.googlePlayOption()
        if (googleOption != null) buy.text = googleOption.title.ifBlank { "Плати през Google Play" }
        buy.isEnabled = activation == null && product != null && offerToken.isNotBlank() && googleOption?.enabled != false
    }

    private fun refresh() {
        val activation = CompanyLicenseStore.loadValid(this)
        create.visibility = if (activation == null) View.GONE else View.VISIBLE
        if (activation != null) {
            price.text = "Фирменият лиценз е потвърден до ${android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", activation.expiresAtMs)}"
            buy.isEnabled = false
        }
    }

    private fun loading(value: Boolean) {
        spinner.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun showPlayResponse(stage: String, result: BillingResult, extra: String = "") {
        playResponse.text = buildString {
            appendLine(stage)
            appendLine(
                "responseCode=${result.responseCode} (${CompanyLicenseBillingDebug.billingCodeName(result.responseCode)})",
            )
            appendLine("debugMessage=${result.debugMessage.ifBlank { "-" }}")
            if (extra.isNotBlank()) append(extra.trim())
        }
    }
}
