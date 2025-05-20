package com.velvettouch.nosafeword

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity class that applies the correct theme based on user preferences
 * All activities should extend this class to have consistent theming
 */
abstract class BaseActivity : AppCompatActivity() {

    companion object {
        // Theme resource map
        private val THEME_MAP = mapOf(
            // Light mode themes
            Pair(Pair(SettingsActivity.THEME_LIGHT, SettingsActivity.COLOR_DEFAULT), R.style.Theme_RandomSceneApp_Pink),
            Pair(Pair(SettingsActivity.THEME_LIGHT, SettingsActivity.COLOR_PURPLE), R.style.Theme_RandomSceneApp_Purple),
            // Just Black is only for dark mode
            
            // Dark mode themes
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_DEFAULT), R.style.Theme_RandomSceneApp_AppColors),
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_PURPLE), R.style.Theme_RandomSceneApp_Purple),
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_JUST_BLACK), R.style.Theme_RandomSceneApp_JustBlack)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the theme before super.onCreate
        applyTheme()
        super.onCreate(savedInstanceState)
    }
    
    private fun applyTheme() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", SettingsActivity.THEME_SYSTEM)
        val colorMode = prefs.getInt("color_mode", SettingsActivity.COLOR_DEFAULT)
        
        // Determine the current theme mode (light/dark)
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
        
        // Set theme resource
        val themeId = THEME_MAP[Pair(currentThemeMode, actualColorMode)] ?: R.style.Theme_RandomSceneApp_AppColors
        setTheme(themeId)
    }
}