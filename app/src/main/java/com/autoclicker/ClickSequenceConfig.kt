package com.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 단일 포인트/제스처. delayAfterMs가 음수이면 시퀀스 공통 딜레이 사용 */
data class ClickPoint(
    val x: Int,
    val y: Int,
    val label: String = "",
    val delayAfterMs: Long = -1L,
    val gesture: GestureType = GestureType.TAP,
    val endX: Int = x,
    val endY: Int = y,
    val longPressDurationMs: Long = 450L,
    val swipeDurationMs: Long = 350L,
    val trigger: TriggerCondition? = null,
    val randomVarianceMs: Long = 0L,
    val pointRepeatCount: Int = 1   // 해당 포인트 단독 반복 횟수 (1=반복 없음)
)

/**
 * 다중 포인트 시퀀스 설정.
 * repeatCount: 전체 시퀀스를 몇 번 반복할지. 0 = 무한.
 */
data class ClickSequenceConfig(
    val points: List<ClickPoint>,
    val globalDelayMs: Long,
    val repeatCount: Int
) {
    fun delayAfter(point: ClickPoint): Long {
        val base = if (point.delayAfterMs >= 0) point.delayAfterMs else globalDelayMs
        val variance = point.randomVarianceMs
        if (variance <= 0) return base
        val offset = ((Math.random() * variance * 2) - variance).toLong()
        return (base + offset).coerceAtLeast(0L)
    }

    fun toJsonString(): String {
        val arr = JSONArray()
        for (p in points) {
            arr.put(
                JSONObject().apply {
                    put("x", p.x)
                    put("y", p.y)
                    put("label", p.label)
                    put("d", p.delayAfterMs)
                    put("g", p.gesture.toJsonKey())
                    if (p.gesture == GestureType.SWIPE) {
                        put("ex", p.endX)
                        put("ey", p.endY)
                        put("sd", p.swipeDurationMs)
                    }
                    if (p.gesture == GestureType.LONG_PRESS) {
                        put("ld", p.longPressDurationMs)
                    }
                    if (p.trigger != null) {
                        put("trigger", JSONObject().apply {
                            put("cx", p.trigger.checkX)
                            put("cy", p.trigger.checkY)
                            put("color", p.trigger.targetColor)
                            put("tol", p.trigger.tolerance)
                            put("act", p.trigger.action.name)
                            put("maxR", p.trigger.maxRetries)
                            put("rDelay", p.trigger.retryDelayMs)
                            if (p.trigger.usePointCoords) put("upc", true)
                        })
                    }
                    if (p.randomVarianceMs > 0) put("rv", p.randomVarianceMs)
                    if (p.pointRepeatCount > 1) put("pr", p.pointRepeatCount)
                }
            )
        }
        return JSONObject().apply {
            put("points", arr)
            put("globalDelay", globalDelayMs)
            put("repeat", repeatCount)
        }.toString()
    }

    companion object {
        fun fromJsonString(json: String?): ClickSequenceConfig? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(json)
                val arr = root.getJSONArray("points")
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val x = o.getInt("x")
                        val y = o.getInt("y")
                        val gesture = GestureType.fromJsonKey(o.optString("g", "tap"))
                        val trigger = if (o.has("trigger")) runCatching {
                            val t = o.getJSONObject("trigger")
                            TriggerCondition(
                                checkX = t.getInt("cx"),
                                checkY = t.getInt("cy"),
                                targetColor = t.getInt("color"),
                                tolerance = t.optInt("tol", 20),
                                action = TriggerAction.valueOf(t.optString("act", TriggerAction.SKIP.name)),
                                maxRetries = t.optInt("maxR", 5),
                                retryDelayMs = t.optLong("rDelay", 500L),
                                usePointCoords = t.optBoolean("upc", false)
                            )
                        }.getOrNull() else null
                        add(
                            ClickPoint(
                                x = x,
                                y = y,
                                label = o.optString("label", ""),
                                delayAfterMs = if (o.has("d")) o.getLong("d") else -1L,
                                gesture = gesture,
                                endX = if (o.has("ex")) o.getInt("ex") else x,
                                endY = if (o.has("ey")) o.getInt("ey") else y,
                                longPressDurationMs = o.optLong("ld", 450L).coerceIn(100L, 60_000L),
                                swipeDurationMs = o.optLong("sd", 350L).coerceIn(50L, 60_000L),
                                trigger = trigger,
                                randomVarianceMs = o.optLong("rv", 0L).coerceAtLeast(0L),
                                pointRepeatCount = o.optInt("pr", 1).coerceAtLeast(1)
                            )
                        )
                    }
                }
                ClickSequenceConfig(
                    points = list,
                    globalDelayMs = root.optLong("globalDelay", 1000L).coerceAtLeast(100L),
                    repeatCount = root.optInt("repeat", 0).coerceAtLeast(0)
                )
            }.getOrNull()
        }
    }
}

object SequencePrefs {
    private const val NAME = "click_sequence_prefs"
    private const val KEY_CONFIG = "config_json"
    private const val KEY_VOLUME_HOTKEY = "volume_hotkey"

    fun loadRawJson(context: Context): String? =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_CONFIG, null)

    fun load(context: Context): ClickSequenceConfig? =
        ClickSequenceConfig.fromJsonString(loadRawJson(context))

    fun save(context: Context, config: ClickSequenceConfig) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIG, config.toJsonString())
            .apply()
    }

    fun isVolumeHotkeyEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOLUME_HOTKEY, false)

    fun setVolumeHotkeyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VOLUME_HOTKEY, enabled)
            .apply()
    }
}
