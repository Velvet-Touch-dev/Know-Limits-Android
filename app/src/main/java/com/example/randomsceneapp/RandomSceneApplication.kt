package com.velvettouch.nosafeword

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication

class RandomSceneApplication : MultiDexApplication() {  // Changed from Application to MultiDexApplication
    override fun onCreate() {
        super.onCreate()
        
        // Apply theme mode
        applyThemeMode()
    }
    
    private fun applyThemeMode() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", SettingsActivity.THEME_SYSTEM)
        
        when (themeMode) {
            SettingsActivity.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SettingsActivity.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    // Add attachBaseContext for MultiDex support
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        androidx.multidex.MultiDex.install(this)
    }
}
