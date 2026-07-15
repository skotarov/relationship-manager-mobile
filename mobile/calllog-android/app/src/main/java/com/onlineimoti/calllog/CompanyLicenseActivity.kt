package com.onlineimoti.calllog

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var status: TextView
    private lateinit var price: TextView
    private lateinit var buy: MaterialButton
    private lateinit var restore: MaterialButton
    private lateinit var create: MaterialButton
    private lateinit var licenseStatus: CompanyLicenseStatusController
    private lateinit var purchaseVerifier: CompanyLicensePurchaseVerifier

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
        buy = views.buy
        restore = views.restore
        create = views.create
        licenseStatus = CompanyLicenseStatusController(
            activity = this,
            executor = executor,
            status = status,
            price = price,
            paymentOptionsBox = views.paymentOptionsBox,
            playResponse = views.playResponse,
            spinner = views.spinner,
            buy = buy,
            restore = restore,
            create = create,
            product = { product },
            offerToken = { offerToken },
        )
        purchaseVerifier = CompanyLicensePurchaseVerifier(
            activity = this,
            executor = executor,
            billingClient = { client },
            connected = { connected },
            status = status,
            buy = buy,
            licenseStatus = licenseStatus,
        )
        setContentView(views.root)
        licenseStatus.refreshActivation()
        if (ConfigStore.load(this).baseUrl.isBlank()) {
            status.text = "Преди покупка въведи Server URL в Настройки."
            licenseStatus.setPlayResponse("Google Play още не е питан, защото Server URL липсва.")
            return
        }
        licenseStatus.sync(CompanyLicenseApi.PlayDiagnostics(stage = "initial"))
        if (!BuildConfig.PLAY_BILLING_ENABLED) {
            status.text = "Фирмен лиценз се купува през версията от Google Play."
            licenseStatus.setPlayResponse("Google Play Billing е изключен в този build.")
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
        licenseStatus.showPlayResponse(
            "Резултат от покупка",
            result,
            "purchases=${owned.size}\n${owned.joinToString("\n") { CompanyLicenseBillingDebug.purchaseDebug(it) }}",
        )
        licenseStatus.sync(CompanyLicenseApi.PlayDiagnostics(
            stage = "purchase_update",
            responseCode = result.responseCode,
            debugMessage = result.debugMessage,
            purchaseCount = owned.size,
            hasPurchaseToken = owned.any { it.purchaseToken.isNotBlank() },
        ))
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> owned.forEach(purchaseVerifier::process)
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
        licenseStatus.loading(true)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connected = result.responseCode == BillingClient.BillingResponseCode.OK
                licenseStatus.showPlayResponse(
                    "Свързване с Google Play",
                    result,
                    "package=${applicationContext.packageName}\nproduct=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}\nbillingEnabled=${BuildConfig.PLAY_BILLING_ENABLED}",
                )
                licenseStatus.sync(CompanyLicenseApi.PlayDiagnostics(
                    stage = "billing_setup",
                    responseCode = result.responseCode,
                    debugMessage = result.debugMessage,
                ))
                if (!connected) {
                    licenseStatus.loading(false)
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
                licenseStatus.setPlayResponse("Google Play връзката беше прекъсната.")
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
            licenseStatus.loading(false)
            product = detailsResult.productDetailsList.firstOrNull()
            val details = product
            val offer = details?.oneTimePurchaseOfferDetailsList?.firstOrNull()
                ?: details?.oneTimePurchaseOfferDetails
            val unfetchedCount = CompanyLicenseBillingDebug.unfetchedProductsCount(detailsResult)
            licenseStatus.showPlayResponse(
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
            licenseStatus.sync(CompanyLicenseApi.PlayDiagnostics(
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
            licenseStatus.renderGoogleButton()
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
        val googleOption = licenseStatus.googlePlayOption()
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
        licenseStatus.showPlayResponse(
            "Отваряне на покупка",
            result,
            "product=${BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID}\nofferToken=${CompanyLicenseBillingDebug.maskedToken(offerToken)}",
        )
        licenseStatus.sync(CompanyLicenseApi.PlayDiagnostics(
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
        licenseStatus.loading(true)
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            licenseStatus.loading(false)
            licenseStatus.showPlayResponse(
                "Възстановяване на покупки",
                result,
                "purchases=${purchases.size}\n${purchases.joinToString("\n") { CompanyLicenseBillingDebug.purchaseDebug(it) }}",
            )
            licenseStatus.sync(CompanyLicenseApi.PlayDiagnostics(
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
            val licenses = purchases.filter {
                it.products.contains(BuildConfig.PLAY_COMPANY_LICENSE_PRODUCT_ID)
            }
            if (licenses.isEmpty() && !silent) {
                status.text = licenseStatus.message().takeIf { it.isNotBlank() }
                    ?: "Няма намерен фирмен лиценз за този Google Play профил."
            }
            licenses.forEach(purchaseVerifier::process)
        }
    }
}
