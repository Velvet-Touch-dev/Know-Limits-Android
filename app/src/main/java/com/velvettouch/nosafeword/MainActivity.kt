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
import kotlinx.coroutines.flow.collectLatest
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
    private lateinit var favoritesContainer: FrameLayout
    private lateinit var editContainer: FrameLayout
    private lateinit var favoritesRecyclerView: RecyclerView
    private lateinit var editRecyclerView: RecyclerView
    private lateinit var emptyFavoritesView: LinearLayout
    private lateinit var sceneFilterChipGroup: ChipGroup
    private lateinit var chipDefaultScenes: Chip
    private lateinit var chipCustomScenes: Chip
    private lateinit var baseTextChipDefaultScenes: String
    private lateinit var baseTextChipCustomScenes: String

    private val scenesViewModel: ScenesViewModel by viewModels()
    private val favoritesViewModel: FavoritesViewModel by viewModels()
    private var displayedScenes: List<Scene> = listOf()
    private var allUserScenes: List<Scene> = listOf()

    private var currentSceneIndex: Int = -1
    private var sceneHistory: MutableList<Scene> = mutableListOf()
    private var historyPosition: Int = -1

    private var favorites: MutableSet<String> = mutableSetOf()
    private var currentMode: Int = MODE_RANDOM
    private var currentToast: Toast? = null
    private var pendingSceneTitleNavigation: String? = null
    private var pendingSceneIdNavigation: String? = null // For navigating by original ID (asset) or Firestore ID

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    private val gson = Gson()
    private val plannedItemsPrefsName = "PlanNightPrefs"
    private val plannedItemsKey = "plannedItemsList"
    private val favoritesPrefsName = "FavoritesPrefs"
    private val favoriteSceneIdsKey = "favoriteSceneIds"

    private lateinit var favoritesAdapter: FavoriteScenesAdapter
    private lateinit var editAdapter: EditScenesAdapter

    companion object {
        private const val MODE_RANDOM = 0
        private const val MODE_FAVORITES = 1
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
        titleTextView.text = "No Scenes Available" // Hardcoded - Replace with R.string.no_scenes_available_title
        contentTextView.text = "There are currently no scenes to display." // Hardcoded - Replace with R.string.no_scenes_available_content
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
        loadFavoritesFromPrefs()
        observeViewModel()
        setupButtonListeners()
        setupChipListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (currentMode == MODE_RANDOM && historyPosition > 0 && sceneHistory.isNotEmpty()) {
                    previousButton.performClick()
                } else {
                    if (isEnabled) { isEnabled = false; super@MainActivity.onBackPressed() }
                }
            }
        })
        handleInitialIntentNavigation()
        updateUI()
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
        navigationView.removeHeaderView(navigationView.getHeaderView(0))
        navigationView.setNavigationItemSelectedListener { menuItem ->
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = when (menuItem.itemId) {
                R.id.nav_positions -> Intent(this, PositionsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_body_worship -> Intent(this, BodyWorshipActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_task_list -> Intent(this, TaskListActivity::class.java)
                R.id.nav_plan_night -> Intent(this, PlanNightActivity::class.java)
                R.id.nav_favorites -> Intent(this, FavoritesActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_settings -> Intent(this, SettingsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_scenes -> null
                else -> null
            }
            intent?.let { startActivity(it) }
            true
        }
        bottomNavigation.setOnItemSelectedListener { item ->
            currentMode = when (item.itemId) {
                R.id.navigation_random -> MODE_RANDOM
                R.id.navigation_edit -> MODE_EDIT
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

        favoritesAdapter = FavoriteScenesAdapter(
            onItemClick = { scene: Scene -> switchToRandomMode(scene) },
            onItemRemoved = { scene: Scene -> removeFromFavorites(scene) }
        )
        favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = favoritesAdapter
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
                    val background = ColorDrawable(Color.parseColor("#F44336"))
                    val deleteIcon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)?.apply { setTint(Color.WHITE) }
                    val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)
                    if (dX > 0) {
                        val iconLeft = itemView.left + iconMargin; val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?:0)
                        deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    } else if (dX < 0) {
                        val iconRight = itemView.right - iconMargin; val iconLeft = iconRight - (deleteIcon?.intrinsicWidth ?:0)
                        deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    } else { background.setBounds(0,0,0,0) }
                    background.draw(c); deleteIcon?.draw(c)
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
            ItemTouchHelper(swipeHandler).attachToRecyclerView(this)
        }
    }

    private fun loadFavoritesFromPrefs() {
        val prefs = getSharedPreferences(favoritesPrefsName, Context.MODE_PRIVATE)
        favorites = prefs.getStringSet(favoriteSceneIdsKey, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveFavoritesToPrefs() {
        val prefs = getSharedPreferences(favoritesPrefsName, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(favoriteSceneIdsKey, favorites).apply()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            scenesViewModel.scenes.collectLatest { scenesList ->
                Log.d(TAG, "ViewModel scenes collected. Count: ${scenesList.size}")
                allUserScenes = scenesList
                val currentSearchQuery = (topAppBar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.query?.toString()
                filterScenes(currentSearchQuery) // This updates displayedScenes
                Log.d(TAG, "Displayed scenes after filter. Count: ${displayedScenes.size}")

                // Handle pending navigation now that allUserScenes is populated
                if (pendingSceneIdNavigation != null) {
                    Log.d(TAG, "observeViewModel: Handling pending navigation to scene ID: $pendingSceneIdNavigation")
                    navigateToSceneByIdInRandomView(pendingSceneIdNavigation)
                    pendingSceneIdNavigation = null // Reset after handling
                    pendingSceneTitleNavigation = null
                } else if (pendingSceneTitleNavigation != null) {
                    Log.d(TAG, "observeViewModel: Handling pending navigation to scene Title: $pendingSceneTitleNavigation")
                    navigateToSceneInRandomView(pendingSceneTitleNavigation!!)
                    pendingSceneTitleNavigation = null
                    pendingSceneIdNavigation = null // Reset after handling
                } else {
                    // Only proceed with default random display logic if no pending navigation
                    val currentActuallyDisplayedScene = getCurrentScene() // Use consistent helper
                    if (currentMode == MODE_RANDOM) {
                        if (displayedScenes.isNotEmpty() && (currentActuallyDisplayedScene == null || !displayedScenes.contains(currentActuallyDisplayedScene))) {
                            Log.d(TAG, "Condition met to displayRandomScene in ViewModel observer (no pending nav).")
                            displayRandomScene()
                        } else if (displayedScenes.isEmpty()) {
                            Log.d(TAG, "DisplayedScenes is empty in ViewModel observer, calling clearSceneDisplay (no pending nav).")
                            clearSceneDisplay()
                            if(allUserScenes.isNotEmpty()) {
                                titleTextView.text = "No Scenes Match Filter" // Hardcoded
                                contentTextView.text = "Try adjusting filters or search." // Hardcoded
                            }
                        } else {
                            Log.d(TAG, "ViewModel observer: Displayed scenes not empty, current scene is: ${currentActuallyDisplayedScene?.title} (no pending nav)")
                        }
                    }
                }
                updateFavoritesList()
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
                        currentSceneIndex = if (indexInDisplayed != -1) indexInDisplayed else -1
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
            updateUI()
        }
        chipDefaultScenes.setOnCheckedChangeListener(chipListener)
        chipCustomScenes.setOnCheckedChangeListener(chipListener)
        chipDefaultScenes.isChecked = true
        chipCustomScenes.isChecked = true
    }
    
    private fun handleInitialIntentNavigation() {
        intent.getStringExtra("SELECTED_SCENE_TITLE")?.let { title ->
            pendingSceneTitleNavigation = title
            pendingSceneIdNavigation = null // Clear ID navigation if title is provided
        }
        // Check for ID after title, ID takes precedence if both somehow present
        intent.getStringExtra("DISPLAY_SCENE_ID")?.let { sceneIdString ->
            if (sceneIdString.isNotBlank()) {
                pendingSceneIdNavigation = sceneIdString
                pendingSceneTitleNavigation = null // Clear title navigation if ID is provided
                Log.d(TAG, "handleInitialIntentNavigation: Set pendingSceneIdNavigation to $sceneIdString")
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
        // Find by Firestore ID first, then by "asset_ID"
        val sceneToNavigate = allUserScenes.find { scene -> scene.firestoreId == sceneIdToFind }
            ?: allUserScenes.find { scene -> "asset_${scene.id}" == sceneIdToFind }

        if (sceneToNavigate != null) {
            Log.d(TAG, "Navigating to scene by ID: ${sceneToNavigate.title} (Searched ID: $sceneIdToFind)")
            currentMode = MODE_RANDOM
            bottomNavigation.selectedItemId = R.id.navigation_random

            val targetIndexInDisplayed = displayedScenes.indexOf(sceneToNavigate)
            if (targetIndexInDisplayed != -1) {
                currentSceneIndex = targetIndexInDisplayed
                displayScene(displayedScenes[currentSceneIndex])
            } else {
                displayScene(sceneToNavigate)
                currentSceneIndex = -1 // Mark as not directly in the filtered list's sequence
            }

            if (sceneHistory.isEmpty() || sceneHistory.last() != sceneToNavigate) {
                sceneHistory.add(sceneToNavigate)
            }
            historyPosition = sceneHistory.lastIndexOf(sceneToNavigate).takeIf { it != -1 } ?: (sceneHistory.size - 1).coerceAtLeast(0)
            
            updatePreviousButtonState()
            updateUI()
        } else {
            showMaterialToast("Scene with ID '$sceneIdToFind' not found.", false)
            Log.w(TAG, "Scene with ID '$sceneIdToFind' not found for navigation.")
        }
    }
    
    private fun navigateToSceneInRandomView(sceneTitle: String) {
        val sceneToNavigate = allUserScenes.find { scene: Scene -> scene.title.equals(sceneTitle, ignoreCase = true) }
        if (sceneToNavigate != null) {
            Log.d(TAG, "Navigating to scene by Title: ${sceneToNavigate.title}")
            currentMode = MODE_RANDOM
            bottomNavigation.selectedItemId = R.id.navigation_random

            val targetIndexInDisplayed = displayedScenes.indexOf(sceneToNavigate)
            if (targetIndexInDisplayed != -1) {
                currentSceneIndex = targetIndexInDisplayed
                displayScene(displayedScenes[currentSceneIndex])
            } else {
                displayScene(sceneToNavigate)
                currentSceneIndex = -1
            }

            if (sceneHistory.isEmpty() || sceneHistory.last() != sceneToNavigate) {
                sceneHistory.add(sceneToNavigate)
            }
            historyPosition = sceneHistory.lastIndexOf(sceneToNavigate).takeIf { it != -1 } ?: (sceneHistory.size - 1).coerceAtLeast(0)
            
            updatePreviousButtonState()
            updateUI()
        } else {
            showMaterialToast("Scene '$sceneTitle' not found.", false)
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Current user UID: ${auth.currentUser?.uid}")
        updateUIForCurrentUser()
        // Pending navigation is now handled in the scenesViewModel.scenes.collectLatest block
        // to ensure allUserScenes is populated.

        // Fallback logic if scenes are already loaded and no pending navigation was handled by collector yet.
        // This might be redundant if collector always runs after onResume sets flags.
        if (currentMode == MODE_RANDOM && pendingSceneIdNavigation == null && pendingSceneTitleNavigation == null) {
            Log.d(TAG, "onResume: In MODE_RANDOM. Displayed scenes count: ${displayedScenes.size}, All scenes count: ${allUserScenes.size}")
            if (displayedScenes.isNotEmpty()) {
                if (getCurrentScene() == null) {
                    Log.d(TAG, "onResume: No current scene, displaying random.")
                    displayRandomScene()
                } else {
                     Log.d(TAG, "onResume: Current scene exists: ${getCurrentScene()?.title}")
                }
            } else if (allUserScenes.isNotEmpty()) { // If displayed is empty but allUserScenes is not (e.g. due to filter)
                Log.d(TAG, "onResume: Displayed scenes empty, but allUserScenes not. Clearing display for filter message.")
                clearSceneDisplay()
                titleTextView.text = "No Scenes Match Filter"
                contentTextView.text = "Try adjusting filters or search."
            } else { // If allUserScenes is also empty
                Log.d(TAG, "onResume: Both displayedScenes and allUserScenes are empty. Clearing display.")
                clearSceneDisplay()
            }
        }
        updateUI() // updateUI should be called to refresh based on current state
    }

    private fun filterScenes(query: String?) {
        val showDefault = chipDefaultScenes.isChecked
        val showCustom = chipCustomScenes.isChecked
        val previouslyDisplayedSceneObject = getCurrentScene()

        displayedScenes = allUserScenes.filter { scene: Scene ->
            val typeMatch = (showDefault && !scene.isCustom) || (showCustom && scene.isCustom)
            val queryMatch = query.isNullOrBlank() || scene.title.contains(query, ignoreCase = true) || scene.content.contains(query, ignoreCase = true)
            typeMatch && queryMatch
        }
        editAdapter.submitList(displayedScenes.toList())

        if (currentMode == MODE_RANDOM) {
            if (displayedScenes.isNotEmpty()) {
                var sceneToRedisplay: Scene? = null
                if (previouslyDisplayedSceneObject != null) {
                    if (previouslyDisplayedSceneObject.firestoreId.isNotEmpty()) {
                        // Case 1: Previously displayed scene had a non-empty firestoreId (Firestore scene or already localized local scene)
                        sceneToRedisplay = displayedScenes.find { it.firestoreId == previouslyDisplayedSceneObject.firestoreId }
                        Log.d(TAG, "FilterScenes: Attempting to find by existing firestoreId '${previouslyDisplayedSceneObject.firestoreId}'. Found: ${sceneToRedisplay != null}")
                    } else {
                        // Case 2: Previously displayed scene was a pristine default scene (empty firestoreId, but valid original 'id')
                        // The updated version in displayedScenes should now have this same original 'id' and a 'local_' prefixed firestoreId.
                        sceneToRedisplay = displayedScenes.find {
                            it.id == previouslyDisplayedSceneObject.id && // Match original integer ID
                            it.firestoreId.startsWith("local_")      // And ensure it's the one that got localized by the ViewModel
                        }
                        Log.d(TAG, "FilterScenes: Attempting to find by originalIntId '${previouslyDisplayedSceneObject.id}' and local_ prefix. Found: ${sceneToRedisplay != null}")

                        // Fallback: If somehow the scene was updated but didn't get a local_ ID (e.g., error in ViewModel or very quick succession of events)
                        // and it's still identifiable by its original ID and still has an empty firestoreId. This is less likely.
                        if (sceneToRedisplay == null) {
                            sceneToRedisplay = displayedScenes.find {
                                it.id == previouslyDisplayedSceneObject.id &&
                                previouslyDisplayedSceneObject.firestoreId.isEmpty() && // Original was pristine
                                it.firestoreId.isEmpty() // Current is still pristine (implies update failed to assign local_ ID)
                            }
                            if (sceneToRedisplay != null) {
                                Log.d(TAG, "FilterScenes: Fallback - Found by originalIntId '${previouslyDisplayedSceneObject.id}' and still empty firestoreId.")
                            }
                        }
                    }
                }

                if (sceneToRedisplay != null) {
                    currentSceneIndex = displayedScenes.indexOf(sceneToRedisplay)
                    if (currentSceneIndex != -1) {
                        displayScene(sceneToRedisplay)
                        Log.d(TAG, "FilterScenes: Redisplaying scene '${sceneToRedisplay.title}' at index $currentSceneIndex.")
                    } else {
                        // This should ideally not happen if sceneToRedisplay was found in displayedScenes.
                        Log.w(TAG, "FilterScenes: Scene '${sceneToRedisplay.title}' was resolved but indexOf failed. Displaying random.")
                        displayRandomScene()
                    }
                } else {
                    // No specific scene to re-display (e.g., it was filtered out, deleted, initial app load, or ID logic failed).
                    Log.d(TAG, "FilterScenes: No specific scene to redisplay. Displaying random scene.")
                    displayRandomScene() // This will set currentSceneIndex and call displayScene.
                }
            } else { // displayedScenes is empty
                clearSceneDisplay()
                if (allUserScenes.isNotEmpty()) {
                    titleTextView.text = "No Scenes Match Filter" // Hardcoded
                    contentTextView.text = "Try adjusting filters or search." // Hardcoded
                }
            }
        }
        updatePreviousButtonState()
        updateFavoritesList()
        updateEditList()

        // Update chip counts
        val customCountInDisplayed = displayedScenes.count { it.isCustom }
        val defaultCountInDisplayed = displayedScenes.count { !it.isCustom }
        chipCustomScenes.text = "$baseTextChipCustomScenes ($customCountInDisplayed)"
        chipDefaultScenes.text = "$baseTextChipDefaultScenes ($defaultCountInDisplayed)"
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
                    if (allUserScenes.isNotEmpty()) {
                        titleTextView.text = "No Scenes Match Filter" // Hardcoded
                        contentTextView.text = "Try adjusting filters or search." // Hardcoded
                    }
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
        val favoriteSceneObjects = allUserScenes.filter { scene ->
            val sceneIdentifier = if (scene.firestoreId.isNotBlank()) {
                scene.firestoreId
            } else {
                "asset_${scene.id}"
            }
            favorites.contains(sceneIdentifier)
        }
        favoritesAdapter.submitList(favoriteSceneObjects)
        emptyFavoritesView.visibility = if (favoriteSceneObjects.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateEditList() { 
        editAdapter.submitList(displayedScenes.toList())
    }

    private fun toggleFavorite() {
        val currentScene = getCurrentScene()
        if (currentScene == null) {
            Log.w(TAG, "toggleFavorite: getCurrentScene() returned null. Cannot toggle favorite.")
            return
        }
        Log.d(TAG, "toggleFavorite: Current Scene: Title='${currentScene.title}', ID='${currentScene.id}', FirestoreID='${currentScene.firestoreId}', isCustom='${currentScene.isCustom}'")

        val sceneIdentifier = if (currentScene.firestoreId.isNotBlank()) {
            currentScene.firestoreId
        } else {
            // For scenes from assets that might not have a firestoreId yet, use a stable local ID.
            // Assuming currentScene.id is the original unique ID from assets.
            "asset_${currentScene.id}"
        }
        Log.d(TAG, "toggleFavorite: Using sceneIdentifier: '$sceneIdentifier'")

        if (favorites.contains(sceneIdentifier)) {
            // It IS a favorite, so REMOVE it
            favorites.remove(sceneIdentifier)
            Log.d(TAG, "toggleFavorite: Removed '$sceneIdentifier' from local favorites. Current favorites: $favorites")
            lifecycleScope.launch {
                favoritesViewModel.removeFavorite(sceneIdentifier, "scene") // Assuming "scene" as itemType
                Log.d(TAG, "toggleFavorite: Attempted to remove '$sceneIdentifier' from Firestore.")
            }
            showMaterialToast("'${currentScene.title}' removed from favorites", false)
        } else {
            // It IS NOT a favorite, so ADD it
            favorites.add(sceneIdentifier)
            Log.d(TAG, "toggleFavorite: Added '$sceneIdentifier' to local favorites. Current favorites: $favorites")
            lifecycleScope.launch {
                favoritesViewModel.addFavorite(sceneIdentifier, "scene") // Assuming "scene" as itemType
                Log.d(TAG, "toggleFavorite: Attempted to add '$sceneIdentifier' to Firestore.")
            }
            showMaterialToast("'${currentScene.title}' added to favorites", true)
        }
        saveFavoritesToPrefs() // Keep SharedPreferences for local/quick access if desired
        updateFavoriteIcon()
        updateFavoritesList() // This updates the local adapter list
    }

    private fun removeFromFavorites(scene: Scene) {
        val sceneIdentifier = if (scene.firestoreId.isNotBlank()) {
            scene.firestoreId
        } else {
            "asset_${scene.id}"
        }
        if (favorites.contains(sceneIdentifier)) {
            favorites.remove(sceneIdentifier)
            saveFavoritesToPrefs() // Keep SharedPreferences for local/quick access

            // Remove from Firestore
            lifecycleScope.launch {
                favoritesViewModel.removeFavorite(sceneIdentifier, "scene") // Assuming "scene" as itemType
                Log.d(TAG, "removeFromFavorites: Attempted to remove '$sceneIdentifier' from Firestore.")
            }

            showMaterialToast("'${scene.title}' removed from favorites", false)
            updateFavoritesList() // This updates the local adapter list
            // Check if the scene being removed is the one currently displayed to update its favorite icon
            val currentDisplayedScene = getCurrentScene()
            val currentDisplayedSceneIdentifier = currentDisplayedScene?.let {
                if (it.firestoreId.isNotBlank()) it.firestoreId else "asset_${it.id}"
            }
            if (currentDisplayedSceneIdentifier == sceneIdentifier) {
                updateFavoriteIcon()
            }
        }
    }

    private fun showMaterialToast(message: String, isAddedToFavorite: Boolean) {
        currentToast?.cancel()
        val iconText = if (isAddedToFavorite) "❤️ " else "" // Remove heartbreak emoji, keep heart for favorite
        currentToast = Toast.makeText(applicationContext, iconText + message, Toast.LENGTH_SHORT).apply {
            show()
        }
    }

    private fun updateFavoriteIcon(menu: Menu? = null) {
        val m = menu ?: topAppBar.menu
        m.findItem(R.id.action_favorite)?.let { item ->
            val current = getCurrentScene()
            if (current != null && currentMode == MODE_RANDOM) {
                item.isVisible = true
                val sceneIdentifier = if (current.firestoreId.isNotBlank()) {
                    current.firestoreId
                } else {
                    "asset_${current.id}"
                }
                if (favorites.contains(sceneIdentifier)) {
                    item.icon = ContextCompat.getDrawable(this, R.drawable.ic_favorite_filled)
                    item.title = "Remove from Favorites" 
                } else {
                    item.icon = ContextCompat.getDrawable(this, R.drawable.ic_favorite) // Fallback
                    item.title = "Add to Favorites" 
                }
            } else {
                item.isVisible = false
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
        currentSceneIndex = displayedScenes.indexOf(scene).takeIf { it != -1 } ?: -1
        displayScene(scene) 
        if (currentSceneIndex == -1) Log.w(TAG, "Switched to scene not in current displayedScenes filter.")

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

        if (plannedItems.any { it.name == sceneToAdd.title && it.type == "Scene" }) { // Corrected: PlannedItem uses 'name'
            showMaterialToast("'${sceneToAdd.title}' is already in your plan.", false); return
        }
        plannedItems.add(PlannedItem(
            name = sceneToAdd.title, // Corrected: use 'name'
            type = "Scene",
            details = sceneToAdd.content.take(100) + if (sceneToAdd.content.length > 100) "..." else "",
            order = plannedItems.size
            // sceneFirebaseId is not in PlannedItem, so it's removed
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
            .setTitle(if (scene == null) "New Scene" else "Edit Scene") 
            .setView(dialogView)
            .setPositiveButton(if (scene == null) "Add" else "Save", null) 
            .setNegativeButton("Cancel", null) 
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
                            isCustom = true, // Always mark edited scenes as custom
                            userId = scene.userId.ifBlank { currentUid }
                            // scene.id (original Int ID) is preserved by .copy()
                        )

                        // If firestoreId is blank, it could be a default scene (id != 0) being edited locally (ViewModel will assign local_ ID),
                        // or an error for a custom scene (id == 0) that lost its ID.
                        if (updatedScene.firestoreId.isBlank() && updatedScene.id == 0) {
                            // This is an error: a non-default (custom) scene has a blank firestoreId.
                            showMaterialToast("Error: Scene ID missing for custom scene.", false)
                        } else {
                            // Proceed with update.
                            // If it's a default scene (updatedScene.id != 0 && updatedScene.firestoreId.isBlank()),
                            // the ViewModel is responsible for assigning a "local_" prefixed firestoreId.
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
            .setTitle("Delete Scene") 
            .setMessage("Are you sure you want to delete \"${scene.title}\"?") 
            .setNegativeButton("Cancel", null) 
            .setPositiveButton("Delete") { _, _ ->
                // ViewModel's deleteScene now takes the whole Scene object
                scenesViewModel.deleteScene(scene)

                // Remove from favorites if it was there.
                // Use a consistent identifier: firestoreId if present, otherwise original id for defaults.
                val identifierForFavorites: String = if (scene.firestoreId.isNotBlank()) {
                    scene.firestoreId
                } else if (scene.id != 0) {
                    "default_${scene.id}" // Create a consistent string key for default scenes based on original ID
                } else {
                    scene.title // Fallback, less reliable
                }
                if (favorites.contains(identifierForFavorites)) {
                    favorites.remove(identifierForFavorites)
                    saveFavoritesToPrefs()
                    Log.d(TAG, "Removed '${scene.title}' (ID: $identifierForFavorites) from favorites after deletion.")
                }
                // ViewModel will handle specific error/success logging. MainActivity can show a generic attempt message.
                showMaterialToast("Attempting to delete \"${scene.title}\".", false)
            }
            .show()
    }

    private fun showResetConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset Default Scenes")
            .setMessage("Are you sure you want to reset all default scenes to their original content? This will revert any edits you've made to them and re-add any default scenes you may have deleted. Custom scenes you created will not be affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                if (auth.currentUser != null) {
                    scenesViewModel.resetDefaultScenesForCurrentUser()
                    showMaterialToast("Resetting default scenes for your account...", false)
                } else {
                    // User is logged out, reset local scenes
                    scenesViewModel.resetLocalScenesToDefault()
                    showMaterialToast("Resetting local scenes to default...", false)
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
                val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)!!
                Log.d(TAG, "Google Sign-In successful, token: ${account.idToken?.take(10)}...")
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                Log.w(TAG, "Google sign in failed", e)
                Toast.makeText(this, "Google Sign-In failed: ${e.statusCode}", Toast.LENGTH_LONG).show()
                updateUIForSignedOutUser()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase Auth successful. User: ${auth.currentUser?.email}")
                    Toast.makeText(this, "Firebase Authentication successful.", Toast.LENGTH_SHORT).show()
                    updateUIForCurrentUser()
                } else {
                    Log.w(TAG, "Firebase Auth failed.", task.exception)
                    Toast.makeText(this, "Firebase Authentication failed.", Toast.LENGTH_SHORT).show()
                    updateUIForSignedOutUser()
                }
            }
    }

    private fun updateUIForCurrentUser() {
        invalidateOptionsMenu() 
    }

    private fun updateUIForSignedOutUser() {
        allUserScenes = emptyList(); displayedScenes = emptyList(); sceneHistory.clear()
        historyPosition = -1; currentSceneIndex = -1
        filterScenes(null) 
        clearSceneDisplay()
        invalidateOptionsMenu()
        Toast.makeText(this, "You are signed out.", Toast.LENGTH_LONG).show() 
    }

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

            // Collapse search view if it was open
            if (searchItem?.isActionViewExpanded == true) {
                searchItem.collapseActionView()
            }
        } else if (currentMode == MODE_EDIT) {
            searchItem?.isVisible = true
            favoriteItem?.isVisible = false
            addToPlanToolbarItem?.isVisible = false
            addToPlanOverflowItem?.isVisible = false // Hide in Edit mode
            settingsItem?.isVisible = false         // Hide in Edit mode
            signInSignOutItem?.isVisible = false    // Hide in Edit mode
        } else { // Default or other modes (e.g. if MODE_FAVORITES was active via bottom nav)
            // Default visibility for any other modes, or if no specific mode is matched above
            searchItem?.isVisible = true
            favoriteItem?.isVisible = false
            addToPlanToolbarItem?.isVisible = false
            addToPlanOverflowItem?.isVisible = true
            settingsItem?.isVisible = true
            signInSignOutItem?.isVisible = true
        }
        
        signInSignOutItem?.title = if (auth.currentUser == null) "Sign In" else "Sign Out"
        updateFavoriteIcon(menu) // This already handles visibility based on mode for favorite icon

        // Programmatically tint icons to respect theme, using colorOnSurface like in PositionsActivity
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
        val iconColor = typedValue.data

        try {
            searchItem?.icon?.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            addToPlanToolbarItem?.icon?.setColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying color filter to menu icons", e)
        }
        // Favorite icon is handled by updateFavoriteIcon.

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
                R.id.action_add_to_plan_toolbar -> {
                    // Attempt to animate the toolbar icon view
                    // Note: findViewById on a menu item ID might not always work reliably to get its View.
                    // A more robust method might involve custom action layouts or iterating through toolbar children.
                    val itemView = findViewById<View>(R.id.action_add_to_plan_toolbar)
                    itemView?.animate()
                        ?.scaleX(1.2f)?.scaleY(1.2f)?.setDuration(150)?.withEndAction {
                            itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                        }?.start()
                    addCurrentSceneToPlan()
                    true
                }
                R.id.action_add_to_plan_overflow -> {
                    addCurrentSceneToPlan()
                    true
                }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                R.id.action_sign_in_out -> {
                    if (auth.currentUser == null) signIn()
                    else MaterialAlertDialogBuilder(this)
                            .setTitle("Sign Out")
                            .setMessage("Are you sure you want to sign out?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Sign Out") { _, _ ->
                                auth.signOut(); googleSignInClient.signOut()
                                updateUIForSignedOutUser()
                                Toast.makeText(this, "Signed out.", Toast.LENGTH_SHORT).show()
                            }.show()
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onOptionsItemSelected for item ID ${item.itemId}: ${e.message}")
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