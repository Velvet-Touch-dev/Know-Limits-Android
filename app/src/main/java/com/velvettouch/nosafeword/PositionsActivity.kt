package com.velvettouch.nosafeword

import com.google.firebase.auth.FirebaseAuth
import android.content.Context
import android.content.Intent
import android.util.Log
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide // Add Glide import
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
import com.google.android.material.progressindicator.LinearProgressIndicator
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
    private var allPositionItems: MutableList<PositionItem> = mutableListOf() // Source of truth from ViewModel
    private var randomizablePositions: List<PositionItem> = emptyList() // Filtered list for Randomize tab
    private lateinit var positionSearchView: SearchView
    private lateinit var resetButton: ExtendedFloatingActionButton // Changed for Reset to Default ExtendedFAB styling
    private lateinit var libraryFabContainer: LinearLayout
    private lateinit var positionFilterChipGroup: ChipGroup
    private lateinit var chipAllPositions: Chip
    private lateinit var chipCustomPositions: Chip
    private lateinit var positionsProgressIndicator: LinearProgressIndicator
    private lateinit var swipeTipTextView: TextView // Added for the swipe tip

    private var isImageLoadingViaGlide: Boolean = false // Added to track Glide loading state
    private lateinit var positionsViewModel: PositionsViewModel
    private lateinit var favoritesViewModel: FavoritesViewModel // Added
    private lateinit var localFavoritesRepository: LocalFavoritesRepository // Added

    // TTS variables
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false
    private var isTtsEnabled = true // Default value, will be updated from preferences
    
    // Voice settings
    private var voicePitch = 1.0f
    private var voiceSpeed = 0.9f
    
    // private var positionImages: List<String> = emptyList() // Deprecated for Randomize tab logic, use allPositionItems
    private var currentPosition: Int = -1 // This will be an index for allPositionItems
    // private var previousPosition: Int = -1 // Deprecated
    private var positionHistory: MutableList<Int> = mutableListOf()
    private var positionHistoryPosition: Int = -1
    private var isAutoPlayOn = false
    private var autoPlayTimer: CountDownTimer? = null
    private var minTimeSeconds = 30 // Default minimum time in seconds
    private var maxTimeSeconds = 60 // Default maximum time in seconds
    private val timeOptions = listOf(10, 15, 20, 30, 45, 60, 90, 120) // Time options in seconds
    
    // Favorites - SharedPreferences for asset favorites. // This is now handled by LocalFavoritesRepository and FavoritesViewModel
    // Non-asset favorites are stored in Firestore via PositionItem.isFavorite.
    // private var assetPositionFavorites: MutableSet<String> = mutableSetOf() // Removed - Used for local asset favorites
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
        // const val POSITION_FAVORITES_PREF = "position_favorites" // Removed
        const val HIDDEN_DEFAULT_POSITIONS_PREF = "hidden_default_positions" // New pref key
        
        // Default values
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_SPEED = 0.7f
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_positions)
        
        // Initialize ViewModel
        val factory = PositionsViewModelFactory(application)
        positionsViewModel = ViewModelProvider(this, factory)[PositionsViewModel::class.java]
        favoritesViewModel = ViewModelProvider(this, FavoritesViewModelFactory(application))[FavoritesViewModel::class.java] // Added
        localFavoritesRepository = LocalFavoritesRepository(applicationContext) // Added

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
        positionsProgressIndicator = findViewById(R.id.positions_progress_indicator)
        
        // Show settings by default initially
        autoPlaySettings.visibility = View.VISIBLE
        
        // Set up toolbar with Material 3 style
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.positions) // Set title to "Positions"
        
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
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
        // loadAssetPositionFavorites() // Removed - will be handled by FavoritesViewModel and LocalFavoritesRepository
        // Asset favorites are loaded by loadAssetPositionFavorites() called from onCreate if still needed for assets.
        // Firestore item's favorite status is part of the PositionItem.

        // Prepare and load asset positions into ViewModel
        val assetItems = prepareAssetPositionItems() // prepareAssetPositionItems will now use localFavoritesRepository
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
        // positionSearchView = findViewById(R.id.position_search_view) // Removed: Will be initialized from menu
        resetButton = findViewById(R.id.button_reset_to_default) // Initialize Reset Button
        libraryFabContainer = findViewById(R.id.library_fab_container) // Initialize FAB container
        positionFilterChipGroup = findViewById(R.id.position_filter_chip_group)
        chipAllPositions = findViewById(R.id.chip_all_positions)
        chipCustomPositions = findViewById(R.id.chip_custom_positions)
        swipeTipTextView = findViewById(R.id.swipe_tip_text_view) // Initialize swipe tip TextView
        // RecyclerView initialization is now inside setupLibraryRecyclerView,
        // but ensure the view ID is correct in your XML (positions_library_recycler_view)

        // Setup Tab Layout
        positionsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // Randomize
                        randomizeTabContent.visibility = View.VISIBLE
                        libraryTabContent.visibility = View.GONE
                        swipeTipTextView.visibility = View.GONE
                        invalidateOptionsMenu() 
                    }
                    1 -> { // Library
                        randomizeTabContent.visibility = View.GONE
                        libraryTabContent.visibility = View.VISIBLE
                        swipeTipTextView.visibility = View.VISIBLE 
                        invalidateOptionsMenu()
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
            swipeTipTextView.visibility = View.GONE // Ensure tip is hidden initially if Randomize is default
        } else {
            randomizeTabContent.visibility = View.GONE
            libraryTabContent.visibility = View.VISIBLE
            swipeTipTextView.visibility = View.VISIBLE // Ensure tip is visible if Library is default (or navigated to)
        }
 
        setupLibraryRecyclerView() // Call to setup RecyclerView, adapter will use allPositionItems
        // loadAllPositionsForLibrary() was called earlier and populated allPositionItems.
        // Now that the adapter is initialized, update it with the loaded items.
        // This will be handled by the ViewModel observer
        // if (::positionLibraryAdapter.isInitialized) {
        //     positionLibraryAdapter.updatePositions(ArrayList(allPositionItems))
        // }
        // setupSearch() // Will be set up in onCreateOptionsMenu
        // setupPositionFilterChips() will be called after observeViewModel to ensure allPositionItems is populated
        setupFab()
        setupResetButton() // Call setup for reset button

        // Initial filter load for the library - This will be handled by observeViewModel or when search view is set up
        // filterPositionsLibrary(positionSearchView.query?.toString()) // Removed to prevent crash

        // Observe ViewModel data - MOVED HERE after all UI is initialized
        observeViewModel()
        setupPositionFilterChips() // Setup for the new filter chips, now called after observeViewModel

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
                
                updateRandomizablePositions() // This updates the list and may trigger a display update.

                if (::positionLibraryAdapter.isInitialized) {
                    positionLibraryAdapter.updatePositions(ArrayList(allPositionItems)) // Library always shows all
                }
                updateFilterChipCounts() // Update chip counts when positions change
                
                // Handle pending navigation or ensure randomize tab is correctly displayed
                if (positionsTabs.selectedTabPosition == 0) {
                    if (pendingPositionNavigationName != null) {
                        navigateToPositionInRandomizeView(pendingPositionNavigationName!!)
                        pendingPositionNavigationName = null
                    } else if (randomizablePositions.isEmpty()) {
                        displayEmptyRandomizeState()
                    } else if (currentPosition == -1 || currentPosition >= randomizablePositions.size) {
                        // If current selection is invalid after list update
                        displayInitialRandomPosition()
                    }
                    // If currentPosition is valid, updateRandomizablePositions should have handled display if item changed.
                }
                // Ensure positionSearchView is initialized before accessing its query
                if (::positionSearchView.isInitialized) {
                    filterPositionsLibrary(positionSearchView.query?.toString()) // For the library tab
                } else {
                    filterPositionsLibrary(null) // Or pass null if search view isn't ready
                }
                updateNavigationButtonStates() // This will call updateFavoriteIcon
                invalidateOptionsMenu() // Explicitly call to update favorite icon
            }
        }

        lifecycleScope.launch {
            positionsViewModel.isLoading.collect { _ -> // Renamed to _
                updateProgressIndicatorVisibility()
            }
        }
        lifecycleScope.launch {
            positionsViewModel.isSyncing.collect { _ -> // Renamed to _
                updateProgressIndicatorVisibility()
            }
        }
        lifecycleScope.launch {
            positionsViewModel.errorMessage.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@PositionsActivity, it, Toast.LENGTH_LONG).show()
                    positionsViewModel.clearErrorMessage() // Clear the error message
                }
            }
        }

        // Observe FavoritesViewModel
        lifecycleScope.launch {
            favoritesViewModel.favorites.collect { favoriteItems ->
                // This list contains all Favorite objects (scenes and positions)
                // We can use this to update the favorite icon status
                Log.d("PositionsActivity", "FavoritesViewModel.favorites collected, count: ${favoriteItems.size}")
                invalidateOptionsMenu() // Update favorite icon when favorites change
                // If library items show favorite status, refresh adapter here
                if (::positionLibraryAdapter.isInitialized) {
                     // Potentially update items in adapter if they display favorite state directly
                     // For now, toolbar icon is the main concern.
                }
            }
        }
        lifecycleScope.launch {
            favoritesViewModel.isLoading.collect { isLoading ->
                // Handle loading state for favorites if needed, e.g. a different progress indicator
                // For now, main progress indicator is tied to positionsViewModel
            }
        }
        lifecycleScope.launch {
            favoritesViewModel.error.collect { error ->
                error?.let {
                    Toast.makeText(this@PositionsActivity, "Favorites Error: $it", Toast.LENGTH_LONG).show()
                    favoritesViewModel.clearError()
                }
            }
        }
         // Initial load of cloud favorites if user is logged in
        if (FirebaseAuth.getInstance().currentUser != null) {
            favoritesViewModel.refreshCloudFavorites(performMergeIfNeeded = true)
        }
    }

    private fun displayEmptyRandomizeState() {
        positionNameTextView.text = getString(R.string.no_positions_to_randomize)
        positionImageView.setImageResource(R.drawable.ic_image_24) // Placeholder
        speakPositionName(getString(R.string.no_positions_to_randomize))
        currentPosition = -1
        positionHistory.clear()
        positionHistoryPosition = -1
        updateNavigationButtonStates()
    }

    private fun updateRandomizablePositions() {
        val previouslyDisplayedRandomItem = if (currentPosition != -1 && currentPosition < randomizablePositions.size) {
            randomizablePositions[currentPosition]
        } else {
            null
        }

        Log.d("PositionsActivity", "Before filtering randomizablePositions: hiddenDefaultPositionNames = $hiddenDefaultPositionNames")
        randomizablePositions = allPositionItems.filterNot { item ->
            val isHidden = item.isAsset && item.name in hiddenDefaultPositionNames
            if (item.isAsset) { // Log details only for assets to reduce noise
                Log.d("PositionsActivity", "Filtering item: Name='${item.name}', isAsset=${item.isAsset}, Name in hiddenDefaultPositionNames='${item.name in hiddenDefaultPositionNames}', Should be hidden=$isHidden")
            }
            isHidden
        }
        Log.d("PositionsActivity", "Updated randomizablePositions. New count: ${randomizablePositions.size}. Filtered Names: ${randomizablePositions.joinToString { it.name }}. All hidden names: $hiddenDefaultPositionNames")

        if (randomizablePositions.isEmpty()) {
            currentPosition = -1
            positionHistory.clear()
            positionHistoryPosition = -1
            if (positionsTabs.selectedTabPosition == 0) {
                displayEmptyRandomizeState()
            }
        } else {
            // If there was a previously displayed item, try to find it in the new list
            if (previouslyDisplayedRandomItem != null) {
                val newIndexOfPrevious = randomizablePositions.indexOfFirst { it.id == previouslyDisplayedRandomItem.id }
                if (newIndexOfPrevious != -1) {
                    // Item still exists, update currentPosition
                    currentPosition = newIndexOfPrevious
                    // Naive history update: if the item at the current history pointer is no longer our currentPosition,
                    // or if history pointer is out of bounds, reset history focused on the current item.
                    // A more sophisticated history re-mapping could be done but is complex.
                    if (positionHistoryPosition < 0 || positionHistoryPosition >= positionHistory.size || positionHistory[positionHistoryPosition] != currentPosition) {
                        positionHistory.clear()
                        positionHistory.add(currentPosition)
                        positionHistoryPosition = 0
                    } else {
                        // Trim any "future" history if we jumped back and then the list changed
                        if (positionHistory.size > positionHistoryPosition + 1) {
                           positionHistory = positionHistory.subList(0, positionHistoryPosition + 1)
                        }
                    }
                } else {
                    // Previously displayed item is no longer in the randomizable list (it was hidden)
                    // Reset to a new initial position if on randomize tab
                    if (positionsTabs.selectedTabPosition == 0) {
                        displayInitialRandomPosition() // This will pick a new one and reset history
                    } else {
                        currentPosition = -1 // Mark as invalid if not on randomize tab
                        positionHistory.clear()
                        positionHistoryPosition = -1
                    }
                }
            } else if (currentPosition == -1 && positionsTabs.selectedTabPosition == 0) {
                 // No item was selected, and we are on randomize tab, display initial if list is not empty
                displayInitialRandomPosition()
            }
        }
        // updateNavigationButtonStates() // Usually called by display functions or observer
    }

    private fun updateProgressIndicatorVisibility() {
        val isLoadingFromViewModel = positionsViewModel.isLoading.value
        val isSyncingFromViewModel = positionsViewModel.isSyncing.value
        // Ensure positionsProgressIndicator is initialized before accessing it.
        if (::positionsProgressIndicator.isInitialized) {
            positionsProgressIndicator.visibility = if (isLoadingFromViewModel || isSyncingFromViewModel || isImageLoadingViaGlide) View.VISIBLE else View.GONE
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent

        val positionNameToDisplay = intent?.getStringExtra("DISPLAY_POSITION_NAME")
        if (positionNameToDisplay != null) {
            if (::positionsTabs.isInitialized) {
                navigateToPositionInRandomizeView(positionNameToDisplay)
            } else {
                pendingPositionNavigationName = positionNameToDisplay
            }
        }
    }


    private fun navigateToPositionInRandomizeView(positionName: String) {
        if (!::positionsTabs.isInitialized) {
            pendingPositionNavigationName = positionName
            return
        }
        positionsTabs.getTabAt(0)?.select()

        val targetPositionItem = allPositionItems.find { it.name.equals(positionName, ignoreCase = true) }

        if (targetPositionItem != null) {
            positionNameTextView.text = targetPositionItem.name
            currentPosition = allPositionItems.indexOf(targetPositionItem) // Set currentPosition to index in allPositionItems
            positionHistory.clear()
            if (currentPosition != -1) { // Ensure item was found
                positionHistory.add(currentPosition)
                positionHistoryPosition = 0
            } else {
                 positionHistoryPosition = -1 // Should not happen if targetPositionItem is not null
            }

            try {
                if (targetPositionItem.isAsset) {
                    isImageLoadingViaGlide = false // Asset, not using Glide
                    val inputStream = assets.open("positions/${targetPositionItem.imageName}")
                    val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                    positionImageView.setImageDrawable(drawable)
                    inputStream.close()
                    speakPositionName(targetPositionItem.name)
                    invalidateOptionsMenu()
                    updateProgressIndicatorVisibility() // Update progress bar state
                } else { // Custom position (local or Firestore)
                    if (targetPositionItem.imageName.isNotBlank()) {
                        val imagePath = targetPositionItem.imageName
                        isImageLoadingViaGlide = true // Will use Glide
                        updateProgressIndicatorVisibility() // Show progress bar

                        if (imagePath.startsWith("content://") || imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                            val imageUriToLoad = Uri.parse(imagePath)
                            Log.d("PositionsActivity", "navigateToPositionInRandomizeView: Attempting to load URI: $imageUriToLoad, Scheme: ${imageUriToLoad.scheme}")
                            Glide.with(this@PositionsActivity)
                                .load(imageUriToLoad)
                                .placeholder(R.drawable.ic_image_24)
                                .error(R.drawable.ic_image_24)
                                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                    override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                                        Log.e("PositionsActivity", "navigateToPositionInRandomizeView (URI): Glide load failed for $imageUriToLoad", e)
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                        Log.d("PositionsActivity", "navigateToPositionInRandomizeView (URI): Glide load success for $imageUriToLoad")
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                })
                                .into(positionImageView)
                        } else if (imagePath.startsWith("/")) { // Absolute file path
                            val localFile = java.io.File(imagePath)
                            if (localFile.exists()) {
                                Log.d("PositionsActivity", "navigateToPositionInRandomizeView: Attempting to load File object: ${localFile.absolutePath}")
                                Glide.with(this@PositionsActivity)
                                    .load(localFile) // Use File object directly
                                    .placeholder(R.drawable.ic_image_24)
                                    .error(R.drawable.ic_image_24)
                                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                        override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                                            Log.e("PositionsActivity", "navigateToPositionInRandomizeView (File): Glide load failed for ${localFile.absolutePath}", e)
                                            isImageLoadingViaGlide = false
                                            updateProgressIndicatorVisibility()
                                            return false
                                        }
                                        override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                            Log.d("PositionsActivity", "navigateToPositionInRandomizeView (File): Glide load success for ${localFile.absolutePath}")
                                            isImageLoadingViaGlide = false
                                            updateProgressIndicatorVisibility()
                                            return false
                                        }
                                    })
                                    .into(positionImageView)
                            } else {
                                Log.e("PositionsActivity", "navigateToPositionInRandomizeView: Local file does not exist: $imagePath")
                                positionImageView.setImageResource(R.drawable.ic_image_24)
                                isImageLoadingViaGlide = false
                                updateProgressIndicatorVisibility()
                            }
                        } else {
                            Log.w("PositionsActivity", "navigateToPositionInRandomizeView: Unknown image path format: $imagePath")
                            positionImageView.setImageResource(R.drawable.ic_image_24)
                            isImageLoadingViaGlide = false
                            updateProgressIndicatorVisibility()
                        }
                    } else {
                        positionImageView.setImageResource(R.drawable.ic_image_24)
                        isImageLoadingViaGlide = false // No image path, not using Glide
                        updateProgressIndicatorVisibility()
                    }
                    speakPositionName(targetPositionItem.name)
                    invalidateOptionsMenu()
                }
            } catch (e: Exception) { // Catch IOException, SecurityException, etc.
                android.util.Log.e("PositionsActivity", "Error loading image for ${targetPositionItem.name}: ${e.message}")
                positionImageView.setImageResource(R.drawable.ic_image_24)
                isImageLoadingViaGlide = false // Error, not using Glide
                updateProgressIndicatorVisibility()
                invalidateOptionsMenu()
                if (e is SecurityException) {
                    Toast.makeText(this, "Permission denied to load image.", Toast.LENGTH_SHORT).show()
                }
            }
            updateNavigationButtonStates()
        } else {
            Toast.makeText(this, "Position '${positionName}' not found.", Toast.LENGTH_LONG).show()
            displayInitialRandomPosition()
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
            .setMessage("Are you sure you want to restore all default positions? Your custom positions will not be affected.")
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

        // Immediately update the randomizable positions list based on the cleared hidden names
        updateRandomizablePositions()
        // Immediately update the library view as well
        filterPositionsLibrary(positionSearchView.query?.toString())

        // Reload all positions from ViewModel. This will fetch current user positions from Firestore
        // and any default/asset positions managed by the ViewModel.
        // This also triggers observers which will call updateRandomizablePositions again,
        // and filterPositionsLibrary.
        positionsViewModel.loadPositions()

        // If the randomize tab is currently active, refresh its display.
        if (positionsTabs.selectedTabPosition == 0) {
            displayInitialRandomPosition() // This will pick a new random position or show empty state.
        }
        // The library tab has been updated by the direct call to filterPositionsLibrary above.
        // It will also be updated again by the observer on positionsViewModel.allPositions.

        // Toast.makeText(this, "Positions list refreshed. Hidden items restored.", Toast.LENGTH_SHORT).show() // Removed as per request
        invalidateOptionsMenu() // Update favorite icon if current position changed due to reset
        updateFilterChipCounts() // Add this call to update chip counts immediately
    }


    override fun onPositionAdded(name: String, imageUri: Uri?) {
        // The imageUri is a content URI from the image picker.
        // For Firestore, we'd typically upload this image to Firebase Storage and store the download URL.
        // For now, we'll store the URI string.
        // val imageNameOrPath = imageUri?.toString() ?: "" // Store URI as string, or empty if no image // Removed as unused

        // Ensure user is logged in before attempting to get UID
        val currentFirebaseUser = FirebaseAuth.getInstance().currentUser
        val userId = currentFirebaseUser?.uid // Will be null if not logged in

        // Determine what to store in PositionItem.imageName based on login state
        val imageNameForPositionItem = if (userId == null) {
            // Offline: Store the local image URI string directly.
            // The ViewModel/Repository will use this for local display.
            imageUri?.toString() ?: ""
        } else {
            // Online: Clear imageName; ViewModel/Repository will upload image from imageUri
            // and populate imageName with the Firebase Storage download URL.
            ""
        }

        val newPosition = PositionItem(
            id = "", // Firestore will generate ID if online; local store might generate one
            name = name,
            imageName = imageNameForPositionItem,
            isAsset = false, // Custom positions are not assets
            userId = userId, // Associate with the current user, or null if not logged in
            isFavorite = false // Default to not favorite
        )

        // ViewModel handles saving to Firestore (and uploading image if URI present & online)
        // or saving to local storage (if offline).
        positionsViewModel.addOrUpdatePosition(newPosition, imageUri) // Pass original imageUri for processing

        if (userId == null) {
            Toast.makeText(this, "Position saved locally. Log in to sync.", Toast.LENGTH_LONG).show()
        } else {
            // Keep existing toast for online mode
            Toast.makeText(this, "Adding position: $name", Toast.LENGTH_SHORT).show()
        }
        // The ViewModel/Repository will now handle image processing and storage.
    }

    // Helper to generate a placeholder name if URI is null, or for other temporary uses.
    private fun generateCustomImageNameForDialog(positionName: String): String {
        return "custom_${positionName.replace("\\s+".toRegex(), "_")}_${System.currentTimeMillis()}"
    }

    private fun setupFab() {
        val fab: FloatingActionButton = findViewById(R.id.fab_add_position)
        fab.setOnClickListener {
            Log.d("PositionsActivity", "FAB Clicked - Showing AddPositionDialog. Current User: ${FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"}")
            val dialog = AddPositionDialogFragment()
            dialog.listener = this
            dialog.show(supportFragmentManager, "AddPositionDialogFragment")
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu
        menuInflater.inflate(R.menu.position_menu, menu)

        val searchItem = menu.findItem(R.id.action_search_positions)
        positionSearchView = searchItem?.actionView as SearchView // Re-assign to the toolbar's SearchView

        val favoriteItem = menu.findItem(R.id.action_favorite_position)
        val addToPlanItem = menu.findItem(R.id.action_add_to_plan_position)

        if (positionsTabs.selectedTabPosition == 0) { // Randomize tab
            searchItem?.isVisible = false
            updateFavoriteIcon(menu) 
            favoriteItem?.isVisible = true
            addToPlanItem?.isVisible = true
            // Collapse search view if it was open from another tab
            if (searchItem?.isActionViewExpanded == true) {
                searchItem.collapseActionView()
            }
        } else { // Library tab
            searchItem?.isVisible = true
            favoriteItem?.isVisible = false
            addToPlanItem?.isVisible = false
            setupSearch() // Setup listener for toolbar search view
        }
        
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        
        return when (item.itemId) {
            R.id.action_favorite_position -> {
                if (positionsTabs.selectedTabPosition == 0 && item.isVisible) {
                    toggleFavorite()
                }
                true
            }
            R.id.action_add_to_plan_position -> {
                if (positionsTabs.selectedTabPosition == 0 && item.isVisible) {
                    addCurrentPositionToPlan()
                }
                true
            }
            // R.id.action_search_positions will be handled by SearchView listeners
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /*private fun loadAssetPositionFavorites() { // Removed
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        assetPositionFavorites = prefs.getStringSet("favorite_position_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveAssetPositionFavorites() { // Removed
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_position_names", assetPositionFavorites).apply()
    }*/

    private fun loadHiddenDefaultPositionNames() {
        val prefs = getSharedPreferences(HIDDEN_DEFAULT_POSITIONS_PREF, Context.MODE_PRIVATE)
        hiddenDefaultPositionNames = prefs.getStringSet("hidden_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveHiddenDefaultPositionNames() {
        val prefs = getSharedPreferences(HIDDEN_DEFAULT_POSITIONS_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("hidden_names", hiddenDefaultPositionNames).commit()
        updateRandomizablePositions() // This will re-filter and update UI if needed.
        updateFilterChipCounts() // Add this call to update chip counts immediately
    }
    
    private fun toggleFavorite() {
        val displayedPositionName = positionNameTextView.text.toString()
        if (displayedPositionName.isBlank() || displayedPositionName == getString(R.string.no_position_images_found) || currentPosition < 0 || currentPosition >= randomizablePositions.size) {
            Toast.makeText(this, "No position selected to favorite.", Toast.LENGTH_SHORT).show()
            return
        }

        val positionToToggle = randomizablePositions[currentPosition] // Get from the currently displayed list
        val itemId = if (positionToToggle.isAsset) "asset_${positionToToggle.imageName}" else positionToToggle.id

        if (itemId.isBlank()) {
            Toast.makeText(this, "Position has no valid ID to favorite.", Toast.LENGTH_SHORT).show()
            return
        }

        val isCurrentlyFavorite: Boolean
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser == null) { // Offline
            isCurrentlyFavorite = localFavoritesRepository.isLocalFavoritePosition(itemId)
            if (isCurrentlyFavorite) {
                localFavoritesRepository.removeLocalFavoritePosition(itemId)
                Toast.makeText(this, "💔 \"${positionToToggle.name}\" removed from favorites.", Toast.LENGTH_SHORT).show()
            } else {
                localFavoritesRepository.addLocalFavoritePosition(itemId)
                Toast.makeText(this, "❤️ \"${positionToToggle.name}\" added to favorites.", Toast.LENGTH_SHORT).show()
            }
        } else { // Online
            isCurrentlyFavorite = favoritesViewModel.favorites.value.any { it.itemId == itemId && it.itemType == "position" }
            if (isCurrentlyFavorite) {
                favoritesViewModel.removeCloudFavorite(itemId, "position")
                Toast.makeText(this, "💔 \"${positionToToggle.name}\" removing from favorites...", Toast.LENGTH_SHORT).show()
            } else {
                favoritesViewModel.addCloudFavorite(itemId, "position")
                Toast.makeText(this, "❤️ \"${positionToToggle.name}\" adding to favorites...", Toast.LENGTH_SHORT).show()
            }
        }
        invalidateOptionsMenu() // Update icon immediately
    }

    private fun updateFavoriteIcon(menu: Menu? = null) {
        val favoriteItem = menu?.findItem(R.id.action_favorite_position) ?: toolbar.menu.findItem(R.id.action_favorite_position)
        if (favoriteItem == null) return

        if (positionsTabs.selectedTabPosition == 0) { // Only on Randomize tab
            favoriteItem.isVisible = true
            var isCurrentFavorite = false

            if (currentPosition >= 0 && currentPosition < randomizablePositions.size) {
                val currentDisplayedItem = randomizablePositions[currentPosition]
                val itemId = if (currentDisplayedItem.isAsset) "asset_${currentDisplayedItem.imageName}" else currentDisplayedItem.id

                if (itemId.isNotBlank()) {
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    isCurrentFavorite = if (currentUser == null) { // Offline
                        localFavoritesRepository.isLocalFavoritePosition(itemId)
                    } else { // Online
                        favoritesViewModel.favorites.value.any { it.itemId == itemId && it.itemType == "position" }
                    }
                }
            }

            if (isCurrentFavorite) {
                favoriteItem.setIcon(R.drawable.ic_favorite_filled)
            } else {
                favoriteItem.setIcon(R.drawable.ic_favorite)
            }
        } else { // Hide on Library tab
            favoriteItem.isVisible = false
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
    
    /* // Method is now obsolete as ViewModel and prepareAssetPositionItems handle asset loading
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
    */
    
    private fun displayPositionByName(targetPositionName: String) {
        val positionItem = allPositionItems.find { it.name.equals(targetPositionName, ignoreCase = true) }

        if (positionItem != null) {
            positionNameTextView.text = positionItem.name
            currentPosition = allPositionItems.indexOf(positionItem) // Index in allPositionItems
            // Reset history to this position
            positionHistory.clear()
            if (currentPosition != -1) {
                positionHistory.add(currentPosition)
                positionHistoryPosition = 0
            } else {
                positionHistoryPosition = -1 // Should not happen if positionItem is not null
            }

            try {
                if (positionItem.isAsset) {
                    isImageLoadingViaGlide = false // Asset, not using Glide
                    // displayCurrentPosition() // This was recursive or incorrect. Call the actual asset loading logic.
                    val inputStream = assets.open("positions/${positionItem.imageName}")
                    val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                    positionImageView.setImageDrawable(drawable)
                    inputStream.close()
                    speakPositionName(positionItem.name)
                    invalidateOptionsMenu()
                    updateProgressIndicatorVisibility()
                } else {
                    // Custom position (not an asset)
                    val imagePath = positionItem.imageName
                    if (imagePath.isNotBlank()) {
                        isImageLoadingViaGlide = true // Will use Glide
                        updateProgressIndicatorVisibility()

                        if (imagePath.startsWith("content://") || imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                            val imageUriToLoad = Uri.parse(imagePath)
                            Log.d("PositionsActivity", "displayPositionByName: Attempting to load URI: $imageUriToLoad, Scheme: ${imageUriToLoad.scheme}")
                            Glide.with(this).load(imageUriToLoad)
                                .placeholder(R.drawable.ic_image_24).error(R.drawable.ic_image_24)
                                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                    override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                                        Log.e("PositionsActivity", "displayPositionByName (URI): Glide load failed for $imageUriToLoad", e)
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                        Log.d("PositionsActivity", "displayPositionByName (URI): Glide load success for $imageUriToLoad")
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                })
                                .into(positionImageView)
                        } else if (imagePath.startsWith("/")) { // Absolute file path
                            val localFile = java.io.File(imagePath)
                            if (localFile.exists()) {
                                Log.d("PositionsActivity", "displayPositionByName: Attempting to load File object: ${localFile.absolutePath}")
                                Glide.with(this).load(localFile) // Use File object directly
                                    .placeholder(R.drawable.ic_image_24).error(R.drawable.ic_image_24)
                                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                        override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                                            Log.e("PositionsActivity", "displayPositionByName (File): Glide load failed for ${localFile.absolutePath}", e)
                                            isImageLoadingViaGlide = false
                                            updateProgressIndicatorVisibility()
                                            return false
                                        }
                                        override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                            Log.d("PositionsActivity", "displayPositionByName (File): Glide load success for ${localFile.absolutePath}")
                                            isImageLoadingViaGlide = false
                                            updateProgressIndicatorVisibility()
                                            return false
                                        }
                                    })
                                    .into(positionImageView)
                            } else {
                                Log.e("PositionsActivity", "displayPositionByName: Local file does not exist: $imagePath")
                                positionImageView.setImageResource(R.drawable.ic_image_24)
                                isImageLoadingViaGlide = false
                                updateProgressIndicatorVisibility()
                            }
                        } else {
                            Log.w("PositionsActivity", "displayPositionByName: Unknown image path format: $imagePath")
                            positionImageView.setImageResource(R.drawable.ic_image_24)
                            isImageLoadingViaGlide = false
                            updateProgressIndicatorVisibility()
                        }
                    } else {
                        positionImageView.setImageResource(R.drawable.ic_image_24) // Placeholder
                        isImageLoadingViaGlide = false // No image path, not using Glide
                        updateProgressIndicatorVisibility()
                    }
                    speakPositionName(positionItem.name)
                    invalidateOptionsMenu() // Update favorite icon status
                }
            } catch (e: IOException) {
                e.printStackTrace()
                positionImageView.setImageResource(R.drawable.ic_image_24) // Placeholder
                isImageLoadingViaGlide = false // Error, not using Glide
                updateProgressIndicatorVisibility()
                Toast.makeText(this, "Error loading image for ${positionItem.name}", Toast.LENGTH_SHORT).show()
                invalidateOptionsMenu()
            }
            updateNavigationButtonStates()
        } else {
            Toast.makeText(this, "Position '$targetPositionName' not found.", Toast.LENGTH_SHORT).show()
            displayInitialRandomPosition()
        }
    }

    private fun displayNextPositionWithHistory() {
        if (allPositionItems.isEmpty()) { // Use allPositionItems
            updateNavigationButtonStates()
            return
        }

        if (positionHistoryPosition < positionHistory.size - 1) {
            // We have a "forward" history
            positionHistoryPosition++
            currentPosition = positionHistory[positionHistoryPosition]
        } else {
            // No "forward" history, get a new random position from allPositionItems
            var newRandomIndex: Int
            if (allPositionItems.size == 1) { // Use allPositionItems
                newRandomIndex = 0 // Only one image
            } else {
                do {
                    newRandomIndex = Random.nextInt(allPositionItems.size) // Use allPositionItems
                } while (newRandomIndex == currentPosition && allPositionItems.size > 1) // Ensure it's different from current if possible
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
        if (allPositionItems.isEmpty()) {
            positionNameTextView.text = "No positions available (Add to strings.xml)" // Use string resource placeholder
            positionImageView.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_image_24))
            currentPosition = -1
            positionHistory.clear()
            positionHistoryPosition = -1
        } else {
            currentPosition = Random.nextInt(allPositionItems.size) // Use allPositionItems
            positionHistory.clear()
            positionHistory.add(currentPosition)
            positionHistoryPosition = 0
            displayCurrentPosition()
        }
        updateNavigationButtonStates()
    }

    private fun displayCurrentPosition() {
        if (randomizablePositions.isEmpty()) {
            Log.w("PositionsActivity", "displayCurrentPosition: randomizablePositions is empty. Displaying empty state.")
            displayEmptyRandomizeState() // Handles UI for empty list
            return
        }

        if (currentPosition < 0 || currentPosition >= randomizablePositions.size) {
            Log.w("PositionsActivity", "displayCurrentPosition: Invalid index $currentPosition for randomizablePositions size ${randomizablePositions.size}. Resetting to initial.")
            // Attempt to reset to a valid initial position if current is bad
            displayInitialRandomPosition() // This should pick a valid one or handle empty state again.
            return // displayInitialRandomPosition will call displayCurrentPosition again if successful.
        }

        val positionItem = randomizablePositions[currentPosition]
        positionNameTextView.text = positionItem.name
        Log.d("PositionsActivity", "Randomize tab: Displaying (Index $currentPosition): '${positionItem.name}', Image: '${positionItem.imageName}', IsAsset: ${positionItem.isAsset} from randomizablePositions.")

        positionImageView.setImageResource(R.drawable.ic_image_24) // Default placeholder

        try {
            if (positionItem.imageName.isNotBlank()) {
                if (positionItem.isAsset) {
                    isImageLoadingViaGlide = false // Asset, not using Glide
                    val inputStream = assets.open("positions/${positionItem.imageName}")
                    val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                    positionImageView.setImageDrawable(drawable)
                    inputStream.close()
                    updateProgressIndicatorVisibility()
                } else { // Not an asset, could be URL, content URI, or file path
                    val imagePath = positionItem.imageName
                    isImageLoadingViaGlide = true // Will use Glide
                    updateProgressIndicatorVisibility()
                    when {
                        imagePath.startsWith("http://") || imagePath.startsWith("https://") -> {
                            Log.d("PositionsActivity", "displayCurrentPosition: Attempting to load URL: $imagePath")
                            Glide.with(this).load(imagePath)
                                .placeholder(R.drawable.ic_image_24).error(R.drawable.ic_image_24)
                                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                    override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                                        Log.e("PositionsActivity", "displayCurrentPosition (URL): Glide load failed for $imagePath", e)
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                        Log.d("PositionsActivity", "displayCurrentPosition (URL): Glide load success for $imagePath")
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                })
                                .into(positionImageView)
                        }
                        imagePath.startsWith("content://") -> {
                            val contentUri = Uri.parse(imagePath)
                            Log.d("PositionsActivity", "displayCurrentPosition: Attempting to load content URI: $contentUri, Scheme: ${contentUri.scheme}")
                            Glide.with(this).load(contentUri)
                                .placeholder(R.drawable.ic_image_24).error(R.drawable.ic_image_24)
                                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                    override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                                        Log.e("PositionsActivity", "displayCurrentPosition (Content URI): Glide load failed for $contentUri", e)
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                        Log.d("PositionsActivity", "displayCurrentPosition (Content URI): Glide load success for $contentUri")
                                        isImageLoadingViaGlide = false
                                        updateProgressIndicatorVisibility()
                                        return false
                                    }
                                })
                                .into(positionImageView)
                        }
                        imagePath.startsWith("/") -> { // Absolute file path
                            val localFile = java.io.File(imagePath)
                            if (localFile.exists()){
                                Log.d("PositionsActivity", "displayCurrentPosition: Attempting to load File object: ${localFile.absolutePath}")
                                Glide.with(this).load(localFile) // Use File object directly
                                    .placeholder(R.drawable.ic_image_24).error(R.drawable.ic_image_24)
                                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                        override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean): Boolean {
                                            Log.e("PositionsActivity", "displayCurrentPosition (File): Glide load failed for ${localFile.absolutePath}", e)
                                            isImageLoadingViaGlide = false
                                            updateProgressIndicatorVisibility()
                                            return false
                                        }
                                        override fun onResourceReady(resource: android.graphics.drawable.Drawable, model: Any, target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                                            Log.d("PositionsActivity", "displayCurrentPosition (File): Glide load success for ${localFile.absolutePath}")
                                            isImageLoadingViaGlide = false
                                            updateProgressIndicatorVisibility()
                                            return false
                                        }
                                    })
                                    .into(positionImageView)
                            } else {
                                Log.e("PositionsActivity", "displayCurrentPosition: Local file does not exist: $imagePath")
                                positionImageView.setImageResource(R.drawable.ic_image_24)
                                isImageLoadingViaGlide = false
                                updateProgressIndicatorVisibility()
                            }
                        }
                        else -> {
                            Log.w("PositionsActivity", "Randomize tab: Unknown non-asset imageName format: $imagePath")
                            positionImageView.setImageResource(R.drawable.ic_image_24)
                            isImageLoadingViaGlide = false
                            updateProgressIndicatorVisibility()
                        }
                    }
                }
            } else {
                Log.w("PositionsActivity", "Randomize tab: ImageName is blank for ${positionItem.name}")
                positionImageView.setImageResource(R.drawable.ic_image_24)
                isImageLoadingViaGlide = false // No image path, not using Glide
                updateProgressIndicatorVisibility()
            }
        } catch (e: Exception) {
            Log.e("PositionsActivity", "Error loading image for ${positionItem.name} (${positionItem.imageName}) on Randomize tab", e)
            positionImageView.setImageResource(R.drawable.ic_image_24)
            isImageLoadingViaGlide = false // Error, not using Glide
            updateProgressIndicatorVisibility()
        }

        speakPositionName(positionItem.name)
        invalidateOptionsMenu() // Update favorite icon status
        updateNavigationButtonStates() // Update nav buttons based on new history/list state
    }
    
    private fun updateNavigationButtonStates() {
        previousButton.isEnabled = positionHistoryPosition > 0
        randomizeButton.isEnabled = allPositionItems.isNotEmpty() // Use allPositionItems
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
        }
        // onDeleteClick lambda removed as the button is gone and swipe handles deletion directly
    )
    positionsLibraryRecyclerView.adapter = positionLibraryAdapter
    positionsLibraryRecyclerView.layoutManager = GridLayoutManager(this, 2) // 2 columns

    // Setup ItemTouchHelper for swipe-to-delete/hide
    val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) { // Changed to RIGHT only
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            return false // We don't want to handle move
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            if (direction == ItemTouchHelper.RIGHT) { // Ensure action is only for right swipe
                val positionSwiped = positionLibraryAdapter.getPositionAt(viewHolder.adapterPosition)
                handlePositionDeletionOrHiding(positionSwiped, viewHolder.adapterPosition)
            }
        }

        override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            val itemView = viewHolder.itemView
            val background = ColorDrawable(Color.parseColor("#F44336")) // Red background
            val deleteIcon = ContextCompat.getDrawable(this@PositionsActivity, R.drawable.ic_delete)?.apply { setTint(Color.WHITE) }
            
            val cornerRadiusInPx = 16f * resources.displayMetrics.density // 16dp corner radius

            val iconIntrinsicHeight = deleteIcon?.intrinsicHeight ?: 0
            val iconIntrinsicWidth = deleteIcon?.intrinsicWidth ?: 0
            
            val iconVerticalMargin = ((itemView.height - iconIntrinsicHeight) / 2).coerceAtLeast(0)
            val iconTop = itemView.top + iconVerticalMargin
            val iconBottom = iconTop + iconIntrinsicHeight

            val fixedIconHorizontalPaddingInPx = (16f * resources.displayMetrics.density).toInt()
            val clipPath = android.graphics.Path()

            if (dX > 0) { // Swiping to the right (revealing background on the left)
                val revealedWidth = dX.coerceAtMost(itemView.width.toFloat())
                val bgLeft = itemView.left.toFloat()
                val bgTop = itemView.top.toFloat()
                val bgRight = itemView.left + revealedWidth
                val bgBottom = itemView.bottom.toFloat()

                val radii = floatArrayOf(
                    cornerRadiusInPx, cornerRadiusInPx, // Top-left
                    0f, 0f,                             // Top-right (flat unless fully revealed)
                    0f, 0f,                             // Bottom-right (flat unless fully revealed)
                    cornerRadiusInPx, cornerRadiusInPx  // Bottom-left
                )
                if (revealedWidth == itemView.width.toFloat()) {
                    radii[2] = cornerRadiusInPx; radii[3] = cornerRadiusInPx // Top-right
                    radii[4] = cornerRadiusInPx; radii[5] = cornerRadiusInPx // Bottom-right
                }

                clipPath.reset()
                clipPath.addRoundRect(bgLeft, bgTop, bgRight, bgBottom, radii, android.graphics.Path.Direction.CW)
                
                c.save()
                c.clipPath(clipPath)
                background.setBounds(bgLeft.toInt(), bgTop.toInt(), bgRight.toInt(), bgBottom.toInt())
                background.draw(c)

                if (deleteIcon != null && revealedWidth >= iconIntrinsicWidth + fixedIconHorizontalPaddingInPx) {
                    val iconActualLeft = itemView.left + fixedIconHorizontalPaddingInPx
                    val iconActualRight = iconActualLeft + iconIntrinsicWidth
                    if (iconActualRight <= bgRight) {
                         deleteIcon.setBounds(iconActualLeft, iconTop, iconActualRight, iconBottom)
                         deleteIcon.draw(c)
                    }
                }
                c.restore()
            // No need for dX < 0 block for drawing delete UI, as swipe is right-only for delete action
            } else {
                // If dX is not > 0 (e.g., 0 or negative from a non-delete swipe),
                // ensure super.onChildDraw is called to handle default view drawing/reset.
                // No custom background/icon drawing needed here for left swipe.
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }
    ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(positionsLibraryRecyclerView)
}

