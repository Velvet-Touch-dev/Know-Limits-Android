package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.tts.TextToSpeech
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import com.velvettouch.nosafeword.BaseActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.SearchView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.random.Random

class PositionsActivity : BaseActivity(), TextToSpeech.OnInitListener, AddPositionDialogFragment.AddPositionDialogListener {
    
    private lateinit var positionImageView: ImageView
    private lateinit var positionNameTextView: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var autoPlayButton: MaterialButton
    private lateinit var timerTextView: TextView
    private lateinit var autoPlaySettings: LinearLayout
    private lateinit var minTimeSpinner: Spinner
    private lateinit var maxTimeSpinner: Spinner
    private lateinit var toolbar: Toolbar
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var positionsTabs: TabLayout
    private lateinit var randomizeTabContent: View
    private lateinit var libraryTabContent: View
    private lateinit var positionsLibraryRecyclerView: RecyclerView
    private lateinit var positionLibraryAdapter: PositionLibraryAdapter
    private var allPositionItems: MutableList<PositionItem> = mutableListOf()
    private lateinit var positionSearchView: SearchView
    private lateinit var resetButton: ExtendedFloatingActionButton // Changed for Reset to Default ExtendedFAB styling
    private lateinit var libraryFabContainer: LinearLayout
    private lateinit var positionFilterChipGroup: ChipGroup
    private lateinit var chipAllPositions: Chip
    private lateinit var chipCustomPositions: Chip

    // TTS variables
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false
    private var isTtsEnabled = true // Default value, will be updated from preferences
    
    // Voice settings
    private var voicePitch = 1.0f
    private var voiceSpeed = 0.9f
    
    private var positionImages: List<String> = emptyList()
    private var currentPosition: Int = -1
    private var isAutoPlayOn = false
    private var autoPlayTimer: CountDownTimer? = null
    private var minTimeSeconds = 30 // Default minimum time in seconds
    private var maxTimeSeconds = 60 // Default maximum time in seconds
    private val timeOptions = listOf(10, 15, 20, 30, 45, 60, 90, 120) // Time options in seconds
    
    // Favorites
    private var positionFavorites: MutableSet<String> = mutableSetOf()
    private var hiddenDefaultPositionNames: MutableSet<String> = mutableSetOf() // For session-persistent hiding
private var pendingPositionNavigationName: String? = null // For navigating from intent

    // Plan Night SharedPreferences
    private val gson = Gson()
    private val plannedItemsPrefsName = "PlanNightPrefs"
    private val plannedItemsKey = "plannedItemsList"

