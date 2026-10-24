package com.realbtob.autoclicker

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

object AdManager {

    private const val AD_UNIT_ID = "ca-app-pub-4580352399728296/2605249128"

    /**
     * 보상형 광고 단위 ID.
     * 디버그 빌드는 구글 테스트 ID를 쓰고, 출시 빌드는 AdMob에서 만든 실제 ID를 넣어야 한다.
     * 비어 있으면 "광고 보고 열기" 버튼을 띄우지 않는다(테스트 ID를 출시에 쓰면 정책 위반).
     */
    private const val REWARD_UNIT_ID_TEST = "ca-app-pub-3940256099942544/5224354917"
    private const val REWARD_UNIT_ID_RELEASE = ""

    private val rewardUnitId: String
        get() = if (BuildConfig.DEBUG) REWARD_UNIT_ID_TEST else REWARD_UNIT_ID_RELEASE

    /** 보상형 광고를 쓸 수 있는 빌드인지 (ID가 설정돼 있는지) */
    val isRewardAvailable: Boolean get() = rewardUnitId.isNotEmpty()

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var rewardedAd: RewardedAd? = null
    private var isLoadingReward = false

    fun init(context: Context) {
        MobileAds.initialize(context)
        loadAd(context)
        loadRewarded(context)
    }

    // ── 보상형 광고 ──────────────────────────────────────────────────────

    private fun loadRewarded(context: Context) {
        if (!isRewardAvailable || isLoadingReward || rewardedAd != null) return
        isLoadingReward = true
        RewardedAd.load(
            context.applicationContext,
            rewardUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoadingReward = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isLoadingReward = false
                }
            }
        )
    }

    /**
     * 보상형 광고를 보여 준다.
     * 끝까지 보면 [onReward], 광고를 못 띄우면 [onUnavailable]을 부른다.
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            loadRewarded(activity)
            onUnavailable()
            return
        }
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewarded(activity)
                if (earned) onReward() else onUnavailable()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                rewardedAd = null
                loadRewarded(activity)
                onUnavailable()
            }
        }
        ad.show(activity) { earned = true }
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
        // 프리미엄 사용자에게는 광고를 노출하지 않는다.
        if (PremiumManager.isPremium) {
            onDismiss()
            return
        }
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