private fun handlePositionDeletionOrHiding(positionItem: PositionItem, adapterPosition: Int) {
    val positionName = positionItem.name

    if (!positionItem.isAsset) { // It's a user's custom position
        MaterialAlertDialogBuilder(this@PositionsActivity)
            .setTitle("Delete Position")
            .setMessage("Are you sure you want to delete your custom position \"$positionName\"? This will remove it from local storage and, if synced, from the cloud.")
            .setNegativeButton("CANCEL") { dialog, _ ->
                if (adapterPosition != -1) positionLibraryAdapter.notifyItemChanged(adapterPosition) // Rebind if from swipe
                dialog.dismiss()
            }
            .setPositiveButton("DELETE") { _, _ ->
                if (positionItem.id.isNotBlank()) {
                    // Call ViewModel with both ID and Name
                    positionsViewModel.deletePosition(positionItem.id, positionItem.name)
                    Toast.makeText(this@PositionsActivity, "\"$positionName\" deleted.", Toast.LENGTH_SHORT).show()
                } else {
                    // This case should ideally not happen if items always have IDs.
                    // If it's a local item that somehow lost its ID before sync, deleting by name might be an option.
                    // However, the current ViewModel deletePosition requires an ID.
                    Log.e("PositionsActivity", "Attempted to delete custom position with blank ID: $positionName")
                    Toast.makeText(this@PositionsActivity, "Error: Position ID missing. Cannot delete.", Toast.LENGTH_SHORT).show()
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
                saveHiddenDefaultPositionNames() // This now calls updateRandomizablePositions() internally
                filterPositionsLibrary(positionSearchView.query?.toString()) // Updates the library tab
                Toast.makeText(this@PositionsActivity, "\"$positionName\" hidden.", Toast.LENGTH_SHORT).show()
            }
             .setOnCancelListener {
                 if (adapterPosition != -1) positionLibraryAdapter.notifyItemChanged(adapterPosition)
             }
            .show()
    }
}


    private fun loadAllPositionsForLibrary(_isReset: Boolean = false) { // Renamed to _isReset
        // This function is largely obsolete as ViewModel now loads data.
        // It should not directly modify `allPositionItems`.
        // Calls to this function have been or should be removed/refactored.
        android.util.Log.w("PositionsActivity", "loadAllPositionsForLibrary() called. This function is deprecated. Data should come from ViewModel.")
    }

    // Helper function to capitalize first letter of each word - This can be moved to a utils file or kept if still used by other local logic
    fun String.capitalizeWords(): String = split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
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

    private fun setupSearch() { // This will now setup the toolbar's SearchView
        if (!::positionSearchView.isInitialized) {
            Log.e("PositionsActivity", "setupSearch: positionSearchView not initialized. Cannot set up search.")
            return
        }
        positionSearchView.queryHint = getString(R.string.search_positions)
        positionSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterPositionsLibrary(query)
                positionSearchView.clearFocus() // Hide keyboard
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterPositionsLibrary(newText)
                return true
            }
        })
        // Optional: Handle search view expansion/collapse if needed
        val searchMenuItem = toolbar.menu.findItem(R.id.action_search_positions)
        searchMenuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                // Potentially hide other elements or adjust layout
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                // Restore elements or layout, clear filter if desired
                filterPositionsLibrary(null) // Clear filter on collapse
                return true
            }
        })
    }

    private fun setupPositionFilterChips() {
        updateFilterChipCounts() // Initial count update

        chipAllPositions.setOnCheckedChangeListener { _, _ ->
            if (!chipAllPositions.isChecked && !chipCustomPositions.isChecked) {
                chipAllPositions.isChecked = true // Keep at least one selected
                // Toast.makeText(this, "At least one filter must be selected.", Toast.LENGTH_SHORT).show() // Optional: can be annoying
            }
            filterPositionsLibrary(positionSearchView.query?.toString())
            updateFilterChipCounts() // Update counts on check change as well, though primary update is from data change
        }

        chipCustomPositions.setOnCheckedChangeListener { _, _ ->
            if (!chipAllPositions.isChecked && !chipCustomPositions.isChecked) {
                chipCustomPositions.isChecked = true // Keep at least one selected
                // Toast.makeText(this, "At least one filter must be selected.", Toast.LENGTH_SHORT).show() // Optional
            }
            filterPositionsLibrary(positionSearchView.query?.toString())
            updateFilterChipCounts() // Update counts on check change
        }
    }

    private fun updateFilterChipCounts() {
        if (!::chipAllPositions.isInitialized || !::chipCustomPositions.isInitialized) {
            Log.d("PositionsActivity", "updateFilterChipCounts: Chips not initialized yet.")
            return
        }

        val assetCount = allPositionItems.count { it.isAsset && !hiddenDefaultPositionNames.contains(it.name) }
        val customCount = allPositionItems.count { !it.isAsset } // Custom positions are not affected by hiddenDefaultPositionNames

        chipAllPositions.text = "${getString(R.string.chip_filter_defaults)} ($assetCount)"
        chipCustomPositions.text = "${getString(R.string.chip_filter_custom)} ($customCount)"
        Log.d("PositionsActivity", "Filter chip counts updated: Defaults ($assetCount), Custom ($customCount)")
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

            val hiddenMatch = if (position.isAsset) {
                !hiddenDefaultPositionNames.contains(position.name) // Show if NOT in hidden set
            } else {
                true // Non-assets are not affected by this specific hiding mechanism
            }

            typeMatchLogic && queryMatch && hiddenMatch
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
                if (currentPosition >= 0 && currentPosition < allPositionItems.size) { // Use allPositionItems
                    val positionToSpeak = allPositionItems[currentPosition]
                    speakPositionName(positionToSpeak.name) // Use PositionItem.name directly
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
                // isFavorite for PositionItem is now less critical, true source is Favorite object.
                // For initial display, we can check local repo.
                val isFavoriteInitially = localFavoritesRepository.isLocalFavoritePosition(assetId)
    
                assetPositionItems.add(
                    PositionItem(
                        id = assetId, // This ID will be used as itemId for Favorite object
                        name = displayName,
                        imageName = imageName, // Adapter uses this to load from assets/positions/
                        isAsset = true,
                        userId = "", // Not applicable for assets
                        isFavorite = isFavoriteInitially // Set based on local state for initial UI
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
