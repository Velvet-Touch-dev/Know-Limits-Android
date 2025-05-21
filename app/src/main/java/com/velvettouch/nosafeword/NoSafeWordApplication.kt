package com.velvettouch.nosafeword

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication
import timber.log.Timber // Added Timber import

class NoSafeWordApplication : MultiDexApplication() {  // Using MultiDexApplication for MultiDex support
    
    companion object {
        // Theme resource map
        private val THEME_MAP = mapOf(
            // Light mode themes
            Pair(Pair(SettingsActivity.THEME_LIGHT, SettingsActivity.COLOR_DEFAULT), R.style.Theme_RandomSceneApp_AppColors),
            Pair(Pair(SettingsActivity.THEME_LIGHT, SettingsActivity.COLOR_PURPLE), R.style.Theme_RandomSceneApp_Purple),
            Pair(Pair(SettingsActivity.THEME_LIGHT, SettingsActivity.COLOR_PINK), R.style.Theme_RandomSceneApp_Pink),
            // Just Black is only for dark mode
            
            // Dark mode themes
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_DEFAULT), R.style.Theme_RandomSceneApp_AppColors),
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_PURPLE), R.style.Theme_RandomSceneApp_Purple),
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_PINK), R.style.Theme_RandomSceneApp_Pink),
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_JUST_BLACK), R.style.Theme_RandomSceneApp_JustBlack)
        )
    }
    
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("Timber initialized")
        }
        
        // Apply theme mode and color palette
        applyThemeSettings()
    }
    
    private fun applyThemeSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", SettingsActivity.THEME_SYSTEM)
        val colorMode = prefs.getInt("color_mode", SettingsActivity.COLOR_DEFAULT)
        
        // Apply theme mode
        when (themeMode) {
            SettingsActivity.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SettingsActivity.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        
        // Apply the appropriate theme resource
        val currentThemeMode = when (themeMode) {
            SettingsActivity.THEME_LIGHT -> SettingsActivity.THEME_LIGHT
            SettingsActivity.THEME_DARK -> SettingsActivity.THEME_DARK
            else -> {
                // For THEME_SYSTEM, determine the current system mode
                when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                    Configuration.UI_MODE_NIGHT_YES -> SettingsActivity.THEME_DARK
                    else -> SettingsActivity.THEME_LIGHT
                }
            }
        }
        
        // Check if "Just Black" theme is selected but not in dark mode
        val actualColorMode = if (colorMode == SettingsActivity.COLOR_JUST_BLACK && currentThemeMode != SettingsActivity.THEME_DARK) {
            SettingsActivity.COLOR_DEFAULT
        } else {
            colorMode
        }
        
        // Set theme resource - this doesn't actually have an effect in Application.onCreate
        // but the theme settings are applied in each Activity's onCreate/attachBaseContext
        val themeId = THEME_MAP[Pair(currentThemeMode, actualColorMode)] ?: R.style.Theme_RandomSceneApp_AppColors
        setTheme(themeId)
    }

    // Add attachBaseContext for MultiDex support
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        androidx.multidex.MultiDex.install(this)
    }
}