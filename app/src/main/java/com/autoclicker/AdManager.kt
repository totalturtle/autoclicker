package com.realbtob.autoclicker

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.lang.ref.WeakReference

/**
 * 광고 관리.
 *
 * - 프리미엄 사용자에게는 광고를 불러오지도, 보여주지도 않는다.
 * - 받아 둔 광고는 한 시간쯤 지나면 쓸 수 없으므로 유효 시간을 두고 버린다.
 * - 보상형 광고가 준비되지 않았으면 그 자리에서 받아 와서 띄운다(바로 실패하지 않는다).
 */
object AdManager {

    private const val AD_UNIT_ID = "ca-app-pub-4580352399728296/2605249128"

    /**
     * 보상형 광고 단위 ID.
     * 디버그 빌드는 구글 테스트 ID를 쓰고, 출시 빌드는 AdMob에서 발급받은 ID를 쓴다.
     * 비어 있으면 "광고 보고 열기" 버튼을 띄우지 않는다(테스트 ID를 출시에 쓰면 정책 위반).
     */
    private const val REWARD_UNIT_ID_TEST = "ca-app-pub-3940256099942544/5224354917"
    private const val REWARD_UNIT_ID_RELEASE = "ca-app-pub-4580352399728296/1070265357"

    /** 받아 둔 광고를 버리는 기준. AdMob은 약 1시간 뒤 만료되므로 그 전에 새로 받는다. */
    private const val AD_TTL_MS = 50 * 60 * 1000L

    /** 광고를 기다려 주는 시간 */
    private const val REWARD_WAIT_MS = 12_000L

    private val rewardUnitId: String
        get() = if (BuildConfig.DEBUG) REWARD_UNIT_ID_TEST else REWARD_UNIT_ID_RELEASE

    /** 보상형 광고를 쓸 수 있는 빌드인지 (ID가 설정돼 있는지) */
    val isRewardAvailable: Boolean get() = rewardUnitId.isNotEmpty()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var interstitialAd: InterstitialAd? = null
    private var interstitialLoadedAt = 0L
    private var isLoading = false

    private var rewardedAd: RewardedAd? = null
    private var rewardedLoadedAt = 0L
    private var isLoadingReward = false

    /** 광고를 기다리는 중인 요청 */
    private class PendingReward(
        val activity: WeakReference<Activity>,
        val onReward: () -> Unit,
        val onUnavailable: () -> Unit
    )

    private var pendingReward: PendingReward? = null
    private val rewardTimeout = Runnable {
        val pending = pendingReward ?: return@Runnable
        pendingReward = null
        pending.onUnavailable()
    }

    fun init(context: Context) {
        MobileAds.initialize(context)
        prefetch(context)
    }

    /** 미리 받아 둔다. 프리미엄이면 아무것도 하지 않는다. */
    fun prefetch(context: Context) {
        if (PremiumManager.isPremium) return
        loadAd(context)
        loadRewarded(context)
    }

    private fun isFresh(loadedAt: Long) =
        loadedAt > 0L && System.currentTimeMillis() - loadedAt < AD_TTL_MS

    // ── 전면 광고 ───────────────────────────────────────────────────────

    private fun loadAd(context: Context) {
        if (PremiumManager.isPremium) return
        if (isLoading) return
        if (interstitialAd != null && isFresh(interstitialLoadedAt)) return
        interstitialAd = null
        isLoading = true
        InterstitialAd.load(
            context.applicationContext,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    interstitialLoadedAt = System.currentTimeMillis()
                    isLoading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    interstitialLoadedAt = 0L
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
        val ad = interstitialAd?.takeIf { isFresh(interstitialLoadedAt) }
        if (ad == null) {
            // 없거나 오래됐으면 버리고 새로 받되, 사용자를 붙잡지 않는다.
            interstitialAd = null
            interstitialLoadedAt = 0L
            loadAd(activity)
            onDismiss()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                interstitialLoadedAt = 0L
                loadAd(activity)
                onDismiss()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                interstitialAd = null
                interstitialLoadedAt = 0L
                loadAd(activity)
                onDismiss()
            }
        }
        ad.show(activity)
    }

    // ── 보상형 광고 ──────────────────────────────────────────────────────

    private fun loadRewarded(context: Context) {
        if (!isRewardAvailable || PremiumManager.isPremium) return
        if (isLoadingReward) return
        if (rewardedAd != null && isFresh(rewardedLoadedAt)) return
        rewardedAd = null
        isLoadingReward = true
        RewardedAd.load(
            context.applicationContext,
            rewardUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    rewardedLoadedAt = System.currentTimeMillis()
                    isLoadingReward = false
                    deliverPendingReward()
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    rewardedLoadedAt = 0L
                    isLoadingReward = false
                    failPendingReward()
                }
            }
        )
    }

    /**
     * 보상형 광고를 보여 준다.
     * 끝까지 보면 [onReward], 끝내 띄우지 못하면 [onUnavailable]을 부른다.
     * 광고가 준비되지 않았으면 "불러오는 중" 안내를 띄우고 잠시 기다린다.
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewardedAd?.takeIf { isFresh(rewardedLoadedAt) }
        if (ad != null) {
            present(activity, ad, onReward, onUnavailable)
            return
        }
        rewardedAd = null
        rewardedLoadedAt = 0L
        Toast.makeText(activity, R.string.toast_ad_loading, Toast.LENGTH_SHORT).show()
        pendingReward = PendingReward(WeakReference(activity), onReward, onUnavailable)
        mainHandler.removeCallbacks(rewardTimeout)
        mainHandler.postDelayed(rewardTimeout, REWARD_WAIT_MS)
        loadRewarded(activity)
    }

    private fun deliverPendingReward() {
        val pending = pendingReward ?: return
        val ad = rewardedAd ?: return
        val activity = pending.activity.get()
        pendingReward = null
        mainHandler.removeCallbacks(rewardTimeout)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            // 화면이 사라졌으면 광고는 다음 기회에 쓴다
            pending.onUnavailable()
            return
        }
        present(activity, ad, pending.onReward, pending.onUnavailable)
    }

    private fun failPendingReward() {
        val pending = pendingReward ?: return
        pendingReward = null
        mainHandler.removeCallbacks(rewardTimeout)
        pending.onUnavailable()
    }

    private fun present(
        activity: Activity,
        ad: RewardedAd,
        onReward: () -> Unit,
        onUnavailable: () -> Unit
    ) {
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                rewardedLoadedAt = 0L
                loadRewarded(activity)
                if (earned) onReward() else onUnavailable()
            }
            override fun onAdFailedToShowFullScreenContent(e: AdError) {
                rewardedAd = null
                rewardedLoadedAt = 0L
                loadRewarded(activity)
                onUnavailable()
            }
        }
        ad.show(activity) { earned = true }
    }
}
