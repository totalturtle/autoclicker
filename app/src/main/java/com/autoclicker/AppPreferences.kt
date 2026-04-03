package com.autoclicker

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object AppPreferences {

    private const val PREFS = "app_prefs"
    private const val KEY_THEME = "theme_mode"

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
