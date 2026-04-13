package com.autoclicker

import android.graphics.Color
import kotlin.math.abs

enum class TriggerAction { SKIP, WAIT_RETRY }

data class TriggerCondition(
    val checkX: Int,
    val checkY: Int,
    val targetColor: Int,
    val tolerance: Int = 20,
    val action: TriggerAction = TriggerAction.SKIP,
    val maxRetries: Int = 5,
    val retryDelayMs: Long = 500L,
    val usePointCoords: Boolean = false   // true이면 checkX/checkY 대신 포인트 좌표로 확인
) {
    fun colorHex(): String = String.format("#%06X", targetColor and 0xFFFFFF)

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