    // Voice settings constants
    companion object VoiceSettings {
        const val PREF_VOICE_SETTINGS = "voice_settings"
        const val PREF_VOICE_PITCH = "voice_pitch"
        const val PREF_VOICE_SPEED = "voice_speed"
        const val POSITION_FAVORITES_PREF = "position_favorites"
        const val HIDDEN_DEFAULT_POSITIONS_PREF = "hidden_default_positions" // New pref key
        
        // Default values
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_SPEED = 0.7f
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_positions)
        
        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)
        
        // Load TTS preference and voice settings
        val sharedPreferences = getSharedPreferences("com.velvettouch.nosafeword_preferences", MODE_PRIVATE)
        isTtsEnabled = sharedPreferences.getBoolean(getString(R.string.pref_tts_enabled_key), true)
        loadVoiceSettings()
        
        // Initialize views
        positionImageView = findViewById(R.id.position_image_view)
        positionNameTextView = findViewById(R.id.position_name_text_view)
        randomizeButton = findViewById(R.id.randomize_button)
        autoPlayButton = findViewById(R.id.auto_play_button)
        timerTextView = findViewById(R.id.timer_text_view)
        autoPlaySettings = findViewById(R.id.auto_play_settings)
        minTimeSpinner = findViewById(R.id.min_time_spinner)
        maxTimeSpinner = findViewById(R.id.max_time_spinner)
        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        
        // Show settings by default initially
        autoPlaySettings.visibility = View.VISIBLE
        
        // Set up toolbar with Material 3 style
        setSupportActionBar(toolbar)
        
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
        
        // Set up navigation view listener
        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_scenes -> {
                    // Navigate to main activity (scenes)
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish() // Close this activity after starting MainActivity
                    true
                }
                R.id.nav_positions -> {
                    // Already on positions page, just close drawer
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_body_worship -> {
                    // Launch body worship activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, BodyWorshipActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish() // Close this activity
                    true
                }
                R.id.nav_task_list -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, TaskListActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish() // Close this activity
                    true
                }
                R.id.nav_plan_night -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PlanNightActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP // Or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    finish() // Close this activity
                    true
                }
                R.id.nav_favorites -> {
                    // Navigate to favorites activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, FavoritesActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    true
                }
                R.id.nav_settings -> {
                    // Launch settings activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }
        
        // Set the correct item as selected in the navigation drawer
        navigationView.setCheckedItem(R.id.nav_positions)
        
        // Remove header view if needed
        if (navigationView.headerCount > 0) {
            navigationView.removeHeaderView(navigationView.getHeaderView(0))
        }
        
        // Set up auto play spinners
        setupSpinners()
        
        // Apply Material 3 dynamic colors
        val typedValue = android.util.TypedValue()
        if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true)) {
            randomizeButton.rippleColor = android.content.res.ColorStateList.valueOf(typedValue.data)
        }
        
        // Set up randomize button (now "Next" button) with Material motion
        randomizeButton.setOnClickListener {
            // If auto play is on, stop current timer and start a new one
            if (isAutoPlayOn) {
                // Cancel current timer
                autoPlayTimer?.cancel()
                
                // Use Material motion for transitions
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_fast)
                
                positionImageView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayRandomPosition()
                        positionImageView.startAnimation(fadeIn)
                        
                        // Start new timer
                        startAutoPlay()
                    }
                })
            } else {
                // Regular behavior when auto play is off
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_fast)
                
                positionImageView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayRandomPosition()
                        positionImageView.startAnimation(fadeIn)
                    }
                })
            }
        }
        
        // Initialize with consistent Play button text
        autoPlayButton.text = getString(R.string.play)
        autoPlayButton.setOnClickListener {
            toggleAutoPlay()
        }

        // Load all positions (assets and custom) into allPositionItems first.
        // This is crucial for displayPositionByName to find custom positions passed via intent.
        // Load hidden names FIRST, so loadAllPositionsForLibrary can use it.
        loadHiddenDefaultPositionNames() // Load hidden names

        loadAllPositionsForLibrary() // Ensures allPositionItems is populated, respecting hidden names.

        // Load position images (assets only, for the randomize tab's default pool)
        loadPositionImages()
        
        // Load position favorites
        loadPositionFavorites()
        
        // Check for specific position to display from intent AFTER allPositionItems is populated
        // This will be handled later, after RecyclerView setup, if the intent is for library navigation.
        // For now, store it if present. The original displayPositionByName might be for the randomize tab.
        // The new requirement is to navigate to the library card.
        val intentPositionName = intent.getStringExtra("DISPLAY_POSITION_NAME")
        if (intentPositionName != null) {
            pendingPositionNavigationName = intentPositionName
            // If this intent means we should NOT display a random position initially on the randomize tab,
            // then the displayRandomPosition() call below might need to be conditional.
            // For now, we'll let displayRandomPosition run, and then navigate if pending.
        }

        if (pendingPositionNavigationName == null) { // Only display random if not about to navigate
            displayRandomPosition()
        }
        
        // Initialize Tab Content Views if not already done (should be done after setContentView)
        // Ensure these IDs match your activity_positions.xml
        positionsTabs = findViewById(R.id.positions_tabs)
        randomizeTabContent = findViewById(R.id.randomize_tab_content)
        libraryTabContent = findViewById(R.id.library_tab_content)
        positionSearchView = findViewById(R.id.position_search_view) // Initialize SearchView here
        resetButton = findViewById(R.id.button_reset_to_default) // Initialize Reset Button
        libraryFabContainer = findViewById(R.id.library_fab_container) // Initialize FAB container
        positionFilterChipGroup = findViewById(R.id.position_filter_chip_group)
        chipAllPositions = findViewById(R.id.chip_all_positions)
        chipCustomPositions = findViewById(R.id.chip_custom_positions)
        // RecyclerView initialization is now inside setupLibraryRecyclerView,
        // but ensure the view ID is correct in your XML (positions_library_recycler_view)

        // Setup Tab Layout
        positionsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // Randomize
                        randomizeTabContent.visibility = View.VISIBLE
                        libraryTabContent.visibility = View.GONE
                        invalidateOptionsMenu() // Re-draw options menu for this tab
                    }
                    1 -> { // Library
                        randomizeTabContent.visibility = View.GONE
                        libraryTabContent.visibility = View.VISIBLE
                        invalidateOptionsMenu() // Re-draw options menu for this tab
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                // No action needed
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                // No action needed
            }
        })

        // Set initial tab visibility correctly
        // Assuming the first tab (Randomize) should be visible by default.
        if (positionsTabs.selectedTabPosition == 0) {
            randomizeTabContent.visibility = View.VISIBLE
            libraryTabContent.visibility = View.GONE
        } else {
            randomizeTabContent.visibility = View.GONE
            libraryTabContent.visibility = View.VISIBLE
        }
 
        setupLibraryRecyclerView() // Call to setup RecyclerView, adapter will use allPositionItems
        // loadAllPositionsForLibrary() was called earlier and populated allPositionItems.
        // Now that the adapter is initialized, update it with the loaded items.
        if (::positionLibraryAdapter.isInitialized) {
            positionLibraryAdapter.updatePositions(ArrayList(allPositionItems))
        }
        setupSearch()
        setupPositionFilterChips() // Setup for the new filter chips
        setupFab()
        setupResetButton() // Call setup for reset button

        // Initial filter load for the library
        filterPositionsLibrary(positionSearchView.query?.toString())

        // Handle pending navigation from intent after all UI is set up
        pendingPositionNavigationName?.let { name ->
            // No need to check for RecyclerView readiness here as displayPositionByName handles its own UI.
            // Ensure positionsTabs is ready for tab selection.
             if (::positionsTabs.isInitialized) {
                navigateToPositionInRandomizeView(name)
            }
            pendingPositionNavigationName = null // Clear after attempting
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            setIntent(it) // Update the activity's intent
            val positionNameToDisplay = it.getStringExtra("DISPLAY_POSITION_NAME")
            if (positionNameToDisplay != null) {
                if (::positionsTabs.isInitialized) {
                    navigateToPositionInRandomizeView(positionNameToDisplay)
                } else {
                    // If UI not fully ready, store for onCreate to pick up.
                    pendingPositionNavigationName = positionNameToDisplay
                }
            }
        }
    }

    private fun navigateToPositionInRandomizeView(positionName: String) {
        if (!::positionsTabs.isInitialized) {
            // Not ready to navigate
            return
        }

        // Ensure allPositionItems is populated before calling displayPositionByName
        // This should be guaranteed by the call order in onCreate.
        if (allPositionItems.isEmpty() && !positionImages.isEmpty()) {
             // This might indicate an issue if allPositionItems is expected but empty.
             // However, displayPositionByName primarily uses allPositionItems.
             // loadAllPositionsForLibrary() should have populated it.
        }

        positionsTabs.getTabAt(0)?.select() // Select Randomize tab (index 0)
        displayPositionByName(positionName) // Display the position in the main view of Randomize tab
    }

    private fun setupResetButton() {
        resetButton.setOnClickListener {
            showResetConfirmationDialog()
        }
    }

    private fun showResetConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_to_default))
            .setMessage("Are you sure you want to reset to default positions? This will delete custom positions and restore default positions.")
            .setPositiveButton(getString(R.string.reset)) { dialog, _ ->
                resetToDefaultPositions()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun resetToDefaultPositions() {
        // Clear the set of hidden default positions and its SharedPreferences
        hiddenDefaultPositionNames.clear()
        val prefs = getSharedPreferences(HIDDEN_DEFAULT_POSITIONS_PREF, Context.MODE_PRIVATE)
        prefs.edit().remove("hidden_names").commit() // Changed to commit()

        // Delete files of custom positions
        val customPositions = allPositionItems.filter { !it.isAsset }
        for (customPosition in customPositions) {
            try {
                val file = File(customPosition.imageName)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Error deleting file for ${customPosition.name}", Toast.LENGTH_SHORT).show()
            }
        }

        // Clear current list and reload only defaults
        allPositionItems.clear()
        loadAllPositionsForLibrary(true) // Pass a flag to indicate reset

        Toast.makeText(this, "Positions reset to default.", Toast.LENGTH_SHORT).show()
    }


    override fun onPositionAdded(name: String, imageUri: Uri?) {
        if (imageUri == null) {
            Toast.makeText(this, "Image URI is null, cannot save.", Toast.LENGTH_LONG).show()
            return
        }

        // Create a more robust unique filename
        val timestamp = System.currentTimeMillis()
        val safeName = name.replace("\\s+".toRegex(), "_").replace("[^a-zA-Z0-9_\\-]".toRegex(), "")
        val fileName = "position_${safeName}_${timestamp}.jpg"

        val newPositionFile = File(getExternalFilesDir("positions"), fileName)
        try {
            contentResolver.openInputStream(imageUri)?.use { inputStream ->
                FileOutputStream(newPositionFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            // Add to the list and update adapter
            val newPositionItem = PositionItem(name, newPositionFile.absolutePath, false) // isAsset = false
            allPositionItems.add(newPositionItem)
            allPositionItems.sortBy { it.name } // Keep it sorted
            positionLibraryAdapter.updatePositions(ArrayList(allPositionItems))
            Toast.makeText(this, "Position '$name' added.", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving image: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupFab() {
        val fab: FloatingActionButton = findViewById(R.id.fab_add_position)
        fab.setOnClickListener {
            val dialog = AddPositionDialogFragment()
            dialog.listener = this
            dialog.show(supportFragmentManager, "AddPositionDialogFragment")
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu
        menuInflater.inflate(R.menu.position_menu, menu)

        // Update icon visibility based on the selected tab
        val favoriteItem = menu.findItem(R.id.action_favorite_position)
        val addToPlanItem = menu.findItem(R.id.action_add_to_plan_position)

        if (positionsTabs.selectedTabPosition == 0) { // Randomize tab
            updateFavoriteIcon(menu) // Update favorite icon state (filled/empty)
            favoriteItem?.isVisible = true
            addToPlanItem?.isVisible = true
        } else { // Library tab
            favoriteItem?.isVisible = false
            addToPlanItem?.isVisible = false
        }
        
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        
        return when (item.itemId) {
            R.id.action_favorite_position -> {
                // Only allow favorite if on randomize tab and the item is visible
                if (positionsTabs.selectedTabPosition == 0 && item.isVisible) {
                    toggleFavorite()
                }
                true
            }
            R.id.action_add_to_plan_position -> {
                // Only allow add to plan if on randomize tab and the item is visible
                if (positionsTabs.selectedTabPosition == 0 && item.isVisible) {
                    addCurrentPositionToPlan()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun loadPositionFavorites() {
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        positionFavorites = prefs.getStringSet("favorite_position_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun savePositionFavorites() {
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_position_names", positionFavorites).apply()
    }

    private fun loadHiddenDefaultPositionNames() {
        val prefs = getSharedPreferences(HIDDEN_DEFAULT_POSITIONS_PREF, Context.MODE_PRIVATE)
        hiddenDefaultPositionNames = prefs.getStringSet("hidden_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveHiddenDefaultPositionNames() {
        val prefs = getSharedPreferences(HIDDEN_DEFAULT_POSITIONS_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("hidden_names", hiddenDefaultPositionNames).commit() // Changed to commit()
    }
    
    private fun toggleFavorite() {
        val currentDisplayedName = positionNameTextView.text.toString()
        if (currentDisplayedName.isBlank() || currentDisplayedName == "No position images found") {
            Toast.makeText(this, "No position selected to favorite.", Toast.LENGTH_SHORT).show()
            return
        }

        // Use currentDisplayedName for favoriting, as it's reliable for both assets and custom items.
        // The name is already capitalized correctly by displayPositionByName or displayCurrentPosition.
        val positionNameToFavorite = currentDisplayedName

        if (positionFavorites.contains(positionNameToFavorite)) {
            // Remove from favorites
            positionFavorites.remove(positionNameToFavorite)
            
            // Show toast
            Toast.makeText(this, "Removed from favorites", Toast.LENGTH_SHORT).show()
        } else {
            // Add to favorites
            positionFavorites.add(positionNameToFavorite)
            
            // Show toast
            Toast.makeText(this, "Added to favorites", Toast.LENGTH_SHORT).show()
        }
        
        // Save changes
        savePositionFavorites()
        
        // Update favorite icon
        invalidateOptionsMenu()
    }
    
    private fun updateFavoriteIcon(menu: Menu? = null) {
        val currentDisplayedName = positionNameTextView.text.toString()
        if (currentDisplayedName.isBlank() || currentDisplayedName == "No position images found") {
            // No specific position is displayed, or it's a placeholder.
            // Ensure the favorite icon is in its default (empty) state.
            menu?.findItem(R.id.action_favorite_position)?.setIcon(R.drawable.ic_favorite)
            return
        }

        // Use currentDisplayedName to check favorite status.
        // This name should be the one displayed and correctly formatted.
        val positionNameToCheck = currentDisplayedName
        val isFavorite = positionFavorites.contains(positionNameToCheck)

        // Update the icon in the menu
        menu?.findItem(R.id.action_favorite_position)?.let { menuItem ->
            menuItem.setIcon(
                if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
            )
        }
    }
    
    private fun setupSpinners() {
        // Create adapter with time options
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            timeOptions.map { "$it ${getString(R.string.seconds)}" }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        // Set up min time spinner
        minTimeSpinner.adapter = adapter
        minTimeSpinner.setSelection(timeOptions.indexOf(minTimeSeconds).takeIf { it >= 0 } ?: 3) // Default to 30s
        minTimeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                minTimeSeconds = timeOptions[position]
                // Ensure min time is always less than or equal to max time
                if (minTimeSeconds > maxTimeSeconds) {
                    val maxPosition = timeOptions.indexOf(minTimeSeconds).takeIf { it >= 0 } ?: 5
                    maxTimeSpinner.setSelection(maxPosition)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Set up max time spinner
        maxTimeSpinner.adapter = adapter
        maxTimeSpinner.setSelection(timeOptions.indexOf(maxTimeSeconds).takeIf { it >= 0 } ?: 5) // Default to 60s
        maxTimeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                maxTimeSeconds = timeOptions[position]
                // Ensure max time is always greater than or equal to min time
                if (maxTimeSeconds < minTimeSeconds) {
                    val minPosition = timeOptions.indexOf(maxTimeSeconds).takeIf { it >= 0 } ?: 3
                    minTimeSpinner.setSelection(minPosition)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun toggleAutoPlaySettings() {
        // Toggle visibility of auto play settings
        autoPlaySettings.visibility = if (autoPlaySettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
    
    private fun toggleAutoPlay() {
        isAutoPlayOn = !isAutoPlayOn
        
        if (isAutoPlayOn) {
            // Start auto play
            startAutoPlay()
            
            // Change button text to Pause
            autoPlayButton.text = getString(R.string.pause)
            
            // Show timer
            timerTextView.visibility = View.VISIBLE
            
            // Update layout weights to accommodate timer
            updateButtonLayout()
        } else {
            // Stop auto play
            stopAutoPlay()
            
            // Change button text to Play
            autoPlayButton.text = getString(R.string.play)
            
            // Hide timer
            timerTextView.visibility = View.GONE
            
            // Update layout weights for two-button layout
            updateButtonLayout()
        }
    }
    
    private fun updateButtonLayout() {
        val buttonsLayout = findViewById<LinearLayout>(R.id.buttons_container)
        
        if (timerTextView.visibility == View.VISIBLE) {
            // Three-element layout (Play, Timer, Next)
            for (i in 0 until buttonsLayout.childCount) {
                val view = buttonsLayout.getChildAt(i)
                val params = view.layoutParams as LinearLayout.LayoutParams
                params.weight = 1.0f
                view.layoutParams = params
            }
        } else {
            // Two-element layout (Play, Next)
            autoPlayButton.layoutParams = LinearLayout.LayoutParams(
                0, 
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                1.0f
            ).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.button_margin)
            }
            
            randomizeButton.layoutParams = LinearLayout.LayoutParams(
                0, 
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                1.0f
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.button_margin)
            }
        }
    }
    
    private fun startAutoPlay() {
        // Cancel any existing timer
        autoPlayTimer?.cancel()
        
        // Generate random duration between min and max time
        val randomDuration = Random.nextInt(minTimeSeconds, maxTimeSeconds + 1) * 1000L
        
        // Create and start countdown timer
        autoPlayTimer = object : CountDownTimer(randomDuration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // Update timer text
                val secondsRemaining = millisUntilFinished / 1000
                timerTextView.text = formatTime(secondsRemaining)
            }
            
            override fun onFinish() {
                // Display next random position
                val fadeOut = AnimationUtils.loadAnimation(this@PositionsActivity, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this@PositionsActivity, R.anim.fade_in_fast)
                
                positionImageView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayRandomPosition()
                        positionImageView.startAnimation(fadeIn)
                        
                        // Start new timer if auto play is still on
                        if (isAutoPlayOn) {
                            startAutoPlay()
                        }
                    }
                })
            }
        }.start()
    }
    
    private fun stopAutoPlay() {
        // Cancel timer
        autoPlayTimer?.cancel()
        autoPlayTimer = null
    }
    
    private fun formatTime(seconds: Long): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) {
            String.format("%d:%02d", minutes, remainingSeconds)
        } else {
            String.format("0:%02d", remainingSeconds)
        }
    }
    
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred
        drawerToggle.syncState()
    }
    
    override fun onBackPressed() {
        // Close the drawer if it's open, otherwise proceed with normal back button behavior
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop auto play when activity is paused
        if (isAutoPlayOn) {
            stopAutoPlay()
            isAutoPlayOn = false
            autoPlayButton.setIconResource(R.drawable.ic_play_24)
            timerTextView.visibility = View.GONE
        }
        
        // Stop TTS if it's speaking
        if (::textToSpeech.isInitialized && textToSpeech.isSpeaking) {
            textToSpeech.stop()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Shutdown TTS when activity is destroyed
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }
    
    private fun loadPositionImages() {
        try {
            positionImages = assets.list("positions")?.filter { 
                it.endsWith(".jpg", ignoreCase = true) || 
                it.endsWith(".jpeg", ignoreCase = true) 
            } ?: emptyList()
            
            if (positionImages.isEmpty()) {
                // Create a placeholder image
                positionNameTextView.text = "No position images found"
                positionImageView.setImageResource(R.drawable.ic_image_24)
                positionImageView.setColorFilter(android.graphics.Color.GRAY)
                
                Toast.makeText(
                    this, 
                    "No position images found. Please add images to the 'positions' folder.", 
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(
                this, 
                "Error loading position images: ${e.message}", 
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun displayPositionByName(targetPositionName: String) {
        val positionItem = allPositionItems.find { it.name.equals(targetPositionName, ignoreCase = true) }

        if (positionItem != null) {
            positionNameTextView.text = positionItem.name
            try {
                if (positionItem.isAsset) {
                    // Find the asset in positionImages and call displayCurrentPosition
                    val assetIndex = positionImages.indexOfFirst { assetFilename ->
                        assetFilename.equals(positionItem.imageName, ignoreCase = true)
                    }
                    if (assetIndex != -1) {
                        currentPosition = assetIndex
                        displayCurrentPosition() // Handles image loading, TTS, and invalidateOptionsMenu
                    } else {
                        // Asset item from allPositionItems not found in positionImages list, this is an inconsistency
                        Toast.makeText(this, "Asset '${positionItem.name}' not found in preloaded assets.", Toast.LENGTH_SHORT).show()
                        positionImageView.setImageResource(R.drawable.ic_image_24) // Placeholder
                        currentPosition = -1
                        invalidateOptionsMenu()
                    }
                } else {
                    // Custom position (not an asset)
                    val imageFile = File(positionItem.imageName) // imageName is absolute path
                    if (imageFile.exists()) {
                        positionImageView.setImageURI(Uri.fromFile(imageFile))
                    } else {
                        positionImageView.setImageResource(R.drawable.ic_image_24) // Placeholder
                        Toast.makeText(this, "Image file not found for ${positionItem.name}", Toast.LENGTH_SHORT).show()
                    }
                    currentPosition = -1 // Indicate it's not a default asset for favoriting via currentPosition
                    speakPositionName(positionItem.name)
                    invalidateOptionsMenu() // Update favorite icon status
                }
            } catch (e: IOException) {
                e.printStackTrace()
                positionImageView.setImageResource(R.drawable.ic_image_24) // Placeholder
                Toast.makeText(this, "Error loading image for ${positionItem.name}", Toast.LENGTH_SHORT).show()
                currentPosition = -1
                invalidateOptionsMenu()
            }
        } else {
            Toast.makeText(this, "Position '$targetPositionName' not found.", Toast.LENGTH_SHORT).show()
            // Fallback to a random position if the named position isn't found in allPositionItems
            displayRandomPosition()
        }
    }

    private fun displayRandomPosition() {
        if (positionImages.isEmpty()) {
            positionNameTextView.text = "No position images found"
            return
        }
        
        // Get a random position that's different from the current one if possible
        var newIndex: Int
        do {
            newIndex = Random.nextInt(positionImages.size)
        } while (positionImages.size > 1 && newIndex == currentPosition)
        
        currentPosition = newIndex
        displayCurrentPosition()
    }
    
    private fun displayCurrentPosition() {
        if (currentPosition < 0 || currentPosition >= positionImages.size) return
        
        val imageName = positionImages[currentPosition]
        
        try {
            // Load and display the image
            val inputStream = assets.open("positions/$imageName")
            val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
            positionImageView.setImageDrawable(drawable)
            
            // Display the image name without the extension
            val nameWithoutExtension = imageName.substringBeforeLast(".")
            val positionName = nameWithoutExtension.replace("_", " ").capitalize()
            positionNameTextView.text = positionName
            
            // Speak the position name with TTS
            speakPositionName(positionName)
            
            // Apply dynamic tint to the randomize button based on your theme
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            randomizeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
            
            // Update favorite icon
            invalidateOptionsMenu()
            
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(
                this, 
                "Error loading image: ${e.message}", 
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
private fun setupLibraryRecyclerView() {
    // Ensure positionsLibraryRecyclerView is initialized before use
    if (!::positionsLibraryRecyclerView.isInitialized) {
         positionsLibraryRecyclerView = findViewById(R.id.positions_library_recycler_view)
    }

    positionLibraryAdapter = PositionLibraryAdapter(this, mutableListOf(), // Initial empty list
        onItemClick = { positionItem ->
            // Handle item click: Display the position in the "Randomize" tab
            displayPositionByName(positionItem.name) // Use existing function
            positionsTabs.getTabAt(0)?.select() // Switch to Randomize tab
            invalidateOptionsMenu() // Ensure favorite icon updates
        },
            onDeleteClick = { positionItem ->
                val originalPosition = allPositionItems.indexOf(positionItem)
                if (originalPosition != -1) {
                    // Temporarily remove from list and update adapter
                    allPositionItems.remove(positionItem)
                    positionLibraryAdapter.updatePositions(ArrayList(allPositionItems))

                    Snackbar.make(findViewById(android.R.id.content), "'${positionItem.name}' deleted.", Snackbar.LENGTH_LONG)
                        .setAnchorView(libraryFabContainer)
                        .setAction("Undo") {
                            // Add item back to its original position
                            allPositionItems.add(originalPosition, positionItem)
                            positionLibraryAdapter.updatePositions(ArrayList(allPositionItems))
                            // If it was a default item that was "hidden", remove from hidden list
                            if (positionItem.isAsset) {
                                hiddenDefaultPositionNames.remove(positionItem.name)
                                saveHiddenDefaultPositionNames()
                            }
                            Toast.makeText(this, "'${positionItem.name}' restored.", Toast.LENGTH_SHORT).show()
                        }
                        .addCallback(object : Snackbar.Callback() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                if (event != DISMISS_EVENT_ACTION) { // If not dismissed by "Undo"
                                    // Perform actual deletion
                                    if (!positionItem.isAsset) {
                                        // This is a custom position, delete its file
                                        try {
                                            val file = File(positionItem.imageName)
                                            if (file.exists()) {
                                                file.delete()
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            // Consider showing an error to the user if file deletion fails
                                        }
                                    } else {
                                        // This is a default (asset) position, add to hidden list
                                        hiddenDefaultPositionNames.add(positionItem.name)
                                        saveHiddenDefaultPositionNames() // Save the updated hidden list
                                    }
                                    // Remove from favorites if it exists there (after permanent delete)
                                    if (positionFavorites.contains(positionItem.name)) {
                                        positionFavorites.remove(positionItem.name)
                                        savePositionFavorites()
                                    }
                                    // No need to update adapter again as it's already removed visually
                                }
                            }
                        })
                        .show()
                } else {
                     Toast.makeText(this, "Could not find '${positionItem.name}' in the list to remove.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    positionsLibraryRecyclerView.adapter = positionLibraryAdapter
        // Use GridLayoutManager for a card-like appearance, adjust spanCount as needed
        positionsLibraryRecyclerView.layoutManager = GridLayoutManager(this, 2) // 2 columns
    }

    private fun loadAllPositionsForLibrary(isReset: Boolean = false) {
        if (isReset) {
            allPositionItems.clear()
            // hiddenDefaultPositionNames is cleared by resetToDefaultPositions before calling this
        } else {
            // For a normal load (e.g., onCreate, onResume if activity was destroyed),
            // always clear to ensure we rebuild respecting hiddenDefaultPositionNames.
            allPositionItems.clear()
        }
        // Now allPositionItems is definitely empty if it's a normal load,
        // or empty if it's a reset.

        // Load from assets
        try {
            val assetManager = assets
            val assetFiles = assetManager.list("positions")
            assetFiles?.forEach { fileName ->
                if (fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".png", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true)) {
                    val nameWithoutExtension = fileName.substringBeforeLast(".")
                    val positionName = nameWithoutExtension.replace("_", " ").capitalizeWords()
                    // Only add asset if it's not in the hidden set
                    if (!hiddenDefaultPositionNames.contains(positionName)) {
                        allPositionItems.add(PositionItem(positionName, fileName, true)) // isAsset = true
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error loading asset positions", Toast.LENGTH_SHORT).show()
        }

        // Load from app's external files directory only if not a reset
        if (!isReset) {
            val customPositionsDir = getExternalFilesDir("positions")
            if (customPositionsDir != null && customPositionsDir.exists()) {
                customPositionsDir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true))) {
                        val parts = file.nameWithoutExtension.split("_")
                        val positionName = if (parts.size > 2 && parts.first() == "position" && parts.last().toLongOrNull() != null) {
                            parts.drop(1).dropLast(1).joinToString(" ").capitalizeWords()
                        } else {
                            file.nameWithoutExtension.replace("_", " ").capitalizeWords()
                        }
                        // Add custom position only if it's not already in the list (e.g. from assets if names collide)
                        if (allPositionItems.none { it.name.equals(positionName, ignoreCase = true) }) {
                            allPositionItems.add(PositionItem(positionName, file.absolutePath, false)) // isAsset = false
                        }
                    }
                }
            }
        }

        allPositionItems.sortBy { it.name }
        if (::positionLibraryAdapter.isInitialized) {
            positionLibraryAdapter.updatePositions(ArrayList(allPositionItems))
        }
    }

    // Helper function to capitalize first letter of each word
    fun String.capitalizeWords(): String = split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun setupSearch() {
        positionSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterPositionsLibrary(query) // Use new filter method
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterPositionsLibrary(newText) // Use new filter method
                return false
            }
        })
    }

    private fun setupPositionFilterChips() {
        chipAllPositions.setOnCheckedChangeListener { _, _ ->
            if (!chipAllPositions.isChecked && !chipCustomPositions.isChecked) {
                chipAllPositions.isChecked = true // Keep at least one selected
                Toast.makeText(this, "At least one filter must be selected.", Toast.LENGTH_SHORT).show()
            } else {
                filterPositionsLibrary(positionSearchView.query?.toString())
            }
        }

        chipCustomPositions.setOnCheckedChangeListener { _, _ ->
            if (!chipAllPositions.isChecked && !chipCustomPositions.isChecked) {
                chipCustomPositions.isChecked = true // Keep at least one selected
                Toast.makeText(this, "At least one filter must be selected.", Toast.LENGTH_SHORT).show()
            } else {
                filterPositionsLibrary(positionSearchView.query?.toString())
            }
        }
    }

    private fun filterPositionsLibrary(query: String?) {
        if (!::positionLibraryAdapter.isInitialized) {
            return // Not ready to filter yet
        }

        val showAll = chipAllPositions.isChecked
        val showCustom = chipCustomPositions.isChecked

        val filteredList = allPositionItems.filter { position ->
            // Corrected typeMatch logic:
            val typeMatchLogic = when {
                showAll && showCustom -> true // Both checked, show all types
                showAll -> position.isAsset // Only "All" (meaning default/asset) is checked
                showCustom -> !position.isAsset // Only "Custom" is checked
                else -> false // Should not happen due to check listeners, but as a fallback
            }

            val queryMatch = if (query.isNullOrBlank()) {
                true
            } else {
                position.name.contains(query, ignoreCase = true)
            }
            typeMatchLogic && queryMatch
        }
        positionLibraryAdapter.updatePositions(ArrayList(filteredList))
        // Scroll to top after filtering
        if (::positionsLibraryRecyclerView.isInitialized) {
            positionsLibraryRecyclerView.scrollToPosition(0)
        }
    }

    //This original filterPositions method is no longer directly used by search,
    //but can be kept if other parts of the app call it, or removed if not.
    //For now, I'll comment it out to avoid confusion.
    /*
    private fun filterPositions(query: String?) {
        val filteredList = if (query.isNullOrEmpty()) {
            allPositionItems // Show all if query is empty
        } else {
            allPositionItems.filter {
                it.name.contains(query, ignoreCase = true)
            }
        }
        positionLibraryAdapter.updatePositions(filteredList)
    }
    */

    // TTS initialization callback
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
                            voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)) {
                            textToSpeech.voice = voice
                            femaleVoiceFound = true
                            break
                        }
                    }
                }
                
                // If no premium female voice found, try any female voice
                if (!femaleVoiceFound) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true)) {
                            textToSpeech.voice = voice
                            femaleVoiceFound = true
                            break
                        }
                    }
                }
                
                // Set speech parameters
                textToSpeech.setPitch(voicePitch)
                textToSpeech.setSpeechRate(voiceSpeed)
                
                // If position already displayed, speak it
                if (currentPosition >= 0 && currentPosition < positionImages.size) {
                    val imageName = positionImages[currentPosition]
                    val nameWithoutExtension = imageName.substringBeforeLast(".")
                    val positionName = nameWithoutExtension.replace("_", " ").capitalize()
                    speakPositionName(positionName)
                }
            }
        } else {
            Toast.makeText(this, "Failed to initialize text-to-speech", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun speakPositionName(positionName: String) {
        if (isTtsReady && isTtsEnabled) {
            // Stop any ongoing speech
            if (textToSpeech.isSpeaking) {
                textToSpeech.stop()
            }
            
            // Create a Bundle for speech parameters
            val params = Bundle()
            
            // Speak the position name
            textToSpeech.speak(positionName, TextToSpeech.QUEUE_FLUSH, params, "position_name")
        }
    }
    
    /**
     * Load voice settings from shared preferences
     */
    private fun loadVoiceSettings() {
        val prefs = getSharedPreferences(VoiceSettings.PREF_VOICE_SETTINGS, Context.MODE_PRIVATE)
        voicePitch = prefs.getFloat(VoiceSettings.PREF_VOICE_PITCH, VoiceSettings.DEFAULT_PITCH)
        voiceSpeed = prefs.getFloat(VoiceSettings.PREF_VOICE_SPEED, VoiceSettings.DEFAULT_SPEED)
    }
private fun addCurrentPositionToPlan() {
        val currentPositionName = positionNameTextView.text.toString()
        if (currentPositionName.isBlank() || currentPositionName == "No position images found") {
            Toast.makeText(this, "No position selected to add to plan.", Toast.LENGTH_SHORT).show()
            return
        }

        val sharedPreferences = getSharedPreferences(plannedItemsPrefsName, Context.MODE_PRIVATE)
        val json = sharedPreferences.getString(plannedItemsKey, null)
        val type = object : TypeToken<MutableList<PlannedItem>>() {}.type
        val plannedItems: MutableList<PlannedItem> = gson.fromJson(json, type) ?: mutableListOf()

        // Check if the item already exists
        val existingItem = plannedItems.find { it.name == currentPositionName && it.type == "Position" }
        if (existingItem != null) {
            Toast.makeText(this, "Position \"$currentPositionName\" is already in your plan.", Toast.LENGTH_SHORT).show()
            return
        }

        // Find the PositionItem to get its image path
        val currentPositionItem = allPositionItems.find { it.name.equals(currentPositionName, ignoreCase = true) }
        val imagePath = currentPositionItem?.imageName ?: "Unknown image" // Default if not found

        // Add new item
        // The ID is auto-generated by PlannedItem's default constructor
        val newItem = PlannedItem(
            name = currentPositionName,
            type = "Position",
            details = imagePath // Store only the image path (asset filename or full file path)
        )
        plannedItems.add(newItem)

        // Save updated list
        val editor = sharedPreferences.edit()
        val updatedJson = gson.toJson(plannedItems)
        editor.putString(plannedItemsKey, updatedJson)
        editor.apply()

        Toast.makeText(this, "Position \"$currentPositionName\" added to Plan Your Night.", Toast.LENGTH_SHORT).show()
    }
}