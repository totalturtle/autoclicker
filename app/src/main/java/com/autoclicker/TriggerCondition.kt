package com.autoclicker

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

enum class TriggerAction { SKIP, WAIT_RETRY }
enum class TriggerMode { PIXEL, REGION }

data class TriggerCondition(
    val checkX: Int,
    val checkY: Int,
    val targetColor: Int,
    val tolerance: Int = 20,
    val action: TriggerAction = TriggerAction.SKIP,
    val maxRetries: Int = 5,
    val retryDelayMs: Long = 500L,
    // 영역 비교 모드 (mode == REGION 일 때 사용)
    val mode: TriggerMode = TriggerMode.PIXEL,
    val regionW: Int = 0,
    val regionH: Int = 0,
    val regionPixels: IntArray? = null,
    val regionMatchThreshold: Float = 0.9f
) {
    fun colorHex(): String = String.format("#%06X", targetColor and 0xFFFFFF)

    /** 비트맵에서 (checkX, checkY) 기준 regionW×regionH 영역을 기준 픽셀과 비교 */
    fun regionMatches(bitmap: Bitmap): Boolean {
        val pixels = regionPixels ?: return false
        if (regionW <= 0 || regionH <= 0) return false
        val x = checkX.coerceIn(0, bitmap.width - 1)
        val y = checkY.coerceIn(0, bitmap.height - 1)
        val w = regionW.coerceAtMost(bitmap.width - x)
        val h = regionH.coerceAtMost(bitmap.height - y)
        if (w <= 0 || h <= 0) return false
        val total = (w * h).coerceAtMost(pixels.size)
        if (total == 0) return false
        // getPixel() 개별 호출 대신 getPixels() 일괄 추출 (22500px = ~500ms → ~5ms)
        val actual = IntArray(w * h)
        bitmap.getPixels(actual, 0, w, x, y, w, h)
        var matches = 0
        for (i in 0 until total) {
            if (colorMatches(actual[i], pixels[i], tolerance)) matches++
        }
        return matches.toFloat() / total >= regionMatchThreshold
    }

    companion object {
        fun colorMatches(actual: Int, target: Int, tolerance: Int): Boolean {
            val dr = abs(Color.red(actual) - Color.red(target))
            val dg = abs(Color.green(actual) - Color.green(target))
            val db = abs(Color.blue(actual) - Color.blue(target))
            return dr <= tolerance && dg <= tolerance && db <= tolerance
        }

        fun parseColor(hex: String): Int? = runCatching { Color.parseColor(hex) }.getOrNull()
    }
}
