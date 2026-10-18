package com.realbtob.autoclicker

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppPreferences {

    private const val PREFS = "app_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_AUTO_LINK = "auto_link_enabled"

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
