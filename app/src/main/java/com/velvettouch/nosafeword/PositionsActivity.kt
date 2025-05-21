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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.velvettouch.nosafeword.BaseActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
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
import kotlinx.coroutines.launch

class PositionsActivity : BaseActivity(), TextToSpeech.OnInitListener, AddPositionDialogFragment.AddPositionDialogListener {
    
    private lateinit var positionImageView: ImageView
    private lateinit var positionNameTextView: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var autoPlayButton: MaterialButton
    private lateinit var previousButton: MaterialButton // Added previous button
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

    private lateinit var positionsViewModel: PositionsViewModel

    // TTS variables
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false
    private var isTtsEnabled = true // Default value, will be updated from preferences
    
    // Voice settings
    private var voicePitch = 1.0f
    private var voiceSpeed = 0.9f
    
    private var positionImages: List<String> = emptyList()
    private var currentPosition: Int = -1
    private var previousPosition: Int = -1 // Used for non-history based previous, will be replaced by history
    private var positionHistory: MutableList<Int> = mutableListOf()
    private var positionHistoryPosition: Int = -1
    private var isAutoPlayOn = false
    private var autoPlayTimer: CountDownTimer? = null
    private var minTimeSeconds = 30 // Default minimum time in seconds
    private var maxTimeSeconds = 60 // Default maximum time in seconds
    private val timeOptions = listOf(10, 15, 20, 30, 45, 60, 90, 120) // Time options in seconds
    
    // Favorites - SharedPreferences for asset favorites.
    // Non-asset favorites are stored in Firestore via PositionItem.isFavorite.
    private var assetPositionFavorites: MutableSet<String> = mutableSetOf() // Used for local asset favorites
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
        
