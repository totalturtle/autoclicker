package com.autoclicker

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

object AdManager {

    // 테스트 ID — 실제 배포 전 AdMob 콘솔에서 발급받은 광고 단위 ID로 교체
    private const val AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false

    fun init(context: Context) {
        MobileAds.initialize(context)
        loadAd(context)
    }

    private fun loadAd(context: Context) {
        if (isLoading || interstitialAd != null) return
        isLoading = true
        InterstitialAd.load(
            context.applicationContext,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isLoading = false
                }
            }
        )
    }

    fun showInterstitial(activity: Activity, onDismiss: () -> Unit) {
        val ad = interstitialAd
        if (ad == null) {
            onDismiss()
            loadAd(activity)
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadAd(activity)
                onDismiss()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                interstitialAd = null
                loadAd(activity)
                onDismiss()
            }
        }
        ad.show(activity)
    }
}
