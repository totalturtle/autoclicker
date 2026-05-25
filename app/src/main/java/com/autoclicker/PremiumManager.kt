package com.realbtob.autoclicker

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.*
import kotlinx.coroutines.*

object PremiumManager {

    const val PRODUCT_ID = "premium_unlock"
    const val FREE_POINT_LIMIT = 3

    private const val PREFS_NAME = "premium_prefs"
    private const val KEY_PREMIUM = "is_premium"
    private const val KEY_DEBUG_PREMIUM = "debug_premium_override"

    private var billingClient: BillingClient? = null
    private var _isPremium = false
    private var _debugPremiumOverride = false
    private var purchaseCallback: ((Boolean) -> Unit)? = null

    val isPremium: Boolean get() =
        if (BuildConfig.DEBUG) _isPremium || _debugPremiumOverride else _isPremium

    val isDebugPremiumOverride: Boolean get() = _debugPremiumOverride

    fun setDebugPremiumOverride(context: Context, value: Boolean) {
        if (!BuildConfig.DEBUG) return
        _debugPremiumOverride = value
        prefs(context).edit().putBoolean(KEY_DEBUG_PREMIUM, value).apply()
    }

    fun init(context: Context) {
        val prefs = prefs(context)
        _isPremium = prefs.getBoolean(KEY_PREMIUM, false)
        if (BuildConfig.DEBUG) {
            _debugPremiumOverride = prefs.getBoolean(KEY_DEBUG_PREMIUM, false)
        }

        billingClient = BillingClient.newBuilder(context.applicationContext)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(context, purchase)
                    }
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPurchases(context)
                }
            }
            override fun onBillingServiceDisconnected() {
                // 서비스 끊기면 자동 재연결
                billingClient?.startConnection(this)
            }
        })
    }

    private fun queryPurchases(context: Context) {
        billingClient?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { _, purchases ->
            val owned = purchases.any { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (owned) setPremium(context, true)
        }
    }

    fun launchPurchase(activity: Activity, onResult: (Boolean) -> Unit) {
        val client = billingClient ?: return
        if (!client.isReady) {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        doLaunchPurchase(activity, onResult)
                    } else {
                        activity.runOnUiThread { onResult(false) }
                    }
                }
                override fun onBillingServiceDisconnected() {
                    activity.runOnUiThread { onResult(false) }
                }
            })
        } else {
            doLaunchPurchase(activity, onResult)
        }
    }

    private fun doLaunchPurchase(activity: Activity, onResult: (Boolean) -> Unit) {
        val client = billingClient ?: return
        purchaseCallback = onResult  // 구매 완료 시 handlePurchase에서 호출
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )).build()

        client.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK || productDetailsList.isEmpty()) {
                purchaseCallback = null
                activity.runOnUiThread { onResult(false) }
                return@queryProductDetailsAsync
            }
            val productDetails = productDetailsList[0]
            val billingParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()
                )).build()

            client.launchBillingFlow(activity, billingParams)
        }
    }

    fun fetchPrice(onResult: (String) -> Unit) {
        val client = billingClient ?: run { onResult(""); return }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )).build()
        client.queryProductDetailsAsync(params) { _, productDetailsList ->
            val price = productDetailsList.firstOrNull()
                ?.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
            onResult(price)
        }
    }

    fun handlePurchase(context: Context, purchase: Purchase) {
        if (purchase.products.contains(PRODUCT_ID) && purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val ackParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient?.acknowledgePurchase(ackParams) { ackResult ->
                    if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        setPremium(context, true)
                        val cb = purchaseCallback
                        purchaseCallback = null
                        cb?.let { (context as? Activity)?.runOnUiThread { it(true) } }
                    }
                }
            } else {
                // 이미 승인된 구매 (재설치 등)
                setPremium(context, true)
                val cb = purchaseCallback
                purchaseCallback = null
                cb?.let { (context as? Activity)?.runOnUiThread { it(true) } }
            }
        }
    }

    private fun setPremium(context: Context, value: Boolean) {
        _isPremium = value
        prefs(context).edit().putBoolean(KEY_PREMIUM, value).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
