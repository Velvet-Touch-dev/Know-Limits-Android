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

    private var appliedThemeId: Int = 0

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
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_JUST_BLACK), R.style.Theme_RandomSceneApp_JustBlack),
            Pair(Pair(SettingsActivity.THEME_DARK, SettingsActivity.COLOR_PITCH_BLACK), R.style.Theme_RandomSceneApp_PitchBlack)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the theme before super.onCreate
        applyTheme()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val expectedThemeModePref = prefs.getInt("theme_mode", SettingsActivity.THEME_SYSTEM)
        val expectedColorModePref = prefs.getInt("color_mode", SettingsActivity.COLOR_DEFAULT)

        // Determine the actual current theme mode based on system settings if THEME_SYSTEM is chosen
        val systemNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val currentActualThemeMode = when (expectedThemeModePref) {
            SettingsActivity.THEME_LIGHT -> SettingsActivity.THEME_LIGHT
            SettingsActivity.THEME_DARK -> SettingsActivity.THEME_DARK
            else -> if (systemNightMode == Configuration.UI_MODE_NIGHT_YES) SettingsActivity.THEME_DARK else SettingsActivity.THEME_LIGHT
        }

        // Adjust color mode if "Just Black" or "Pitch Black" is selected with light theme
        val actualExpectedColorMode = if ((expectedColorModePref == SettingsActivity.COLOR_JUST_BLACK || expectedColorModePref == SettingsActivity.COLOR_PITCH_BLACK) && currentActualThemeMode != SettingsActivity.THEME_DARK) {
            SettingsActivity.COLOR_DEFAULT
        } else {
            expectedColorModePref
        }

        val expectedThemeId = THEME_MAP[Pair(currentActualThemeMode, actualExpectedColorMode)] ?: R.style.Theme_RandomSceneApp_AppColors

        if (appliedThemeId != 0 && appliedThemeId != expectedThemeId) {
            recreate()
        }
    }
    
    private fun applyTheme() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val themeModePref = prefs.getInt("theme_mode", SettingsActivity.THEME_SYSTEM)
        val colorModePref = prefs.getInt("color_mode", SettingsActivity.COLOR_DEFAULT)
        
        // Determine the actual current theme mode based on system settings if THEME_SYSTEM is chosen
        val systemNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val currentActualThemeMode = when (themeModePref) {
            SettingsActivity.THEME_LIGHT -> SettingsActivity.THEME_LIGHT
            SettingsActivity.THEME_DARK -> SettingsActivity.THEME_DARK
            else -> if (systemNightMode == Configuration.UI_MODE_NIGHT_YES) SettingsActivity.THEME_DARK else SettingsActivity.THEME_LIGHT
        }
        
        // Adjust color mode if "Just Black" or "Pitch Black" is selected with light theme
        // This ensures that if a black theme is chosen with light mode, it defaults to a compatible light color.
        val actualColorMode = if ((colorModePref == SettingsActivity.COLOR_JUST_BLACK || colorModePref == SettingsActivity.COLOR_PITCH_BLACK) && currentActualThemeMode != SettingsActivity.THEME_DARK) {
            SettingsActivity.COLOR_DEFAULT
        } else {
            colorModePref
        }
        
        // Set theme resource
        val themeId = THEME_MAP[Pair(currentActualThemeMode, actualColorMode)] ?: R.style.Theme_RandomSceneApp_AppColors
        setTheme(themeId)
        appliedThemeId = themeId // Store the applied theme ID
    }
}