        // Initialize ViewModel
        positionsViewModel = ViewModelProvider(this)[PositionsViewModel::class.java]

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
        previousButton = findViewById(R.id.previous_button) // Initialize previous button
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
        randomizeButton.setOnClickListener { // This is the "Next" button
            if (isAutoPlayOn) {
                // If auto play is on, stop current timer and start a new one
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
                        displayNextPositionWithHistory() // Changed from displayRandomPosition
                        positionImageView.startAnimation(fadeIn)
                        startAutoPlay()
                    }
                })
            } else {
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_fast)
                positionImageView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayNextPositionWithHistory() // Changed from displayRandomPosition
                        positionImageView.startAnimation(fadeIn)
                    }
                })
            }
        }

        previousButton.setOnClickListener {
            if (isAutoPlayOn) {
                autoPlayTimer?.cancel()
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_fast)
                positionImageView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayPreviousPositionWithHistory()
                        positionImageView.startAnimation(fadeIn)
                        startAutoPlay() // Restart timer if it was on
                    }
                })
            } else {
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_fast)
                positionImageView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayPreviousPositionWithHistory()
                        positionImageView.startAnimation(fadeIn)
                    }
                })
            }
        }
        // Initialize with consistent Play button text
        // autoPlayButton.text = getString(R.string.play) // Text is removed, icon is set in XML
        autoPlayButton.setOnClickListener {
            toggleAutoPlay()
        }

        // Load all positions (assets and custom) into allPositionItems first.
        // This is crucial for displayPositionByName to find custom positions passed via intent.
        // Load hidden names FIRST, so loadAllPositionsForLibrary can use it.
        loadHiddenDefaultPositionNames() // Load hidden names - This might also move to ViewModel or be handled differently with Firestore

        // loadAllPositionsForLibrary() // ViewModel will now handle loading positions
        // loadPositionImages() // ViewModel will handle loading or providing asset positions

        // Load position favorites for assets (if any are stored locally)
        loadAssetPositionFavorites() // This should be called once in onCreate to load initial asset favorites.
        // Asset favorites are loaded by loadAssetPositionFavorites() called from onCreate if still needed for assets.
        // Firestore item's favorite status is part of the PositionItem.

        // Prepare and load asset positions into ViewModel
        val assetItems = prepareAssetPositionItems()
        positionsViewModel.loadAssetPositions(assetItems)
        
        // Check for specific position to display from intent AFTER allPositionItems is populated (or ViewModel provides data)
        // This will be handled later, after RecyclerView setup, if the intent is for library navigation.
        // For now, store it if present. The original displayPositionByName might be for the randomize tab.
        // The new requirement is to navigate to the library card.
        val intentPositionName = intent.getStringExtra("DISPLAY_POSITION_NAME")
        if (intentPositionName != null) {
            pendingPositionNavigationName = intentPositionName
            // If this intent means we should NOT display a random position initially on the randomize tab,
            // then the displayRandomPosition() call below might need to be conditional.
            // For now, we'll let displayInitialRandomPosition run, and then navigate if pending.
        }

        // The initial display logic might need adjustment now that ViewModel loads data asynchronously.
        // displayInitialRandomPosition() might be called too early or based on incomplete data.
        // Consider triggering initial display from the ViewModel observer when data is ready.
        // For now, let's see how it behaves.
        if (pendingPositionNavigationName == null) {
             displayInitialRandomPosition() // This now handles history initialization
        } else {
            // Navigation pending, will be handled by navigateToPositionInRandomizeView
            // which also handles history.
            // Ensure history is clear if we are about to navigate to a specific item as the start.
            positionHistory.clear()
            positionHistoryPosition = -1
            // updateNavigationButtonStates() will be called by navigateToPositionInRandomizeView or displayInitialRandomPosition
        }
        // updateNavigationButtonStates() // Moved to be called by the functions that change position
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
        // This will be handled by the ViewModel observer
        // if (::positionLibraryAdapter.isInitialized) {
        //     positionLibraryAdapter.updatePositions(ArrayList(allPositionItems))
        // }
        setupSearch()
        setupPositionFilterChips() // Setup for the new filter chips
        setupFab()
        setupResetButton() // Call setup for reset button

        // Initial filter load for the library
        filterPositionsLibrary(positionSearchView.query?.toString())

        // Observe ViewModel data - MOVED HERE after all UI is initialized
        observeViewModel()

        // Handle pending navigation from intent after all UI is set up
        pendingPositionNavigationName?.let { name ->
            // No need to check for RecyclerView readiness here as displayPositionByName handles its own UI.
            // Ensure positionsTabs is ready for tab selection.
             if (::positionsTabs.isInitialized) {
                navigateToPositionInRandomizeView(name)
            }
            pendingPositionNavigationName = null // Clear after attempting
        }

        // Setup custom back press handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            positionsViewModel.allPositions.collect { positions ->
                allPositionItems.clear()
                allPositionItems.addAll(positions)
                if (::positionLibraryAdapter.isInitialized) {
                    positionLibraryAdapter.updatePositions(ArrayList(positions))
                }
                // If asset positions are loaded separately by ViewModel, update positionImages for Randomize tab
                // This part needs careful handling based on how asset positions are managed by ViewModel
                val assetBasedPositions = positions.filter { it.isAsset }
                positionImages = assetBasedPositions.map { it.imageName }


                // After positions are loaded, handle any pending navigation or initial display
                if (pendingPositionNavigationName == null && positionsTabs.selectedTabPosition == 0) {
                     displayInitialRandomPosition() // Ensure this uses the new positionImages
                } else if (pendingPositionNavigationName != null) {
                    navigateToPositionInRandomizeView(pendingPositionNavigationName!!)
                    pendingPositionNavigationName = null
                }
                filterPositionsLibrary(positionSearchView.query?.toString()) // Re-filter
                updateNavigationButtonStates() // Update nav buttons based on new data
            }
        }

        lifecycleScope.launch {
            positionsViewModel.isLoading.collect { isLoading ->
                // Show/hide loading indicator (e.g., a ProgressBar)
                // findViewById<ProgressBar>(R.id.progressBar).visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            positionsViewModel.errorMessage.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@PositionsActivity, it, Toast.LENGTH_LONG).show()
                    // Clear the error message in ViewModel after showing it if needed
                }
            }
        }

        // If you still load asset images separately (e.g., from local assets folder)
        // you might trigger this from here after ViewModel is initialized.
        // This is a placeholder for how asset positions might be loaded if not part of the main Firestore flow.
        // Option 1: ViewModel loads them internally.
        // Option 2: Activity tells ViewModel to load them or provides them.
        // For now, assuming ViewModel's `loadPositions` also fetches/provides asset data
        // or `positionImages` gets populated correctly from `allPositions`.
        // Example:
        // val localAssetPositionItems = loadPositionImagesFromAssets() // A new function to get List<PositionItem>
        // positionsViewModel.loadAssetPositions(localAssetPositionItems)
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
        // When navigating by name, we should find its index in positionImages and update history
        val targetIndex = allPositionItems.indexOfFirst { it.name.equals(positionName, ignoreCase = true) }
        val assetImageName = if (targetIndex != -1) allPositionItems[targetIndex].imageName else null

        if (assetImageName != null) {
            val imageIndexInAssets = positionImages.indexOfFirst { it.equals(assetImageName, ignoreCase = true) }
            if (imageIndexInAssets != -1) {
                // Clear history or decide how to integrate this navigation
                positionHistory.clear()
                currentPosition = imageIndexInAssets
                positionHistory.add(currentPosition)
                positionHistoryPosition = 0
                displayCurrentPosition()
                updateNavigationButtonStates()
            // This was the original fallback, but it could loop if displayPositionByName itself calls displayInitialRandomPosition on failure.
            // Instead, handle the "not found in positionImages" case directly.
             Toast.makeText(this, "Asset image '${assetImageName}' (used by '${positionName}') not found in preloaded image list.", Toast.LENGTH_LONG).show()
             positionImageView.setImageResource(R.drawable.ic_image_24) // Show placeholder
             currentPosition = -1 // Mark as invalid for history tracking by index
             invalidateOptionsMenu() // Update favorite icon if necessary
             updateNavigationButtonStates() // Update nav buttons
            }
        } else {
            // This case means positionName was not found in allPositionItems
             Toast.makeText(this, "Position '${positionName}' not found.", Toast.LENGTH_LONG).show()
            displayInitialRandomPosition() // Display a new random position as a fallback
        }
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
        prefs.edit().remove("hidden_names").apply() // Use apply() for SharedPreferences

        // Deleting user's custom positions from Firestore should be handled by the ViewModel/Repository
        // if a "full reset" means deleting cloud data.
        // For now, this "reset" will primarily clear local hides and refresh from the source.
        // If you need to delete all user's positions from Firestore, a specific ViewModel function would be required.
        // Example: positionsViewModel.deleteAllUserPositions()

        // Reload positions from ViewModel. This will fetch current user positions from Firestore
        // and any default/asset positions managed by the ViewModel.
        positionsViewModel.loadPositions()

        // UI will update via observers.
        // The filterPositionsLibrary() called by the observer will respect the now-empty hiddenDefaultPositionNames.
        // displayInitialRandomPosition() might also be called by the observer if the list changes.

        Toast.makeText(this, "Positions list refreshed. Hidden items restored.", Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu() // Update favorite icon if current position changed due to reset
    }


    override fun onPositionAdded(name: String, imageUri: Uri?) {
        // The imageUri is a content URI from the image picker.
        // For Firestore, we'd typically upload this image to Firebase Storage and store the download URL.
        // Or, if storing locally and syncing paths, ensure the path is meaningful.
        // For now, we'll create a PositionItem and let the ViewModel/Repository handle it.
        // The actual image file handling (saving locally, uploading to cloud) needs to be
        // decided and implemented, likely involving the Repository.

        val imageNameOrPath = imageUri?.toString() ?: generateCustomImageNameForDialog(name)

        val newPosition = PositionItem(
            id = "", // Firestore will generate if new
            name = name,
            imageName = imageNameOrPath, // This might be a content URI, a local file path, or later a cloud URL
            isAsset = false
            // userId will be set by ViewModel/Repository before saving to Firestore
        )

        positionsViewModel.addOrUpdatePosition(newPosition)
        // The UI will update via the observer on `allPositions` or `userPositions`.

        Toast.makeText(this, "Adding position \"$name\"...", Toast.LENGTH_SHORT).show()
        // Optionally, switch to the library tab. Scrolling to the item will be tricky
        // until the list is updated by the observer and the item has an ID from Firestore.
        positionsTabs.getTabAt(1)?.select()
    }

    // Helper to generate a placeholder name if URI is null, or for other temporary uses.
    private fun generateCustomImageNameForDialog(positionName: String): String {
        return "custom_${positionName.replace("\\s+".toRegex(), "_")}_${System.currentTimeMillis()}"
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
    
    private fun loadAssetPositionFavorites() {
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        assetPositionFavorites = prefs.getStringSet("favorite_position_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveAssetPositionFavorites() {
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_position_names", assetPositionFavorites).apply()
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
        val displayedPositionName = positionNameTextView.text.toString()
        if (displayedPositionName.isBlank() || displayedPositionName == getString(R.string.no_position_images_found)) {
            Toast.makeText(this, "No position selected to favorite.", Toast.LENGTH_SHORT).show()
            return
        }

        val positionToToggle = allPositionItems.find { it.name.equals(displayedPositionName, ignoreCase = true) }

        if (positionToToggle != null) {
            if (positionToToggle.isAsset) {
                // Handle asset favorites locally with SharedPreferences
                if (assetPositionFavorites.contains(positionToToggle.name)) {
                    assetPositionFavorites.remove(positionToToggle.name)
                    Toast.makeText(this, "\"${positionToToggle.name}\" removed from asset favorites.", Toast.LENGTH_SHORT).show()
                } else {
                    assetPositionFavorites.add(positionToToggle.name)
                    Toast.makeText(this, "\"${positionToToggle.name}\" added to asset favorites.", Toast.LENGTH_SHORT).show()
                }
                saveAssetPositionFavorites()
                invalidateOptionsMenu() // Update icon immediately
            } else {
                // Handle non-asset (Firestore) positions via ViewModel
                if (positionToToggle.id.isNotBlank()) {
                    positionsViewModel.toggleFavoriteStatus(positionToToggle)
                    // Toast message for immediate feedback. ViewModel will trigger data reload.
                    val actionText = if (!positionToToggle.isFavorite) "added to" else "removed from" // Check current state before toggle
                    Toast.makeText(this, "\"${positionToToggle.name}\" $actionText Firestore favorites.", Toast.LENGTH_SHORT).show()
                    // The observer of positionsViewModel.allPositions will call invalidateOptionsMenu()
                    // once the data is updated and re-collected.
                    // For a slightly faster visual update of the icon (though data might not be saved yet):
                    val index = allPositionItems.indexOfFirst { it.id == positionToToggle.id } // Ensure finding by ID for safety
                    if (index != -1) {
                        // Create a new updated item to ensure StateFlow detects a change if the object reference was the same.
                        allPositionItems[index] = positionToToggle.copy(isFavorite = !positionToToggle.isFavorite) // Reflect the toggle for immediate UI
                        invalidateOptionsMenu()
                    }
                } else {
                    Toast.makeText(this, "Cannot favorite unsaved custom position. Save it first.", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Toast.makeText(this, "Could not find position: $displayedPositionName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFavoriteIcon(menu: Menu? = null) {
        val favoriteItem = menu?.findItem(R.id.action_favorite_position) ?: toolbar.menu.findItem(R.id.action_favorite_position) // Ensure correct menu item ID
        if (favoriteItem != null) {
            if (positionsTabs.selectedTabPosition == 0) { // Only on Randomize tab
                favoriteItem.isVisible = true
                var isCurrentFavorite = false
                val displayedPositionName = positionNameTextView.text.toString()

                if (displayedPositionName.isNotEmpty() && displayedPositionName != getString(R.string.no_position_images_found)) {
                    val currentDisplayedItem = allPositionItems.find { it.name.equals(displayedPositionName, ignoreCase = true) }
                    if (currentDisplayedItem != null) {
                        isCurrentFavorite = if (currentDisplayedItem.isAsset) {
                            assetPositionFavorites.contains(currentDisplayedItem.name)
                        } else {
                            // isFavorite status comes from the PositionItem itself, which is sourced from Firestore via ViewModel
                            currentDisplayedItem.isFavorite
                        }
                    }
                }

                if (isCurrentFavorite) {
                    favoriteItem.setIcon(R.drawable.ic_favorite_filled) // Use ic_favorite_filled
                } else {
                    favoriteItem.setIcon(R.drawable.ic_favorite) // Use ic_favorite (border)
                }
            } else { // Hide on Library tab
                favoriteItem.isVisible = false
            }
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
            autoPlayButton.text = getString(R.string.pause)
            autoPlayButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_pause_24)
            timerTextView.visibility = View.VISIBLE
            autoPlaySettings.visibility = View.GONE // Hide settings when auto play starts
            startAutoPlay()
        } else {
            autoPlayButton.text = getString(R.string.play)
            autoPlayButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_play_24)
            timerTextView.visibility = View.GONE
            autoPlaySettings.visibility = View.VISIBLE // Show settings when auto play stops
            stopAutoPlay()
        }
        updateButtonLayout() // Ensure layout updates correctly
    }

    private fun updateButtonLayout() {
        val buttonsContainer = findViewById<LinearLayout>(R.id.buttons_container)
        val autoPlayButtonParams = autoPlayButton.layoutParams as LinearLayout.LayoutParams
        val timerTextViewParams = timerTextView.layoutParams as LinearLayout.LayoutParams
        
        // The navButtonsContainer is the LinearLayout that holds the previous and next buttons.
        // It's the third child in the buttons_container LinearLayout in the XML.
        val navButtonsContainer = buttonsContainer.getChildAt(2) as LinearLayout
        val navButtonsContainerParams = navButtonsContainer.layoutParams as LinearLayout.LayoutParams

        if (isAutoPlayOn) {
            // Distribute weight in thirds: AutoPlay | Timer | NavButtons
            autoPlayButtonParams.weight = 1f
            timerTextViewParams.weight = 1f
            navButtonsContainerParams.weight = 1f
            timerTextView.visibility = View.VISIBLE
        } else {
            // Distribute weight in thirds: AutoPlay | Timer (gone) | NavButtons
            // Timer is GONE, so AutoPlay and NavButtons effectively take 50/50 of the space
            // by still assigning them 1f weight each and timer 0f.
            autoPlayButtonParams.weight = 1f
            timerTextViewParams.weight = 0f // Timer is hidden and takes no space
            navButtonsContainerParams.weight = 1f
            timerTextView.visibility = View.GONE
        }
        
        autoPlayButton.layoutParams = autoPlayButtonParams
        timerTextView.layoutParams = timerTextViewParams
        navButtonsContainer.layoutParams = navButtonsContainerParams
        
        buttonsContainer.requestLayout() // Crucial to apply layout changes
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
                        displayInitialRandomPosition()
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
    
    // Removed deprecated override fun onBackPressed()

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
            displayInitialRandomPosition()
        }
    }

    private fun displayNextPositionWithHistory() {
        if (positionImages.isEmpty()) {
            updateNavigationButtonStates()
            return
        }

        if (positionHistoryPosition < positionHistory.size - 1) {
            // We have a "forward" history
            positionHistoryPosition++
            currentPosition = positionHistory[positionHistoryPosition]
        } else {
            // No "forward" history, get a new random position
            var newRandomIndex: Int
            if (positionImages.size == 1) {
                newRandomIndex = 0 // Only one image
            } else {
                do {
                    newRandomIndex = Random.nextInt(positionImages.size)
                } while (newRandomIndex == currentPosition) // Ensure it's different from current
            }
            currentPosition = newRandomIndex

            // Add to history
            // If we were at the end of history, just add.
            // If we went back and then "next" to a new random, truncate future history.
            if (positionHistoryPosition < positionHistory.size -1) {
                positionHistory = positionHistory.subList(0, positionHistoryPosition + 1)
            }
            positionHistory.add(currentPosition)
            positionHistoryPosition = positionHistory.size - 1
        }
        displayCurrentPosition()
        updateNavigationButtonStates()
    }

    private fun displayPreviousPositionWithHistory() {
        if (positionHistoryPosition > 0) {
            positionHistoryPosition--
            currentPosition = positionHistory[positionHistoryPosition]
            displayCurrentPosition()
        } else {
            Toast.makeText(this, "No previous position", Toast.LENGTH_SHORT).show()
        }
        updateNavigationButtonStates()
    }
    
    // Renamed from displayRandomPosition to reflect it's now for initial/fallback
    private fun displayInitialRandomPosition() {
        if (positionImages.isEmpty()) {
            positionNameTextView.text = "No Positions Available"
            positionImageView.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_image_24))
            currentPosition = -1
            positionHistory.clear()
            positionHistoryPosition = -1
            updateNavigationButtonStates()
            return
        }

        currentPosition = Random.nextInt(positionImages.size)
        positionHistory.clear()
        positionHistory.add(currentPosition)
        positionHistoryPosition = 0
        displayCurrentPosition()
        updateNavigationButtonStates()
    }

    // This function is now primarily for displaying the image/name based on currentPosition
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
            val positionName = nameWithoutExtension.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
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
    
    private fun updateNavigationButtonStates() {
        previousButton.isEnabled = positionHistoryPosition > 0
        randomizeButton.isEnabled = positionImages.isNotEmpty() // Next is always enabled if there are images
    }

private fun setupLibraryRecyclerView() {
    // Ensure positionsLibraryRecyclerView is initialized before use
    if (!::positionsLibraryRecyclerView.isInitialized) {
         positionsLibraryRecyclerView = findViewById(R.id.positions_library_recycler_view)
    }

    positionLibraryAdapter = PositionLibraryAdapter(this, mutableListOf(), // Initial empty list
        onItemClick = { positionItem ->
            // Handle item click: Display the position in the "Randomize" tab
            navigateToPositionInRandomizeView(positionItem.name) // Updated to use the correct navigation
            positionsTabs.getTabAt(0)?.select() // Switch to Randomize tab
            invalidateOptionsMenu() // Ensure favorite icon updates
        },
        onDeleteClick = { positionItem -> // This is for the explicit delete button on the card
            handlePositionDeletionOrHiding(positionItem, -1) // -1 as adapterPosition is not from swipe
        }
    )
    positionsLibraryRecyclerView.adapter = positionLibraryAdapter
    positionsLibraryRecyclerView.layoutManager = GridLayoutManager(this, 2) // 2 columns

    // Setup ItemTouchHelper for swipe-to-delete/hide
    val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            return false // We don't want to handle move
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val positionSwiped = positionLibraryAdapter.getPositionAt(viewHolder.adapterPosition)
            handlePositionDeletionOrHiding(positionSwiped, viewHolder.adapterPosition)
        }
    }
    ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(positionsLibraryRecyclerView)
}

