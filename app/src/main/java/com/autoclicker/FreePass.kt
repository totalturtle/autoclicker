package com.realbtob.autoclicker

import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * 광고를 보고 받는 임시 이용권.
 *
 * 무료 사용자가 제한에 걸렸을 때 보상형 광고를 보면 24시간 동안 그 제한이 풀린다.
 * 프리미엄 사용자는 이 값과 무관하게 항상 열려 있다.
 */
object FreePass {

    /** 위치를 한 개 더(4개까지) */
    const val EXTRA_POINT = "pass_extra_point"

    /** 화면 녹화 */
    const val RECORDING = "pass_recording"

    /** 연속 실행 24시간 */
    const val LONG_RUN = "pass_long_run"

    /** 이용권 유효 시간 */
    const val PASS_HOURS = 24L

    /** 무료 사용자의 연속 실행 한도 */
    val FREE_RUN_LIMIT_MS: Long = TimeUnit.HOURS.toMillis(3)

    /** 이용권을 받았을 때의 연속 실행 한도 */
    val PASS_RUN_LIMIT_MS: Long = TimeUnit.HOURS.toMillis(24)

    private const val PREFS = "free_pass_prefs"

    /** 이용권이 아직 살아 있는지 */
    fun isActive(context: Context, key: String): Boolean =
        remainingMs(context, key) > 0L

    /** 남은 시간(ms). 없으면 0 */
    fun remainingMs(context: Context, key: String): Long {
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 광고를 끝까지 본 뒤 호출 — 지금부터 [PASS_HOURS]시간 동안 열어 준다 */
    fun grant(context: Context, key: String) {
        val until = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(PASS_HOURS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(key, until).apply()
    }

    /** "12시간 23분 남음" 같은 안내 문구 */
    fun remainingText(context: Context, key: String): String {
        val ms = remainingMs(context, key)
        if (ms <= 0L) return ""
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (hours > 0) "${hours}시간 ${minutes}분 남음" else "${minutes}분 남음"
    }

    /** 지금 쓸 수 있는 위치 개수 */
    fun pointLimit(context: Context): Int = when {
        PremiumManager.isPremium -> Int.MAX_VALUE
        isActive(context, EXTRA_POINT) -> PremiumManager.FREE_POINT_LIMIT + 1
        else -> PremiumManager.FREE_POINT_LIMIT
    }

    /** 녹화를 쓸 수 있는지 */
    fun canRecord(context: Context): Boolean =
        PremiumManager.isPremium || isActive(context, RECORDING)

    /** 연속 실행 한도(ms). 0이면 무제한 */
    fun runLimitMs(context: Context): Long = when {
        PremiumManager.isPremium -> 0L
        isActive(context, LONG_RUN) -> PASS_RUN_LIMIT_MS
        else -> FREE_RUN_LIMIT_MS
    }
}
