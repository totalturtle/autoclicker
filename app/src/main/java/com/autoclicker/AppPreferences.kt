package com.realbtob.autoclicker

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppPreferences {

    private const val PREFS = "app_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_AUTO_LINK = "auto_link_enabled"
    private const val KEY_SIMPLE_START = "simple_mode_start"
    private const val KEY_SIMPLE_MULTI = "simple_mode_multi"
    private const val KEY_OVERLAY_BUTTON = "overlay_button_enabled"

    /** 실행 중 화면에 떠 있는 조작 버튼을 보여 줄지 (기본 켜짐) */
    fun isOverlayButtonEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERLAY_BUTTON, true)

    fun setOverlayButtonEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OVERLAY_BUTTON, enabled).apply()
    }

    /**
     * 앱을 열 때 간단 모드로 시작할지.
     * 정한 적이 없으면 새 사용자(저장된 설정이 없는 경우)만 간단 모드로 연다.
     */
    fun startInSimpleMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_SIMPLE_START)) return prefs.getBoolean(KEY_SIMPLE_START, true)
        return SequencePrefs.load(context)?.points.isNullOrEmpty()
    }

    fun setSimpleModeStart(context: Context, simple: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SIMPLE_START, simple).apply()
    }

    /** 간단 모드에서 "여러 곳 순서대로"를 고른 상태인지 */
    fun isSimpleMulti(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SIMPLE_MULTI, false)

    fun setSimpleMulti(context: Context, multi: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SIMPLE_MULTI, multi).apply()
    }

    /** 연동된 앱 실행 시 프로필을 자동으로 불러올지 여부 (기본 켜짐) */
    fun isAutoLinkEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_LINK, true)

    fun setAutoLinkEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_LINK, enabled).apply()
    }

    fun getTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setTheme(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getTheme(context))
    }
}
