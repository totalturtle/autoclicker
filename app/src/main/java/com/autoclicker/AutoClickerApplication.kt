package com.autoclicker

import android.app.Application

class AutoClickerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPreferences.applyTheme(this)
    }
}
