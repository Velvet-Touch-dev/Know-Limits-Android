package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : BaseActivity() {

    private lateinit var titleTextView: TextView
    private lateinit var contentTextView: TextView
    private lateinit var randomizeButton: FloatingActionButton
    private lateinit var previousButton: FloatingActionButton
    private lateinit var editButton: FloatingActionButton
    private lateinit var sceneCardView: MaterialCardView
    private lateinit var shareButton: MaterialButton
    private lateinit var addSceneButton: FloatingActionButton
    private lateinit var resetScenesButton: ExtendedFloatingActionButton
    private lateinit var topAppBar: Toolbar
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var randomContent: NestedScrollView
    private lateinit var favoritesContainer: FrameLayout // This might be deprecated if FavoritesActivity is primary
    private lateinit var editContainer: FrameLayout
    private lateinit var favoritesRecyclerView: RecyclerView // This might be deprecated
    private lateinit var editRecyclerView: RecyclerView
    private lateinit var emptyFavoritesView: LinearLayout // This might be deprecated
    private lateinit var sceneFilterChipGroup: ChipGroup
    private lateinit var chipDefaultScenes: Chip
    private lateinit var chipCustomScenes: Chip
    private lateinit var baseTextChipDefaultScenes: String
    private lateinit var baseTextChipCustomScenes: String

    private val scenesViewModel: ScenesViewModel by viewModels()
    private val cloudFavoritesViewModel: FavoritesViewModel by viewModels()
    private val localFavoritesViewModel: LocalFavoritesViewModel by viewModels()
    private val positionsViewModel: PositionsViewModel by viewModels() // Added PositionsViewModel
    private var displayedScenes: List<Scene> = listOf()
    private var allUserScenes: List<Scene> = listOf()

    private var currentSceneIndex: Int = -1
    private var sceneHistory: MutableList<Scene> = mutableListOf()
    private var historyPosition: Int = -1

    private var currentMode: Int = MODE_RANDOM
    private var currentToast: Toast? = null
    private var pendingSceneTitleNavigation: String? = null
    private var pendingSceneIdNavigation: String? = null

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private var authStateListener: FirebaseAuth.AuthStateListener? = null


    private val gson = Gson()
    private val plannedItemsPrefsName = "PlanNightPrefs"
    private val plannedItemsKey = "plannedItemsList"
    // favoritesPrefsName and favoriteSceneIdsKey are no longer used here

    private lateinit var favoritesAdapter: FavoriteScenesAdapter // This might be deprecated
    private lateinit var editAdapter: EditScenesAdapter

    companion object {
        private const val MODE_RANDOM = 0
        private const val MODE_FAVORITES = 1 // Consider if this mode is still needed in MainActivity
        private const val MODE_EDIT = 2
        private const val RC_SIGN_IN = 9001
        private const val TAG = "MainActivityAuth"
    }

    private fun getCurrentScene(): Scene? {
        if (currentSceneIndex != -1 && currentSceneIndex < displayedScenes.size) {
            return displayedScenes[currentSceneIndex]
        }
        if (sceneHistory.isNotEmpty() && historyPosition != -1 && historyPosition < sceneHistory.size) {
            val lastKnownSceneFromHistory = sceneHistory[historyPosition]
            val indexOfLastKnownInDisplayed = displayedScenes.indexOf(lastKnownSceneFromHistory)
            if (indexOfLastKnownInDisplayed != -1) {
                currentSceneIndex = indexOfLastKnownInDisplayed
                return displayedScenes[currentSceneIndex]
            }
            return lastKnownSceneFromHistory
        }
        return null
    }

    private fun clearSceneDisplay() {
        titleTextView.text = getString(R.string.no_scenes_available_title)
        contentTextView.text = getString(R.string.no_scenes_available_content)
        currentSceneIndex = -1
        sceneCardView.visibility = View.INVISIBLE
        editButton.visibility = View.GONE
        shareButton.visibility = View.GONE
        updateFavoriteIcon()
        updatePreviousButtonState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = Firebase.auth
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail()
        try {
            getString(R.string.default_web_client_id).let { gsoBuilder.requestIdToken(it) }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Failed to get R.string.default_web_client_id", e)
        }
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        initializeViews()
        setupNavigation()
        setupAdapters()
        observeViewModels()
        setupButtonListeners()
        setupChipListeners()
        setupAuthStateListener() // Setup listener

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (currentMode == MODE_RANDOM && historyPosition > 0 && sceneHistory.isNotEmpty()) {
                    previousButton.performClick()
                } else {
                    if (isEnabled) { isEnabled = false; onBackPressed() } // Corrected super call
                }
            }
        })
        handleInitialIntentNavigation()
        // updateUI() will be called by auth state listener or ViewModel observers
    }

    override fun onStart() {
        super.onStart()
        authStateListener?.let { auth.addAuthStateListener(it) }
        // Initial check of auth state when activity becomes visible
        handleAuthStateChange(auth.currentUser, isInitialCheck = true)
    }

    override fun onStop() {
        super.onStop()
        authStateListener?.let { auth.removeAuthStateListener(it) }
    }


    private fun initializeViews() {
        titleTextView = findViewById(R.id.titleTextView)
        contentTextView = findViewById(R.id.contentTextView)
        randomizeButton = findViewById(R.id.randomizeButton)
        previousButton = findViewById(R.id.previousButton)
        editButton = findViewById(R.id.editButton)
        sceneCardView = findViewById(R.id.sceneCardView)
        shareButton = findViewById(R.id.shareButton)
        topAppBar = findViewById(R.id.topAppBar)
        setSupportActionBar(topAppBar)
        supportActionBar?.title = getString(R.string.random) // Set title to "Scenes"
        bottomNavigation = findViewById(R.id.bottom_navigation)
        randomContent = findViewById(R.id.random_content)
        favoritesContainer = findViewById(R.id.favorites_container)
        editContainer = findViewById(R.id.edit_container)
        favoritesRecyclerView = findViewById(R.id.favorites_recycler_view)
        editRecyclerView = findViewById(R.id.edit_recycler_view)
        emptyFavoritesView = findViewById(R.id.empty_favorites_view)
        addSceneButton = findViewById(R.id.add_scene_fab)
        resetScenesButton = findViewById(R.id.reset_scenes_button)
        sceneFilterChipGroup = findViewById(R.id.scene_filter_chip_group)
        chipDefaultScenes = findViewById(R.id.chip_default_scenes)
        chipCustomScenes = findViewById(R.id.chip_custom_scenes)
        baseTextChipDefaultScenes = chipDefaultScenes.text.toString()
        baseTextChipCustomScenes = chipCustomScenes.text.toString()
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        titleTextView.movementMethod = LinkMovementMethod.getInstance()
        contentTextView.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setupNavigation() {
        drawerToggle = ActionBarDrawerToggle(this, drawerLayout, topAppBar, R.string.drawer_open, R.string.drawer_close)
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
        if (navigationView.headerCount > 0) {
             navigationView.removeHeaderView(navigationView.getHeaderView(0))
        }
        navigationView.setNavigationItemSelectedListener { menuItem ->
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = when (menuItem.itemId) {
                R.id.nav_positions -> Intent(this, PositionsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_body_worship -> Intent(this, BodyWorshipActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_task_list -> Intent(this, TaskListActivity::class.java)
                R.id.nav_plan_night -> Intent(this, PlanNightActivity::class.java)
                R.id.nav_favorites -> Intent(this, FavoritesActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_settings -> Intent(this, SettingsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_scenes -> null // Already in MainActivity
                else -> null
            }
            intent?.let { startActivity(it) }
            true
        }
        bottomNavigation.setOnItemSelectedListener { item ->
            currentMode = when (item.itemId) {
                R.id.navigation_random -> MODE_RANDOM
                R.id.navigation_edit -> MODE_EDIT
                // R.id.navigation_favorites -> MODE_FAVORITES // If you re-enable this
                else -> return@setOnItemSelectedListener false
            }
            updateUI()
            true
        }
    }

    private fun setupAdapters() {
        editAdapter = EditScenesAdapter(
            onEditClick = { scene -> switchToRandomMode(scene) },
            onDeleteClick = { scene -> showDeleteConfirmation(scene) }
        )
        editRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = editAdapter
        }

        // favoritesAdapter might be deprecated if MODE_FAVORITES is removed from MainActivity
        favoritesAdapter = FavoriteScenesAdapter(
            onItemClick = { scene: Scene -> switchToRandomMode(scene) },
            onItemRemoved = { scene: Scene -> removeFromFavorites(scene) } // This will call the updated removeFromFavorites
        )
        favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = favoritesAdapter
            // Swipe to delete for favorites list (if kept in MainActivity)
            val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    if (viewHolder.adapterPosition != RecyclerView.NO_POSITION) {
                        val scene = favoritesAdapter.currentList[viewHolder.adapterPosition]
                        removeFromFavorites(scene)
                    }
                }
                override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                    val itemView = viewHolder.itemView
                    val background = ColorDrawable(Color.parseColor("#F44336")) // Red background
                    val deleteIcon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)?.apply { setTint(Color.WHITE) }
                    val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)
                    if (dX > 0) { // Swiping to the right
                        val iconLeft = itemView.left + iconMargin; val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?:0)
                        deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    } else if (dX < 0) { // Swiping to the left
                        val iconRight = itemView.right - iconMargin; val iconLeft = iconRight - (deleteIcon?.intrinsicWidth ?:0)
                        deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    } else { background.setBounds(0,0,0,0) } // view is unSwiped
                    background.draw(c); deleteIcon?.draw(c)
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
            ItemTouchHelper(swipeHandler).attachToRecyclerView(this)
        }
    }

    private fun observeViewModels() {
        lifecycleScope.launch {
            scenesViewModel.scenes.collectLatest { scenesList ->
                Log.d(TAG, "All scenes updated. Count: ${scenesList.size}")
                allUserScenes = scenesList
                val currentSearchQuery = (topAppBar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.query?.toString()
                filterScenes(currentSearchQuery) // This updates displayedScenes

                processPendingNavigation()

                val sceneAfterPendingNav = getCurrentScene()
                if (currentMode == MODE_RANDOM) {
                    if (sceneAfterPendingNav == null && displayedScenes.isNotEmpty()) {
                        displayRandomScene()
                    } else if (displayedScenes.isEmpty() && allUserScenes.isNotEmpty()) {
                        clearSceneDisplay()
                        titleTextView.text = getString(R.string.no_scenes_match_filter_title)
                        contentTextView.text = getString(R.string.no_scenes_match_filter_content)
                    } else if (displayedScenes.isEmpty() && allUserScenes.isEmpty()) {
                        clearSceneDisplay()
                    }
                }
                // When all scenes change, local favorites might need re-filtering if user is logged out
                if (auth.currentUser == null) {
                    localFavoritesViewModel.refreshLocalFavoriteScenes(allUserScenes)
                }
                updateUI()
            }
        }

        lifecycleScope.launch {
            localFavoritesViewModel.localFavoriteScenes.collectLatest { localFavScenes ->
                if (auth.currentUser == null) {
                    Log.d(TAG, "Local favorites updated. Count: ${localFavScenes.size}")
                    updateFavoriteIcon()
                    if (currentMode == MODE_FAVORITES) {
                        favoritesAdapter.submitList(localFavScenes)
                        emptyFavoritesView.visibility = if (localFavScenes.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            cloudFavoritesViewModel.favorites.collectLatest { cloudFavoriteObjects ->
                if (auth.currentUser != null) {
                    Log.d(TAG, "Cloud favorites updated. Count: ${cloudFavoriteObjects.size}")
                    updateFavoriteIcon()
                    if (currentMode == MODE_FAVORITES) {
                        val favoriteSceneDetails = allUserScenes.filter { scene ->
                            cloudFavoriteObjects.any { fav -> fav.itemId == getSceneIdentifier(scene) && fav.itemType == "scene" }
                        }
                        favoritesAdapter.submitList(favoriteSceneDetails)
                        emptyFavoritesView.visibility = if (favoriteSceneDetails.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            cloudFavoritesViewModel.isLoading.collectLatest { isLoading ->
                 if (auth.currentUser != null) { /* TODO: Handle cloud loading indicator */ }
            }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.error.collectLatest { error ->
                if (auth.currentUser != null && error != null) {
                    Toast.makeText(this@MainActivity, "Cloud Favorites Error: $error", Toast.LENGTH_SHORT).show()
                    cloudFavoritesViewModel.clearError()
                }
            }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.isMerging.collectLatest { isMerging ->
                if (auth.currentUser != null && isMerging) { /* TODO: Handle merging indicator */ }
            }
        }

        lifecycleScope.launch {
            scenesViewModel.isLoading.collectLatest { isLoading ->
                randomizeButton.isEnabled = !isLoading
                previousButton.isEnabled = !isLoading && historyPosition > 0
                editButton.isEnabled = !isLoading && getCurrentScene() != null
                addSceneButton.isEnabled = !isLoading
            }
        }
        lifecycleScope.launch {
            scenesViewModel.error.collectLatest { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@MainActivity, "Error: $it", Toast.LENGTH_LONG).show()
                    scenesViewModel.clearError()
                }
            }
        }
    }

    private fun setupButtonListeners() {
        randomizeButton.setOnClickListener {
            val slideOut = AnimationUtils.loadAnimation(this, R.anim.slide_out_left)
            val slideIn = AnimationUtils.loadAnimation(this, R.anim.slide_in_right)
            sceneCardView.startAnimation(slideOut)
            slideOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) { displayNextScene(); sceneCardView.startAnimation(slideIn) }
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            })
        }
        previousButton.setOnClickListener {
            if (historyPosition > 0 && sceneHistory.isNotEmpty()) {
                 val slideOut = AnimationUtils.loadAnimation(this, R.anim.slide_out_right)
                 val slideIn = AnimationUtils.loadAnimation(this, R.anim.slide_in_left)
                 sceneCardView.startAnimation(slideOut)
                 slideOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        historyPosition--
                        val prevScene = sceneHistory[historyPosition]
                        val indexInDisplayed = displayedScenes.indexOf(prevScene)
                        currentSceneIndex = if (indexInDisplayed != -1) indexInDisplayed else -1 // Ensure currentSceneIndex is valid for displayedScenes
                        displayScene(prevScene)
                        sceneCardView.startAnimation(slideIn)
                        updatePreviousButtonState()
                    }
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                })
            } else { showMaterialToast("No previous scene available", false) }
        }
        editButton.setOnClickListener {
            getCurrentScene()?.let { showEditDialog(it) } ?: showMaterialToast("No scene selected to edit.", false)
        }
        shareButton.setOnClickListener { shareCurrentScene() }
        addSceneButton.setOnClickListener { showEditDialog(null) }
        resetScenesButton.setOnClickListener { showResetConfirmation() }
    }

    private fun setupChipListeners() {
        val chipListener = { _: View, _: Boolean ->
            val query = (topAppBar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.query?.toString()
            filterScenes(query)
            // updateUI() will be called by scenesViewModel observer after filterScenes updates displayedScenes
        }
        chipDefaultScenes.setOnCheckedChangeListener(chipListener)
        chipCustomScenes.setOnCheckedChangeListener(chipListener)
        chipDefaultScenes.isChecked = true
        chipCustomScenes.isChecked = true
    }
    
    private fun handleInitialIntentNavigation() {
        var navigationAttempted = false
        intent.getStringExtra("DISPLAY_SCENE_ID")?.let { sceneIdString ->
            if (sceneIdString.isNotBlank()) {
                pendingSceneIdNavigation = sceneIdString
                pendingSceneTitleNavigation = null
                Log.d(TAG, "handleInitialIntentNavigation: Set pendingSceneIdNavigation to $sceneIdString")
                if (allUserScenes.isNotEmpty()) {
                    processPendingNavigation()
                }
                navigationAttempted = true
            }
        }
        if (!navigationAttempted) {
            intent.getStringExtra("SELECTED_SCENE_TITLE")?.let { title ->
                pendingSceneTitleNavigation = title
                pendingSceneIdNavigation = null
                Log.d(TAG, "handleInitialIntentNavigation: Set pendingSceneTitleNavigation to $title")
                if (allUserScenes.isNotEmpty()) {
                    processPendingNavigation()
                }
            }
        }
        intent.removeExtra("DISPLAY_SCENE_ID")
        intent.removeExtra("SELECTED_SCENE_TITLE")
    }

    private fun processPendingNavigation() {
        if (pendingSceneIdNavigation != null) {
            val idToNavigate = pendingSceneIdNavigation
            pendingSceneIdNavigation = null
            pendingSceneTitleNavigation = null
            Log.d(TAG, "processPendingNavigation: Handling pending navigation to scene ID: $idToNavigate")
            navigateToSceneByIdInRandomView(idToNavigate)
        } else if (pendingSceneTitleNavigation != null) {
            val titleToNavigate = pendingSceneTitleNavigation
            pendingSceneTitleNavigation = null
            pendingSceneIdNavigation = null
            if (titleToNavigate != null) {
                Log.d(TAG, "processPendingNavigation: Handling pending navigation to scene Title: $titleToNavigate")
                navigateToSceneInRandomView(titleToNavigate)
            } else {
                Log.w(TAG, "processPendingNavigation: pendingSceneTitleNavigation was set but became null.")
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInitialIntentNavigation()
    }

    private fun navigateToSceneByIdInRandomView(sceneIdToFind: String?) {
        if (sceneIdToFind == null) {
            Log.w(TAG, "navigateToSceneByIdInRandomView: sceneIdToFind is null.")
            return
        }
        Log.d(TAG, "navigateToSceneByIdInRandomView: Attempting to find scene with ID: '$sceneIdToFind'")
        if (allUserScenes.isEmpty()) {
            Log.w(TAG, "navigateToSceneByIdInRandomView: allUserScenes is empty. Cannot find scene.")
            pendingSceneIdNavigation = sceneIdToFind // Re-set for when scenes load
            return
        }
        var foundScene: Scene? = null
        for (scene in allUserScenes) {
            val assetIdFormatted = "asset_${scene.id}"
            if (scene.firestoreId.isNotBlank() && scene.firestoreId == sceneIdToFind) {
                foundScene = scene; break
            }
            if (assetIdFormatted == sceneIdToFind) {
                foundScene = scene; break
            }
        }
        if (foundScene != null) {
            Log.d(TAG, "navigateToSceneByIdInRandomView: Found scene: ${foundScene.title}")
            switchToRandomMode(foundScene)
        } else {
            Log.w(TAG, "navigateToSceneByIdInRandomView: Scene with ID '$sceneIdToFind' not found in allUserScenes (${allUserScenes.size} scenes checked).")
            showMaterialToast("Scene with ID '$sceneIdToFind' not found.", false)
        }
    }

    private fun navigateToSceneInRandomView(sceneTitle: String) {
        Log.d(TAG, "navigateToSceneInRandomView: Attempting to find scene with title: '$sceneTitle'")
        if (allUserScenes.isEmpty()) {
            Log.w(TAG, "navigateToSceneInRandomView: allUserScenes is empty. Cannot find scene.")
            pendingSceneTitleNavigation = sceneTitle // Re-set for when scenes load
            return
        }
        val scene = allUserScenes.find { it.title.equals(sceneTitle, ignoreCase = true) }
        if (scene != null) {
            Log.d(TAG, "navigateToSceneInRandomView: Found scene: ${scene.title}")
            switchToRandomMode(scene)
        } else {
            Log.w(TAG, "navigateToSceneInRandomView: Scene with title '$sceneTitle' not found.")
            showMaterialToast("Scene '$sceneTitle' not found.", false)
        }
    }

    override fun onResume() {
        super.onResume()
        // updateUIForCurrentUser() // AuthStateListener handles this
        // If MODE_FAVORITES was the last active mode, refresh its list
        if (currentMode == MODE_FAVORITES) {
            updateFavoritesList()
        }
        // Ensure correct menu state
        invalidateOptionsMenu()
        
        // Explicitly set "Scenes" as checked in the navigation drawer
        if (::navigationView.isInitialized) {
            navigationView.setCheckedItem(R.id.nav_scenes)
        }
        // Check if user is logged in and update UI accordingly
        // This is also handled by AuthStateListener, but an explicit call can ensure immediate UI consistency
        // if the listener hasn't fired yet or if there are subtle timing issues.
        // However, to avoid redundant calls, primarily rely on the listener.
        // handleAuthStateChange(auth.currentUser, isInitialCheck = false) // Re-evaluate if this is needed or causes issues
    }

    private fun filterScenes(query: String?) {
        val showDefault = chipDefaultScenes.isChecked
        val showCustom = chipCustomScenes.isChecked

        displayedScenes = allUserScenes.filter { scene ->
            val matchesType = (showDefault && !scene.isCustom) || (showCustom && scene.isCustom)
            val matchesQuery = query.isNullOrBlank() ||
                               scene.title.contains(query, ignoreCase = true) ||
                               scene.content.contains(query, ignoreCase = true)
            matchesType && matchesQuery
        }
        Log.d(TAG, "filterScenes: Query='$query', showDefault=$showDefault, showCustom=$showCustom. Displayed count: ${displayedScenes.size}")

        if (currentMode == MODE_RANDOM) {
            if (displayedScenes.isNotEmpty()) {
                // If current scene is no longer in filtered list, or no scene is displayed, pick a new random one
                val currentDisplayed = getCurrentScene()
                if (currentDisplayed == null || !displayedScenes.contains(currentDisplayed)) {
                    displayRandomScene()
                } else {
                    // Current scene is still valid, just update UI elements like chip counts
                    updateUI()
                }
            } else {
                clearSceneDisplay() // No scenes match filter
                titleTextView.text = getString(R.string.no_scenes_match_filter_title)
                contentTextView.text = getString(R.string.no_scenes_match_filter_content)
            }
        } else if (currentMode == MODE_EDIT) {
            updateEditList() // Refresh edit list based on new displayedScenes
        }
        // Update chip counts regardless of mode
        // Counts should reflect the total number of default/custom scenes available in allUserScenes
        val totalCustomCount = allUserScenes.count { it.isCustom }
        val totalDefaultCount = allUserScenes.count { !it.isCustom }
        chipCustomScenes.text = "$baseTextChipCustomScenes ($totalCustomCount)"
        chipDefaultScenes.text = "$baseTextChipDefaultScenes ($totalDefaultCount)"
    }

    private fun updateUI() {
        when (currentMode) {
            MODE_RANDOM -> {
                randomContent.visibility = View.VISIBLE
                favoritesContainer.visibility = View.GONE
                editContainer.visibility = View.GONE
                addSceneButton.visibility = View.GONE
                resetScenesButton.visibility = View.GONE
                sceneFilterChipGroup.visibility = View.GONE
                val sceneToDisplay = getCurrentScene()
                if (sceneToDisplay != null) displayScene(sceneToDisplay)
                else if (displayedScenes.isNotEmpty()) displayRandomScene()
                else {
                    clearSceneDisplay()
                    if (allUserScenes.isNotEmpty()) { // Filter resulted in no displayed scenes
                        titleTextView.text = getString(R.string.no_scenes_match_filter_title)
                        contentTextView.text = getString(R.string.no_scenes_match_filter_content)
                    }
                    // If allUserScenes is also empty, clearSceneDisplay already set appropriate text
                }
                randomizeButton.visibility = View.VISIBLE
                previousButton.visibility = View.VISIBLE
            }
            MODE_FAVORITES -> {
                randomContent.visibility = View.GONE; favoritesContainer.visibility = View.VISIBLE; editContainer.visibility = View.GONE
                addSceneButton.visibility = View.GONE; resetScenesButton.visibility = View.GONE; sceneFilterChipGroup.visibility = View.GONE
                updateFavoritesList()
                editButton.visibility = View.GONE; shareButton.visibility = View.GONE; randomizeButton.visibility = View.GONE; previousButton.visibility = View.GONE
            }
            MODE_EDIT -> {
                randomContent.visibility = View.GONE; favoritesContainer.visibility = View.GONE; editContainer.visibility = View.VISIBLE
                addSceneButton.visibility = View.VISIBLE; resetScenesButton.visibility = View.VISIBLE; sceneFilterChipGroup.visibility = View.VISIBLE
                updateEditList()
                editButton.visibility = View.GONE; shareButton.visibility = View.GONE; randomizeButton.visibility = View.GONE; previousButton.visibility = View.GONE
            }
        }
        invalidateOptionsMenu()
        updateFavoriteIcon()
        updatePreviousButtonState()
    }

    private fun updateFavoritesList() {
        if (currentMode == MODE_FAVORITES) {
            if (auth.currentUser == null) {
                val localFavs = localFavoritesViewModel.localFavoriteScenes.value
                favoritesAdapter.submitList(localFavs)
                emptyFavoritesView.visibility = if (localFavs.isEmpty()) View.VISIBLE else View.GONE
            } else {
                val cloudFavObjects = cloudFavoritesViewModel.favorites.value
                val favoriteSceneDetails = allUserScenes.filter { scene ->
                    cloudFavObjects.any { fav -> fav.itemId == getSceneIdentifier(scene) && fav.itemType == "scene" }
                }
                favoritesAdapter.submitList(favoriteSceneDetails)
                emptyFavoritesView.visibility = if (favoriteSceneDetails.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateEditList() {
        editAdapter.submitList(displayedScenes.toList()) // Assumes displayedScenes is correctly filtered for edit mode
    }

    private fun getSceneIdentifier(scene: Scene): String {
        return if (scene.firestoreId.isNotBlank()) {
            scene.firestoreId
        } else {
            "asset_${scene.id}"
        }
    }

    private fun toggleFavorite() {
        val currentScene = getCurrentScene() ?: run {
            Log.w(TAG, "toggleFavorite: getCurrentScene() returned null.")
            return
        }
        val sceneIdentifier = getSceneIdentifier(currentScene)
        val isCurrentlyFavorite: Boolean

        if (auth.currentUser != null) {
            isCurrentlyFavorite = cloudFavoritesViewModel.favorites.value.any { it.itemId == sceneIdentifier && it.itemType == "scene" }
            if (isCurrentlyFavorite) {
                cloudFavoritesViewModel.removeCloudFavorite(sceneIdentifier, "scene")
                Toast.makeText(applicationContext, "💔 '${currentScene.title}' removed from cloud favorites", Toast.LENGTH_SHORT).show()
            } else {
                cloudFavoritesViewModel.addCloudFavorite(sceneIdentifier, "scene")
                Toast.makeText(applicationContext, "❤️ '${currentScene.title}' added to cloud favorites", Toast.LENGTH_SHORT).show()
            }
        } else {
            isCurrentlyFavorite = localFavoritesViewModel.localFavoriteScenes.value.any { getSceneIdentifier(it) == sceneIdentifier }
            if (isCurrentlyFavorite) {
                localFavoritesViewModel.removeLocalFavoriteScene(sceneIdentifier, allUserScenes)
                Toast.makeText(applicationContext, "💔 '${currentScene.title}' removed from local favorites", Toast.LENGTH_SHORT).show()
            } else {
                localFavoritesViewModel.addLocalFavoriteScene(sceneIdentifier, allUserScenes)
                Toast.makeText(applicationContext, "❤️ '${currentScene.title}' added to local favorites", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeFromFavorites(scene: Scene) {
        val sceneIdentifier = getSceneIdentifier(scene)
        if (auth.currentUser != null) {
            cloudFavoritesViewModel.removeCloudFavorite(sceneIdentifier, "scene")
            Toast.makeText(applicationContext, "💔 '${scene.title}' removed from favorites", Toast.LENGTH_SHORT).show()
        } else {
            localFavoritesViewModel.removeLocalFavoriteScene(sceneIdentifier, allUserScenes)
            Toast.makeText(applicationContext, "💔 '${scene.title}' removed from favorites", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMaterialToast(message: String, isAddedToFavorite: Boolean) { // Parameter isAddedToFavorite might be misleading now
        currentToast?.cancel()
        // Reverted: Emoji logic will be specific to favorite actions
        currentToast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).apply {
            show()
        }
    }

    private fun updateFavoriteIcon(menu: Menu? = null) {
        val m = menu ?: topAppBar.menu
        m.findItem(R.id.action_favorite)?.let { menuItem ->
            val currentScene = getCurrentScene()
            if (currentScene != null && currentMode == MODE_RANDOM) {
                menuItem.isVisible = true
                val sceneIdentifier = getSceneIdentifier(currentScene)
                val isFavorited: Boolean = if (auth.currentUser != null) {
                    cloudFavoritesViewModel.favorites.value.any { it.itemId == sceneIdentifier && it.itemType == "scene" }
                } else {
                    localFavoritesViewModel.localFavoriteScenes.value.any { getSceneIdentifier(it) == sceneIdentifier }
                }

                if (isFavorited) {
                    menuItem.icon = ContextCompat.getDrawable(this, R.drawable.ic_favorite_filled)
                    menuItem.title = getString(R.string.remove_from_favorites)
                } else {
                    menuItem.icon = ContextCompat.getDrawable(this, R.drawable.ic_favorite)
                    menuItem.title = getString(R.string.add_to_favorites)
                }
            } else {
                menuItem.isVisible = false
            }
        }
    }

    private fun displayRandomScene() {
        if (displayedScenes.isEmpty()) { clearSceneDisplay(); return }
        sceneCardView.visibility = View.VISIBLE
        val previousScene = getCurrentScene()
        currentSceneIndex = if (displayedScenes.size == 1) 0 else {
            var nextIdx: Int
            do { nextIdx = Random.nextInt(displayedScenes.size) } while (displayedScenes[nextIdx] == previousScene && displayedScenes.size > 1)
            nextIdx
        }
        val sceneToDisplay = displayedScenes[currentSceneIndex]
        displayScene(sceneToDisplay)
        if (sceneHistory.isEmpty() || sceneHistory.last() != sceneToDisplay) sceneHistory.add(sceneToDisplay)
        historyPosition = sceneHistory.lastIndexOf(sceneToDisplay).takeIf { it != -1 } ?: (sceneHistory.size - 1).coerceAtLeast(0)
        updatePreviousButtonState()
    }

    private fun displayNextScene() {
        if (displayedScenes.isEmpty()) { clearSceneDisplay(); return }
        sceneCardView.visibility = View.VISIBLE
        currentSceneIndex = (currentSceneIndex + 1) % displayedScenes.size
        val sceneToDisplay = displayedScenes[currentSceneIndex]
        displayScene(sceneToDisplay)
        if (sceneHistory.isEmpty() || sceneHistory.last() != sceneToDisplay) sceneHistory.add(sceneToDisplay)
        historyPosition = sceneHistory.lastIndexOf(sceneToDisplay).takeIf { it != -1 } ?: (sceneHistory.size - 1).coerceAtLeast(0)
        updatePreviousButtonState()
    }

    private fun switchToRandomMode(scene: Scene) {
        currentMode = MODE_RANDOM
        bottomNavigation.selectedItemId = R.id.navigation_random
        currentSceneIndex = displayedScenes.indexOf(scene).takeIf { it != -1 } ?: -1 // Ensure index is valid for current filter
        displayScene(scene)
        if (currentSceneIndex == -1) Log.w(TAG, "Switched to scene not in current displayedScenes filter: ${scene.title}")

        if (sceneHistory.isEmpty() || sceneHistory.last() != scene) sceneHistory.add(scene)
        historyPosition = sceneHistory.lastIndexOf(scene).takeIf { it != -1 } ?: (sceneHistory.size - 1).coerceAtLeast(0)
        updatePreviousButtonState()
        updateUI()
    }

    private fun displayScene(scene: Scene) {
        titleTextView.text = HtmlCompat.fromHtml(formatMarkdownText(scene.title), HtmlCompat.FROM_HTML_MODE_LEGACY)
        contentTextView.text = HtmlCompat.fromHtml(formatMarkdownText(scene.content), HtmlCompat.FROM_HTML_MODE_LEGACY)
        sceneCardView.visibility = View.VISIBLE
        val isInRandomModeAndSceneSelected = currentMode == MODE_RANDOM && getCurrentScene() != null
        editButton.visibility = if (isInRandomModeAndSceneSelected) View.VISIBLE else View.GONE
        shareButton.visibility = if (isInRandomModeAndSceneSelected) View.VISIBLE else View.GONE
        updateFavoriteIcon()
        updatePreviousButtonState()
    }

    private fun updatePreviousButtonState() {
        previousButton.isEnabled = historyPosition > 0 && sceneHistory.isNotEmpty()
    }

    private fun formatMarkdownText(text: String): String {
        var htmlText = text
        htmlText = htmlText.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
        htmlText = htmlText.replace(Regex("__(.*?)__"), "<b>$1</b>")
        htmlText = htmlText.replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
        htmlText = htmlText.replace(Regex("_(.*?)_"), "<i>$1</i>")
        htmlText = htmlText.replace(Regex("~~(.*?)~~"), "<s>$1</s>")
        htmlText = htmlText.replace(Regex("\\[(.*?)]\\((.*?)\\)"), "<a href='$2'>$1</a>")
        htmlText = htmlText.replace("\n", "<br>")
        return htmlText
    }

    private fun shareCurrentScene() {
        val sceneToShare = getCurrentScene() ?: run { showMaterialToast("No scene to share.", false); return }
        val shareText = "Scene: ${sceneToShare.title}\n\n${sceneToShare.content}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NoSafeWord Scene: ${sceneToShare.title}")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }, "Share Scene Via"))
    }

    private fun addCurrentSceneToPlan() {
        val sceneToAdd = getCurrentScene() ?: run { showMaterialToast("No scene to add.", false); return }
        val prefs = getSharedPreferences(plannedItemsPrefsName, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(plannedItemsKey, null)
        val typeToken = object : TypeToken<MutableList<PlannedItem>>() {}.type
        val plannedItems: MutableList<PlannedItem> = gson.fromJson(jsonString, typeToken) ?: mutableListOf()

        if (plannedItems.any { it.name == sceneToAdd.title && it.type == "Scene" }) {
            showMaterialToast("'${sceneToAdd.title}' is already in your plan.", false); return
        }
        plannedItems.add(PlannedItem(
            name = sceneToAdd.title,
            type = "Scene",
            details = sceneToAdd.content.take(100) + if (sceneToAdd.content.length > 100) "..." else "",
            order = plannedItems.size
        ))
        plannedItems.forEachIndexed { index, item -> item.order = index }
        prefs.edit().putString(plannedItemsKey, gson.toJson(plannedItems)).apply()
        showMaterialToast("'${sceneToAdd.title}' added to Plan your Night.", true)
    }

    private fun showEditDialog(scene: Scene?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_scene, null)
        val titleEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_title_input)
        val contentEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_content_input)
        scene?.let { titleEditText.setText(it.title); contentEditText.setText(it.content) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (scene == null) getString(R.string.new_scene) else getString(R.string.edit_scene))
            .setView(dialogView)
            .setPositiveButton(if (scene == null) getString(R.string.add) else getString(R.string.save), null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleEditText.text.toString().trim()
                val content = contentEditText.text.toString().trim()
                var valid = true
                if (title.isEmpty()) { titleEditText.error = "Title cannot be empty"; valid = false }
                if (content.isEmpty()) { contentEditText.error = "Content cannot be empty"; valid = false }
                if (valid) {
                    val currentUid = auth.currentUser?.uid ?: ""
                    if (scene == null) {
                        scenesViewModel.addScene(Scene(title = title, content = content, isCustom = true, userId = currentUid))
                        showMaterialToast("Adding scene: $title", false)
                    } else {
                        val updatedScene = scene.copy(
                            title = title,
                            content = content,
                            isCustom = true,
                            userId = scene.userId.ifBlank { currentUid }
                        )
                        if (updatedScene.firestoreId.isBlank() && updatedScene.id == 0 && updatedScene.isCustom) {
                             showMaterialToast("Error: Scene ID missing for custom scene.", false)
                        } else {
                            scenesViewModel.updateScene(updatedScene)
                            showMaterialToast("Updating scene: ${updatedScene.title}", false)
                        }
                    }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirmation(scene: Scene) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_scene_title))
            .setMessage(getString(R.string.delete_scene_confirmation, scene.title))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                scenesViewModel.deleteScene(scene)
                removeFromFavorites(scene) // Ensure it's removed from local/cloud favorites as well
                showMaterialToast("Deleted \"${scene.title}\".", false)
            }
            .show()
    }

    private fun showResetConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reset_default_scenes_title))
            .setMessage(getString(R.string.reset_default_scenes_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.reset)) { _, _ ->
                if (auth.currentUser != null) {
                    scenesViewModel.resetDefaultScenesForCurrentUser()
                    showMaterialToast("Resetting default scenes for your account...", false)
                } else {
                    scenesViewModel.resetLocalScenesToDefault()
                    showMaterialToast("Resetting scenes to default...", false)
                }
            }
            .show()
    }

    private fun signIn() {
        Log.d(TAG, "signIn: Attempting Google Sign-In.")
        startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                if (account?.idToken != null) {
                    Log.d(TAG, "Google Sign-In successful, token: ${account.idToken?.take(10)}...")
                    firebaseAuthWithGoogle(account.idToken!!)
                } else {
                    Log.w(TAG, "Google Sign-In successful but idToken is null.")
                    Toast.makeText(this, "Google Sign-In failed: No ID Token.", Toast.LENGTH_LONG).show()
                    // AuthStateListener will handle UI update for signed-out state
                }
            } catch (e: ApiException) {
                Log.w(TAG, "Google sign in failed: ${e.statusCode}", e)
                Toast.makeText(this, "Google Sign-In failed: ${e.message}", Toast.LENGTH_LONG).show()
                // AuthStateListener will handle UI update for signed-out state
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase Auth successful. User: ${auth.currentUser?.email}")
                    Toast.makeText(this, "Sign-in successful.", Toast.LENGTH_SHORT).show()
                    // AuthStateListener in observeAuthAndLoadInitialFavorites will handle the rest:
                    // - Triggering merge via cloudFavoritesViewModel.loadCloudFavorites(performMerge = true)
                    // - Updating UI
                } else {
                    Log.w(TAG, "Firebase Auth failed.", task.exception)
                    Toast.makeText(this, "Firebase Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    // AuthStateListener will handle UI update for signed-out state if auth state changes to null
                }
            }
    }

    private fun setupAuthStateListener() { // Renamed from observeAuthAndLoadInitialFavorites for clarity
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
             handleAuthStateChange(firebaseAuth.currentUser, isInitialCheck = false)
        }
    }

    private fun handleAuthStateChange(user: com.google.firebase.auth.FirebaseUser?, isInitialCheck: Boolean) {
        if (user != null) {
            Log.d(TAG, "handleAuthStateChange: User is SIGNED IN (${user.uid}). Initial: $isInitialCheck")
            cloudFavoritesViewModel.refreshCloudFavorites(performMergeIfNeeded = true)
        } else {
            Log.d(TAG, "handleAuthStateChange: User is SIGNED OUT. Initial: $isInitialCheck")
            if (allUserScenes.isNotEmpty() || !isInitialCheck) { // Avoid refreshing if scenes aren't loaded yet on initial cold start
                localFavoritesViewModel.refreshLocalFavoriteScenes(allUserScenes)
            }
        }
        updateUI()
        invalidateOptionsMenu()
    }


    // The old updateUIForCurrentUser and updateUIForSignedOutUser are now largely
    // superseded by the AuthStateListener and direct ViewModel observation.
    // Retaining simplified versions for explicit calls if absolutely necessary,
    // but ideally, UI updates flow from ViewModel state changes.

    private fun updateUIForCurrentUser() { // Potentially called after specific actions if needed
        Log.d(TAG, "updateUIForCurrentUser: (Manual call if needed) User is signed in.")
        invalidateOptionsMenu()
        updateUI()
    }

    private fun updateUIForSignedOutUser() { // Potentially called after specific actions if needed
        Log.d(TAG, "updateUIForSignedOutUser: (Manual call if needed) User is signed out.")
        if (allUserScenes.isNotEmpty()) { // Ensure allUserScenes is populated before refreshing
            localFavoritesViewModel.refreshLocalFavoriteScenes(allUserScenes)
        }
        currentSceneIndex = -1 // Reset current scene view
        sceneHistory.clear()
        historyPosition = -1
        updateUI()
        invalidateOptionsMenu()
        // Toast.makeText(this, "You are signed out.", Toast.LENGTH_LONG).show() // Listener might show this
    }

    // Removed syncFavoritesOnLogin and updateLocalStoreWithFirestoreData

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        try { 
            menuInflater.inflate(R.menu.main_menu, menu) 
            val searchItem = menu.findItem(R.id.action_search) 
            (searchItem?.actionView as? SearchView)?.apply {
                setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean { filterScenes(query); return true }
                    override fun onQueryTextChange(newText: String?): Boolean { filterScenes(newText); return true }
                })
            }
            searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
                override fun onMenuItemActionCollapse(item: MenuItem): Boolean { filterScenes(null); return true }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating menu or setting up search: ${e.message}")
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val searchItem = menu?.findItem(R.id.action_search)
        val favoriteItem = menu?.findItem(R.id.action_favorite)
        val addToPlanToolbarItem = menu?.findItem(R.id.action_add_to_plan_toolbar)
        val addToPlanOverflowItem = menu?.findItem(R.id.action_add_to_plan_overflow)
        val settingsItem = menu?.findItem(R.id.action_settings)
        val signInSignOutItem = menu?.findItem(R.id.action_sign_in_out)

        if (currentMode == MODE_RANDOM) {
            searchItem?.isVisible = false
            favoriteItem?.isVisible = true
            addToPlanToolbarItem?.isVisible = true
            addToPlanOverflowItem?.isVisible = false // Hide overflow version
            settingsItem?.isVisible = false
            signInSignOutItem?.isVisible = false

            if (searchItem?.isActionViewExpanded == true) {
                searchItem.collapseActionView()
            }
        } else if (currentMode == MODE_EDIT) {
            searchItem?.isVisible = true
            favoriteItem?.isVisible = false
            addToPlanToolbarItem?.isVisible = false
            addToPlanOverflowItem?.isVisible = false
            settingsItem?.isVisible = false
            signInSignOutItem?.isVisible = false
        } else { 
            searchItem?.isVisible = true
            favoriteItem?.isVisible = false
            addToPlanToolbarItem?.isVisible = false
            addToPlanOverflowItem?.isVisible = true
            settingsItem?.isVisible = true
            signInSignOutItem?.isVisible = true
        }
        
        signInSignOutItem?.title = if (auth.currentUser == null) getString(R.string.sign_in) else getString(R.string.sign_out)
        updateFavoriteIcon(menu)

        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
        val iconColor = typedValue.data

        try {
            searchItem?.icon?.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            addToPlanToolbarItem?.icon?.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying color filter to menu icons", e)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) return true
        try {
            return when (item.itemId) {
                R.id.action_favorite -> {
                    val itemView = findViewById<View>(R.id.action_favorite)
                    itemView?.animate()
                        ?.scaleX(1.2f)?.scaleY(1.2f)?.setDuration(150)?.withEndAction {
                            itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                        }?.start()
                    toggleFavorite()
                    true
                }
                R.id.action_add_to_plan_toolbar, R.id.action_add_to_plan_overflow -> {
                    addCurrentSceneToPlan()
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_sign_in_out -> {
                    if (auth.currentUser == null) {
                        signIn()
                    } else {
                        auth.signOut() // AuthStateListener will handle UI update
                        googleSignInClient.signOut().addOnCompleteListener {
                            Log.d(TAG, "Google Sign-Out complete.")
                        }
                    }
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onOptionsItemSelected: ${e.message}")
            return super.onOptionsItemSelected(item)
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onPause() {
        super.onPause()
        currentToast?.cancel()
    }
}
