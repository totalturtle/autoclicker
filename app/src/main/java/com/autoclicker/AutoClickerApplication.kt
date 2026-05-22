package com.realbtob.autoclicker

import android.app.Application

class AutoClickerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPreferences.applyTheme(this)
        PremiumManager.init(this)
        AdManager.init(this)
    }
}
