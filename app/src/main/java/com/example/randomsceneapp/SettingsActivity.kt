package com.example.randomsceneapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView

class SettingsActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle

    companion object {
        // Theme mode constants
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_SYSTEM = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
            
            // Get current theme
            val currentTheme = loadThemeSetting()
            
            // Setup theme selector dialog
            themeCard.setOnClickListener {
                // Inflate the dialog layout
                val dialogView = layoutInflater.inflate(R.layout.dialog_theme_settings, null)
                val radioGroup = dialogView.findViewById<RadioGroup>(R.id.theme_radio_group)
                
                // Set the current theme option
                when (currentTheme) {
                    THEME_LIGHT -> radioGroup.check(R.id.theme_light_radio)
                    THEME_DARK -> radioGroup.check(R.id.theme_dark_radio)
                    THEME_SYSTEM -> radioGroup.check(R.id.theme_system_radio)
                }
                
                // Create and show dialog
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.theme_settings)
                    .setView(dialogView)
                    .setPositiveButton(R.string.save) { _, _ ->
                        // Get selected theme
                        val selectedTheme = when (radioGroup.checkedRadioButtonId) {
                            R.id.theme_light_radio -> THEME_LIGHT
                            R.id.theme_dark_radio -> THEME_DARK
                            R.id.theme_system_radio -> THEME_SYSTEM
                            else -> THEME_SYSTEM // Default
                        }
                        
                        // Save and apply theme if changed
                        if (selectedTheme != currentTheme) {
                            saveThemeSetting(selectedTheme)
                            applyTheme(selectedTheme)
                            updateThemeSelection(selectedTheme)
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            
            // Initial update of the theme display
            updateThemeSelection(currentTheme)
        } catch (e: Exception) {
            // Just log the exception and continue
            e.printStackTrace()
        }
    }

    private fun updateThemeSelection(themeMode: Int) {
        try {
            val themeValue = findViewById<TextView>(R.id.theme_value)
            val themeTextResId = when (themeMode) {
                THEME_LIGHT -> R.string.theme_light
                THEME_DARK -> R.string.theme_dark
                else -> R.string.theme_system
            }
            themeValue.setText(themeTextResId)
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
     * Apply the selected theme
     */
    private fun applyTheme(themeMode: Int) {
        when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}