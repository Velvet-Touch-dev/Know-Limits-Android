package com.velvettouch.nosafeword

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
// If you want to use the debug provider for emulators/non-Play Store builds:
// import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import timber.log.Timber // Assuming Timber is used or will be used
import androidx.appcompat.app.AppCompatDelegate

class MyApplication : Application() {
    
    companion object {
        // Theme mode constants
        private const val THEME_LIGHT = 1
        private const val THEME_DARK = 2
        private const val THEME_SYSTEM = 0
        
        // Color palette constants
        private const val COLOR_DEFAULT = 0
        private const val COLOR_PURPLE = 1
        private const val COLOR_PINK = 2
        private const val COLOR_JUST_BLACK = 3
        
        // Theme resource map
        private val THEME_MAP = mapOf(
            // Light mode themes
            Pair(Pair(THEME_LIGHT, COLOR_DEFAULT), R.style.Theme_RandomSceneApp_AppColors),
            Pair(Pair(THEME_LIGHT, COLOR_PURPLE), R.style.Theme_RandomSceneApp_Purple),
            Pair(Pair(THEME_LIGHT, COLOR_PINK), R.style.Theme_RandomSceneApp_Pink),
            // Just Black is only for dark mode
            
            // Dark mode themes
            Pair(Pair(THEME_DARK, COLOR_DEFAULT), R.style.Theme_RandomSceneApp_AppColors),
            Pair(Pair(THEME_DARK, COLOR_PURPLE), R.style.Theme_RandomSceneApp_Purple),
            Pair(Pair(THEME_DARK, COLOR_PINK), R.style.Theme_RandomSceneApp_Pink),
            Pair(Pair(THEME_DARK, COLOR_JUST_BLACK), R.style.Theme_RandomSceneApp_JustBlack)
        )
    }
    
    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase App (if not already done elsewhere, good to have here)
        FirebaseApp.initializeApp(this)

        // Initialize Firebase App Check
        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        firebaseAppCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
        // For testing on emulators or non-Play Store builds, you might use the debug provider:
        // firebaseAppCheck.installAppCheckProviderFactory(
        //     DebugAppCheckProviderFactory.getInstance()
        // )

        // Initialize Timber for logging, if you use it
        if (BuildConfig.DEBUG) { // Assuming BuildConfig is available
            Timber.plant(Timber.DebugTree())
        }
        
        // Apply the saved theme mode and color palette
        applyThemeSettings()
    }
    
    private fun applyThemeSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", THEME_SYSTEM)
        val colorMode = prefs.getInt("color_mode", COLOR_DEFAULT)
        
        // Apply theme mode
        when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        
        // Apply the appropriate theme resource
        val currentThemeMode = when (themeMode) {
            THEME_LIGHT -> THEME_LIGHT
            THEME_DARK -> THEME_DARK
            else -> {
                // For THEME_SYSTEM, determine the current system mode
                when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                    Configuration.UI_MODE_NIGHT_YES -> THEME_DARK
                    else -> THEME_LIGHT
                }
            }
        }
        
        // Check if "Just Black" theme is selected but not in dark mode
        val actualColorMode = if (colorMode == COLOR_JUST_BLACK && currentThemeMode != THEME_DARK) {
            COLOR_DEFAULT
        } else {
            colorMode
        }
        
        // Set theme resource
        val themeId = THEME_MAP[Pair(currentThemeMode, actualColorMode)] ?: R.style.Theme_RandomSceneApp_AppColors
        setTheme(themeId)
    }
}