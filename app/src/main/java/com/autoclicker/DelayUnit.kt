package com.realbtob.autoclicker

import android.widget.EditText
import android.widget.Spinner

/**
 * 딜레이 입력란의 단위(ms/초/분) 변환 도우미.
 * 저장 포맷은 항상 ms이므로 UI에 채울 때와 읽을 때만 단위를 적용한다.
 */
object DelayUnit {

    const val MS = 0
    const val SEC = 1
    const val MIN = 2

    private val MULTIPLIER = longArrayOf(1L, 1_000L, 60_000L)

    /** ms 값을 나누어떨어지는 가장 큰 단위로 보여주기 위한 단위 인덱스 */
    fun unitFor(ms: Long): Int = when {
        ms <= 0L           -> MS
        ms % 60_000L == 0L -> MIN
        ms % 1_000L == 0L  -> SEC
        else               -> MS
    }

    fun toMs(value: Long, unit: Int): Long = value * MULTIPLIER[unit.coerceIn(MS, MIN)]

    fun toDisplay(ms: Long, unit: Int): Long = ms / MULTIPLIER[unit.coerceIn(MS, MIN)]

    /** ms 값을 적절한 단위로 입력란에 채운다. 음수면 비워 둔다(=기본값 사용). */
    fun fill(et: EditText, spinner: Spinner, ms: Long) {
        if (ms < 0L) {
            et.setText("")
            spinner.setSelection(MS)
            return
        }
        val unit = unitFor(ms)
        spinner.setSelection(unit)
        et.setText(toDisplay(ms, unit).toString())
    }

    /** 입력란 값을 ms로 읽는다. 비어 있거나 숫자가 아니면 [fallback]. */
    fun readMs(et: EditText, spinner: Spinner, fallback: Long): Long {
        val value = et.text?.toString()?.trim()?.toLongOrNull() ?: return fallback
        return toMs(value, spinner.selectedItemPosition)
    }

    /** 목록 등에 사람이 읽기 쉬운 형태로 표시한다. (300000 → "5분") */
    fun format(ms: Long): String = when {
        ms < 0L                              -> ""
        ms >= 60_000L && ms % 60_000L == 0L  -> "${ms / 60_000L}분"
        ms >= 1_000L && ms % 1_000L == 0L    -> "${ms / 1_000L}초"
        else                                 -> "${ms}ms"
    }

    /** 화면 회전 등으로 입력 중인 값을 그대로 되돌릴 때 사용한다. */
    fun restore(et: EditText, spinner: Spinner, value: String, unit: Int) {
        et.setText(value)
        spinner.setSelection(unit.coerceIn(MS, MIN))
    }
}