private fun handlePositionDeletionOrHiding(positionItem: PositionItem, adapterPosition: Int) {
    val positionName = positionItem.name

    if (!positionItem.isAsset) { // It's a user's custom position (from Firestore)
        MaterialAlertDialogBuilder(this@PositionsActivity)
            .setTitle("Delete Position")
            .setMessage("Are you sure you want to delete your custom position \"$positionName\"?")
            .setNegativeButton("CANCEL") { dialog, _ ->
                if (adapterPosition != -1) positionLibraryAdapter.notifyItemChanged(adapterPosition) // Rebind if from swipe
                dialog.dismiss()
            }
            .setPositiveButton("DELETE") { _, _ ->
                if (positionItem.id.isNotBlank()) {
                    positionsViewModel.deletePosition(positionItem.id)
                    Toast.makeText(this@PositionsActivity, "\"$positionName\" deleted.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@PositionsActivity, "Error: Position ID missing.", Toast.LENGTH_SHORT).show()
                    if (adapterPosition != -1) positionLibraryAdapter.notifyItemChanged(adapterPosition)
                }
            }
            .setOnCancelListener { // Handles back press or tap outside
                if (adapterPosition != -1) positionLibraryAdapter.notifyItemChanged(adapterPosition)
            }
            .show()
    } else { // It's a default/asset position
         MaterialAlertDialogBuilder(this@PositionsActivity)
            .setTitle("Hide Position")
            .setMessage("Are you sure you want to hide the default position \"$positionName\"? This can be undone by resetting positions.")
            .setNegativeButton("CANCEL") { dialog, _ ->
                if (adapterPosition != -1) positionLibraryAdapter.notifyItemChanged(adapterPosition)
                dialog.dismiss()
            }
            .setPositiveButton("HIDE") { _, _ ->
                hiddenDefaultPositionNames.add(positionName)
                saveHiddenDefaultPositionNames()
                filterPositionsLibrary(positionSearchView.query?.toString())
                Toast.makeText(this@PositionsActivity, "\"$positionName\" hidden.", Toast.LENGTH_SHORT).show()
            }
             .setOnCancelListener {
                 if (adapterPosition != -1) positionLibraryAdapter.notifyItemChanged(adapterPosition)
             }
            .show()
    }
}


    private fun loadAllPositionsForLibrary(isReset: Boolean = false) {
        // This function is largely obsolete as ViewModel now loads data.
        // It should not directly modify `allPositionItems`.
        // Calls to this function have been or should be removed/refactored.
        android.util.Log.w("PositionsActivity", "loadAllPositionsForLibrary() called. This function is deprecated. Data should come from ViewModel.")
    }

    // Helper function to capitalize first letter of each word - This can be moved to a utils file or kept if still used by other local logic
    fun String.capitalizeWords(): String = split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    // Example helper to load custom positions from SharedPreferences - OBSOLETE with Firestore
    private fun loadCustomPositions(): List<PositionItem> {
        android.util.Log.w("PositionsActivity", "loadCustomPositions() called. This is obsolete with Firestore.")
        return emptyList()
    }

    // Example helper to save custom positions (called when adding/deleting custom ones) - OBSOLETE with Firestore
    private fun saveCustomPositions() {
        android.util.Log.w("PositionsActivity", "saveCustomPositions() called. This is obsolete with Firestore.")
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
                            !voice.isNetworkConnectionRequired()) { // Use !isNetworkConnectionRequired as a proxy for embedded/high quality
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
                            // femaleVoiceFound = true // This assignment was unused as per warning, loop breaks anyway
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
                    val positionName = nameWithoutExtension.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
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
    
    private fun prepareAssetPositionItems(): List<PositionItem> {
        val assetPositionItems = mutableListOf<PositionItem>()
        try {
            val imageFiles = assets.list("positions")?.filter {
                it.endsWith(".jpg", ignoreCase = true) ||
                it.endsWith(".jpeg", ignoreCase = true) ||
                it.endsWith(".png", ignoreCase = true) // Added png support
            } ?: emptyList()
    
            for (imageName in imageFiles) {
                val nameWithoutExtension = imageName.substringBeforeLast(".")
                val displayName = nameWithoutExtension.replace("_", " ").capitalizeWords()
                // Use a consistent prefix for asset IDs to avoid clashes with Firestore IDs
                val assetId = "asset_$imageName"
                val isFavorite = assetPositionFavorites.contains(imageName) // Check against loaded asset favorites
    
                assetPositionItems.add(
                    PositionItem(
                        id = assetId,
                        name = displayName,
                        imageName = imageName, // Adapter uses this to load from assets/positions/
                        isAsset = true,
                        userId = "", // Not applicable for assets
                        isFavorite = isFavorite
                    )
                )
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error loading asset position items: ${e.message}", Toast.LENGTH_LONG).show()
        }
        return assetPositionItems
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