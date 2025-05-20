package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.MenuItem
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import java.util.Locale

class SettingsActivity : BaseActivity(), TextToSpeech.OnInitListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle
    
    // TTS for previewing voice
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false

    companion object {
        // Theme mode constants
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_SYSTEM = 0
        
        // Color palette constants
        const val COLOR_DEFAULT = 0
        const val COLOR_PURPLE = 1
        const val COLOR_PINK = 2
        const val COLOR_JUST_BLACK = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Initialize TextToSpeech for voice preview
        textToSpeech = TextToSpeech(this, this)

        // Set up toolbar with navigation icon
        val toolbar = findViewById<Toolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)

        // Set up drawer components
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        
        // Set up ActionBarDrawerToggle
        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        )
        drawerToggle.isDrawerIndicatorEnabled = true
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Remove header view
        navigationView.removeHeaderView(navigationView.getHeaderView(0))

        // Set up navigation view listener
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_scenes -> {
                    // Go back to main activity
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    true
                }
                R.id.nav_positions -> {
                    // Go to positions activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PositionsActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.nav_body_worship -> {
                    // Go to body worship activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, BodyWorshipActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.nav_favorites -> {
                    // Go to favorites activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, FavoritesActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.nav_settings -> {
                    // Already on settings page, just close drawer
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }

        // Set up theme selection
        setupThemeSelector()
        
        // Set up voice settings card
        setupVoiceSettingsCard()
        
        // Set up voice instructions toggle
        setupVoiceInstructionsToggle()
    }
    
    override fun onDestroy() {
        // Clean up TTS resources
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language to US English
            val result = textToSpeech.setLanguage(Locale.US)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Text-to-speech language not supported", Toast.LENGTH_SHORT).show()
            } else {
                isTtsReady = true
                
                // Try to set a female voice if available
                val voices = textToSpeech.voices
                var femaleVoiceFound = false
                
                // First, try to find high-quality female voice
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true) && 
                            (voice.name.contains("premium", ignoreCase = true) || 
                             voice.name.contains("enhanced", ignoreCase = true))) {
                            textToSpeech.voice = voice
                            femaleVoiceFound = true
                            break
                        }
                    }
                }
                
                // If no high-quality female voice, try any female voice
                if (!femaleVoiceFound) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true)) {
                            textToSpeech.voice = voice
                            break
                        }
                    }
                }
            }
        } else {
            Toast.makeText(this, "Failed to initialize text-to-speech", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred
        drawerToggle.syncState()
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        // Update the selected menu item in the drawer
        navigationView.setCheckedItem(R.id.nav_settings)
        
        // Update voice settings display
        try {
            val voiceSettingsValue = findViewById<TextView>(R.id.voice_settings_value)
            updateVoiceSettingsDisplay(voiceSettingsValue)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBackPressed() {
        // Close the drawer if it's open, otherwise proceed with normal back button behavior
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun setupThemeSelector() {
        try {
            val themeCard = findViewById<MaterialCardView>(R.id.theme_card)
            
            // Get current theme and color settings
            val currentTheme = loadThemeSetting()
            val currentColor = loadColorSetting()
            
            // Setup theme selector dialog
            themeCard.setOnClickListener {
                // Inflate the dialog layout
                val dialogView = layoutInflater.inflate(R.layout.dialog_theme_settings, null)
                val themeRadioGroup = dialogView.findViewById<RadioGroup>(R.id.theme_radio_group)
                val colorRadioGroup = dialogView.findViewById<RadioGroup>(R.id.color_radio_group)
                
                // Set the current theme option
                when (currentTheme) {
                    THEME_LIGHT -> themeRadioGroup.check(R.id.theme_light_radio)
                    THEME_DARK -> themeRadioGroup.check(R.id.theme_dark_radio)
                    THEME_SYSTEM -> themeRadioGroup.check(R.id.theme_system_radio)
                }
                
                // Set the current color palette option
                when (currentColor) {
                    COLOR_PURPLE -> colorRadioGroup.check(R.id.color_purple_radio)
                    COLOR_JUST_BLACK -> colorRadioGroup.check(R.id.color_just_black_radio)
                    else -> colorRadioGroup.check(R.id.color_default_radio) // Default is now pink
                }
                
                // Show/hide "Just Black" option based on the selected theme
                val justBlackRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.color_just_black_radio)
                val justBlackDivider = dialogView.findViewById<android.view.View>(R.id.just_black_divider)
                adjustJustBlackVisibility(justBlackRadio, justBlackDivider, themeRadioGroup.checkedRadioButtonId)
                
                // Listen for theme changes to update Just Black visibility
                themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                    adjustJustBlackVisibility(justBlackRadio, justBlackDivider, checkedId)
                    
                    // If switching away from dark mode and Just Black is selected, reset to default
                    if (checkedId != R.id.theme_dark_radio && colorRadioGroup.checkedRadioButtonId == R.id.color_just_black_radio) {
                        colorRadioGroup.check(R.id.color_default_radio)
                    }
                }
                
                // Create and show dialog
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.theme_settings)
                    .setView(dialogView)
                    .setPositiveButton(R.string.save) { _, _ ->
                        // Get selected theme
                        val selectedTheme = when (themeRadioGroup.checkedRadioButtonId) {
                            R.id.theme_light_radio -> THEME_LIGHT
                            R.id.theme_dark_radio -> THEME_DARK
                            R.id.theme_system_radio -> THEME_SYSTEM
                            else -> THEME_SYSTEM // Default
                        }
                        
                        // Get selected color palette
                        val selectedColor = when (colorRadioGroup.checkedRadioButtonId) {
                            R.id.color_purple_radio -> COLOR_PURPLE
                            R.id.color_just_black_radio -> COLOR_JUST_BLACK
                            else -> COLOR_DEFAULT
                        }
                        
                        // Save and apply settings if changed
                        val themeChanged = selectedTheme != currentTheme
                        val colorChanged = selectedColor != currentColor
                        
                        if (themeChanged) {
                            saveThemeSetting(selectedTheme)
                        }
                        
                        if (colorChanged) {
                            saveColorSetting(selectedColor)
                        }
                        
                        if (themeChanged || colorChanged) {
                            // Apply theme and color changes
                            applyThemeAndColor(selectedTheme, selectedColor)
                            
                            // Update the theme display text
                            updateThemeSelection(selectedTheme, selectedColor)
                            
                            // Show toast if color palette changed
                            if (colorChanged) {
                                Toast.makeText(this, R.string.color_palette_changed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            
            // Initial update of the theme display
            updateThemeSelection(currentTheme, currentColor)
        } catch (e: Exception) {
            // Just log the exception and continue
            e.printStackTrace()
        }
    }
    
    private fun adjustJustBlackVisibility(justBlackRadio: android.widget.RadioButton, justBlackDivider: android.view.View, themeRadioId: Int) {
        // Only show Just Black option for dark mode
        val visibility = if (themeRadioId == R.id.theme_dark_radio) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        justBlackRadio.visibility = visibility
        justBlackDivider.visibility = visibility
        
        // Clear the checked state when hiding - fixes the selection issue
        if (visibility == android.view.View.GONE && justBlackRadio.isChecked) {
            justBlackRadio.isChecked = false
        }
    }

    private fun updateThemeSelection(themeMode: Int, colorMode: Int) {
        try {
            val themeValue = findViewById<TextView>(R.id.theme_value)
            
            // Get the theme mode string
            val themeModeString = when (themeMode) {
                THEME_LIGHT -> getString(R.string.theme_light)
                THEME_DARK -> getString(R.string.theme_dark)
                else -> getString(R.string.theme_system)
            }
            
            // Get the color mode string
            val colorModeString = when (colorMode) {
                COLOR_PURPLE -> getString(R.string.color_purple)
                COLOR_JUST_BLACK -> getString(R.string.color_just_black)
                else -> getString(R.string.color_default) // Default is now pink
            }
            
            // Combine them
            if (colorMode == COLOR_DEFAULT) {
                themeValue.text = themeModeString
            } else {
                val coloredThemeText = "$themeModeString - $colorModeString"
                themeValue.text = coloredThemeText
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Load the saved theme setting from preferences
     */
    private fun loadThemeSetting(): Int {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getInt("theme_mode", THEME_SYSTEM) // Default to system theme
    }

    /**
     * Save the theme setting to preferences
     */
    private fun saveThemeSetting(themeMode: Int) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("theme_mode", themeMode).apply()
    }
    
    /**
     * Load the saved color palette setting from preferences
     */
    private fun loadColorSetting(): Int {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getInt("color_mode", COLOR_DEFAULT) // Default to default colors
    }
    
    /**
     * Save the color palette setting to preferences
     */
    private fun saveColorSetting(colorMode: Int) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("color_mode", colorMode).apply()
    }

    /**
     * Apply the selected theme and color palette
     */
    private fun applyThemeAndColor(themeMode: Int, colorMode: Int) {
        // First apply the theme mode
        when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        
        // Then save the color mode to be applied after recreating
        val isCurrentlyDarkMode = resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        // Only apply Just Black theme if in dark mode
        if (colorMode == COLOR_JUST_BLACK && !isCurrentlyDarkMode) {
            saveColorSetting(COLOR_DEFAULT)
        }
        
        // Recreate the activity to apply the theme colors
        recreate()
    }
    
    private fun setupVoiceSettingsCard() {
        try {
            val voiceSettingsCard = findViewById<MaterialCardView>(R.id.voice_settings_card)
            val voiceSettingsValue = findViewById<TextView>(R.id.voice_settings_value)
            
            // Update the display with current settings
            updateVoiceSettingsDisplay(voiceSettingsValue)
            
            // Set click listener to launch voice settings
            voiceSettingsCard.setOnClickListener {
                val intent = Intent(this, DeveloperSettingsActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateVoiceSettingsDisplay(textView: TextView) {
        try {
            // Load current voice settings
            val prefs = getSharedPreferences(DeveloperSettingsActivity.Settings.PREF_VOICE_SETTINGS, Context.MODE_PRIVATE)
            val pitch = prefs.getFloat(DeveloperSettingsActivity.Settings.PREF_VOICE_PITCH, 
                                      DeveloperSettingsActivity.Settings.DEFAULT_PITCH)
            val speed = prefs.getFloat(DeveloperSettingsActivity.Settings.PREF_VOICE_SPEED, 
                                      DeveloperSettingsActivity.Settings.DEFAULT_SPEED)
            
            // Format and display
            val displayText = String.format("Pitch: %.2f, Speed: %.2f", pitch, speed)
            textView.text = displayText
        } catch (e: Exception) {
            e.printStackTrace()
            textView.text = "Adjust voice pitch and speed"
        }
    }
    private fun setupVoiceInstructionsToggle() {
        // Try to find a card named voice_card and set up toggle
        try {
            val voiceCard = findViewById<MaterialCardView>(R.id.voice_card)
            val voiceSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.voice_switch)
            
            // Get current TTS setting
            val ttsEnabled = isTtsEnabled()
            voiceSwitch.isChecked = ttsEnabled
            
            // Set up click listener for the card
            voiceCard.setOnClickListener {
                voiceSwitch.toggle()
                saveTtsSetting(voiceSwitch.isChecked)
            }
            
            // Set up listener for the switch
            voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
                saveTtsSetting(isChecked)
            }
            
        } catch (e: Exception) {
            // Just log the exception and continue
            e.printStackTrace()
        }
    }
    
    private fun isTtsEnabled(): Boolean {
        val prefs = getSharedPreferences("com.velvettouch.nosafeword_preferences", Context.MODE_PRIVATE)
        return prefs.getBoolean(getString(R.string.pref_tts_enabled_key), true) // Default to enabled
    }
    
    private fun saveTtsSetting(enabled: Boolean) {
        val prefs = getSharedPreferences("com.velvettouch.nosafeword_preferences", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(getString(R.string.pref_tts_enabled_key), enabled).apply()
    }
}