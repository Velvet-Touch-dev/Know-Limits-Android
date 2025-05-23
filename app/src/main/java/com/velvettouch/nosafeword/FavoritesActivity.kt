package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log // Added Log import
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import com.velvettouch.nosafeword.BaseActivity // Keep BaseActivity if it's your custom base
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine // Added combine import
import kotlinx.coroutines.launch
import java.util.Locale
import java.io.File
import java.io.IOException
import org.json.JSONArray

class FavoritesActivity : BaseActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var tabLayout: TabLayout
    private lateinit var sceneFavoritesRecyclerView: RecyclerView
    private lateinit var positionFavoritesRecyclerView: RecyclerView
    private lateinit var emptyFavoritesView: View

    // Renaming favoritesViewModel to cloudFavoritesViewModel for clarity in logic,
    // but keeping original name for by viewModels() to avoid breaking existing ViewModel creation.
    private val cloudFavoritesViewModel: FavoritesViewModel by viewModels()
    private val localFavoritesViewModel: LocalFavoritesViewModel by viewModels()
    private val scenesViewModel: ScenesViewModel by viewModels()

    // private var allScenes: List<Scene> = emptyList()
    private var positions: MutableList<Position> = mutableListOf()

    private lateinit var sceneFavoritesAdapter: FavoriteScenesAdapter
    private lateinit var positionFavoritesAdapter: FavoritePositionsAdapter

    private lateinit var auth: FirebaseAuth
    // private var localSceneFavoriteIds: MutableSet<String> = mutableSetOf() // Now managed by LocalFavoritesViewModel
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    companion object {
        private const val SCENES_FILENAME = "scenes.json" // Keep if still used for positions or other logic
        // private const val FAVORITES_PREFS_NAME = "FavoritesPrefs" // No longer directly used here
        // private const val FAVORITE_SCENE_IDS_KEY = "favoriteSceneIds" // No longer directly used here
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        auth = FirebaseAuth.getInstance()
        // setupAuthStateListener() will be called in onStart to ensure ViewModels are ready

        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        tabLayout = findViewById(R.id.tabs)
        sceneFavoritesRecyclerView = findViewById(R.id.scene_favorites_recycler_view)
        positionFavoritesRecyclerView = findViewById(R.id.position_favorites_recycler_view)
        emptyFavoritesView = findViewById(R.id.empty_favorites_view)

        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.favorites)

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

        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_scenes -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_positions -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PositionsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_body_worship -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, BodyWorshipActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_task_list -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, TaskListActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_plan_night -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PlanNightActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish()
                    true
                }
                R.id.nav_favorites -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_settings -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    true
                }
                else -> false
            }
        }

        navigationView.setCheckedItem(R.id.nav_favorites)
        if (navigationView.headerCount > 0) {
            navigationView.removeHeaderView(navigationView.getHeaderView(0))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> { // Scenes tab
                        sceneFavoritesRecyclerView.visibility = View.VISIBLE
                        positionFavoritesRecyclerView.visibility = View.GONE
                        // This logic will be handled by updateCurrentTabContent based on auth state
                        updateCurrentTabContent()
                    }
                    1 -> { // Positions tab
                        // This logic will be handled by updateCurrentTabContent based on auth state
                        updateCurrentTabContent()
                    }
                }
                // updateCurrentTabContent already handles empty state updates.
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        sceneFavoritesAdapter = FavoriteScenesAdapter({ scene -> // onItemClick
            val intent = Intent(this, MainActivity::class.java)
            val sceneIdToDisplay = getSceneIdentifier(scene)
            Log.d("FavoritesActivity", "Navigating to scene. ID being passed: $sceneIdToDisplay (Title: ${scene.title})")
            intent.putExtra("DISPLAY_SCENE_ID", sceneIdToDisplay)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }, { scene -> // onRemove
            if (auth.currentUser != null) {
                cloudFavoritesViewModel.removeCloudFavorite(getSceneIdentifier(scene), "scene")
            } else {
                localFavoritesViewModel.removeLocalFavorite(getSceneIdentifier(scene), scenesViewModel.scenes.value)
            }
        })

        sceneFavoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = sceneFavoritesAdapter
            setupSceneSwipeToDelete(this)
        }

        positionFavoritesAdapter = FavoritePositionsAdapter({ position ->
            val intent = Intent(this, PositionsActivity::class.java)
            intent.putExtra("DISPLAY_POSITION_NAME", position.name)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }, { position ->
            // Positions are assumed to be cloud-only for now.
            // If local position favorites were a thing, similar logic to scenes would apply.
            cloudFavoritesViewModel.removeCloudFavorite(position.name, "position")
        })

        positionFavoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = positionFavoritesAdapter
            setupPositionSwipeToDelete(this)
        }

        findViewById<androidx.cardview.widget.CardView>(R.id.swipe_hint_container).visibility = View.VISIBLE

        // Load local positions data (scenes will be loaded from ScenesViewModel)
        loadPositions()

        // loadLocalSceneFavoritesFromPrefs() // Removed, handled by LocalFavoritesViewModel

        observeViewModels()
        // Initial load will be triggered by AuthStateListener in onStart

        // scenesViewModel loads its scenes internally via its init block or other triggers.
        // Ensure scenesViewModel is ready before localFavoritesViewModel tries to use its scenes.
        // This might require observing scenesViewModel.scenes first in localFavoritesViewModel's load.

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

    override fun onStart() {
        super.onStart()
        // Initialize AuthStateListener here, after ViewModels are available.
        setupAuthStateListener()
        authStateListener?.let { auth.addAuthStateListener(it) }
        // Trigger initial check in case user is already logged in/out when activity starts
        handleAuthStateChange(auth.currentUser)
    }

    override fun onStop() {
        super.onStop()
        authStateListener?.let { auth.removeAuthStateListener(it) }
    }

    private fun setupAuthStateListener() {
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            handleAuthStateChange(firebaseAuth.currentUser)
        }
    }

    private fun handleAuthStateChange(user: com.google.firebase.auth.FirebaseUser?) {
        Log.d("FavoritesActivity", "AuthStateListener: User changed. Current user: ${user?.uid}")
        if (user == null) {
            // User is signed out
            Log.d("FavoritesActivity", "AuthStateListener: User signed out. Loading local favorites.")
            // Ensure scenes are loaded before trying to map local favorite IDs to Scene objects
            lifecycleScope.launch {
                scenesViewModel.scenes.collectLatest { allScenes ->
                    if (auth.currentUser == null) { // Double check, as this is a collecting flow
                         localFavoritesViewModel.refreshLocalFavorites(allScenes) // Use refresh to re-evaluate with current allScenes
                    }
                }
            }
             // Initial load for local favorites if scenes are already present
            if (scenesViewModel.scenes.value.isNotEmpty()) {
                 localFavoritesViewModel.refreshLocalFavorites(scenesViewModel.scenes.value)
            }


        } else {
            // User is signed in
            Log.d("FavoritesActivity", "AuthStateListener: User signed in. Triggering merge and loading cloud favorites.")
            // The cloudFavoritesViewModel will handle the merge internally if performMerge is true
            cloudFavoritesViewModel.loadCloudFavorites(performMerge = true)
        }
        updateCurrentTabContent() // Refresh UI based on new auth state
    }


    private fun updateCurrentTabContent() {
        val selectedTabPosition = tabLayout.selectedTabPosition
        // Ensure a tab is selected, default to 0 if not
        val currentPosition = if (selectedTabPosition == -1 && tabLayout.tabCount > 0) 0 else selectedTabPosition

        if (currentPosition == -1) {
            Log.d("FavoritesActivity", "updateCurrentTabContent: No tab selected and no tabs available.")
            emptyFavoritesView.visibility = View.VISIBLE // Show empty state if no tabs
            sceneFavoritesRecyclerView.visibility = View.GONE
            positionFavoritesRecyclerView.visibility = View.GONE
            return
        }
        
        // Ensure the correct tab is visually selected if we defaulted
        if (selectedTabPosition == -1 && tabLayout.tabCount > 0) {
            tabLayout.getTabAt(0)?.select()
        }


        if (auth.currentUser == null) { // Logged Out
            Log.d("FavoritesActivity", "updateCurrentTabContent: User Logged Out.")
            val localFavScenes = localFavoritesViewModel.localFavoriteScenes.value
            if (currentPosition == 0) { // Scenes tab
                sceneFavoritesRecyclerView.visibility = View.VISIBLE
                positionFavoritesRecyclerView.visibility = View.GONE
                displayLocalSceneFavorites(localFavScenes)
            } else { // Positions tab (assuming empty for local)
                sceneFavoritesRecyclerView.visibility = View.GONE
                positionFavoritesRecyclerView.visibility = View.VISIBLE
                positionFavoritesAdapter.submitList(emptyList()) // Positions are not local
            }
            updateOverallEmptyStateForLocal(localFavScenes, emptyList())
        } else { // Logged In
            Log.d("FavoritesActivity", "updateCurrentTabContent: User Logged In.")
            val cloudFavs = cloudFavoritesViewModel.favorites.value
            val allAppScenes = scenesViewModel.scenes.value // All scenes from app assets/repository

            if (currentPosition == 0) { // Scenes tab
                sceneFavoritesRecyclerView.visibility = View.VISIBLE
                positionFavoritesRecyclerView.visibility = View.GONE
                updateSceneFavoritesListFromFirestore(cloudFavs, allAppScenes)
            } else { // Positions tab
                sceneFavoritesRecyclerView.visibility = View.GONE
                positionFavoritesRecyclerView.visibility = View.VISIBLE
                updatePositionFavoritesListFromFirestore(cloudFavs)
            }
            updateOverallEmptyStateForFirestore(cloudFavs, allAppScenes)
        }
    }


    private fun observeViewModels() {
        // Observe Cloud Favorites (for logged-in state)
        lifecycleScope.launch {
            cloudFavoritesViewModel.favorites.collectLatest { cloudFavoriteList ->
                if (auth.currentUser != null) {
                    Log.d("FavoritesActivity", "Cloud favorites updated. Count: ${cloudFavoriteList.size}")
                    updateCurrentTabContent() // Re-render based on new cloud data
                }
            }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.isLoading.collectLatest { isLoading ->
                Log.d("FavoritesActivity", "Cloud isLoading: $isLoading")
                // TODO: Show/hide global loading indicator for cloud operations
            }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.isMerging.collectLatest { isMerging ->
                Log.d("FavoritesActivity", "Cloud isMerging: $isMerging")
                // TODO: Show/hide specific merging indicator
            }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.error.collectLatest { errorMessage ->
                errorMessage?.let {
                    if (auth.currentUser != null || !it.contains("User not logged in", ignoreCase = true)) {
                        Toast.makeText(this@FavoritesActivity, "Cloud Favorites Error: $it", Toast.LENGTH_LONG).show()
                        Log.e("FavoritesActivity", "Cloud Favorites Error: $it")
                    }
                    cloudFavoritesViewModel.clearError()
                }
            }
        }

        // Observe Local Favorites (for logged-out state)
        lifecycleScope.launch {
            localFavoritesViewModel.localFavoriteScenes.collectLatest { localFavoriteList ->
                if (auth.currentUser == null) {
                    Log.d("FavoritesActivity", "Local favorites updated. Count: ${localFavoriteList.size}")
                    updateCurrentTabContent() // Re-render based on new local data
                }
            }
        }
         lifecycleScope.launch {
            localFavoritesViewModel.isLoading.collectLatest { isLoading ->
                 if (auth.currentUser == null) { // Only show for local context
                    Log.d("FavoritesActivity", "Local isLoading: $isLoading")
                    // TODO: Show/hide global loading indicator for local operations (if any become async)
                 }
            }
        }
        lifecycleScope.launch {
            localFavoritesViewModel.error.collectLatest { errorMessage ->
                errorMessage?.let {
                    if (auth.currentUser == null) { // Only show for local context
                        Toast.makeText(this@FavoritesActivity, "Local Favorites Error: $it", Toast.LENGTH_LONG).show()
                        Log.e("FavoritesActivity", "Local Favorites Error: $it")
                    }
                    localFavoritesViewModel.clearError()
                }
            }
        }


        // Observe All Scenes (needed by both local and cloud to map IDs to Scene objects)
        lifecycleScope.launch {
            scenesViewModel.scenes.collectLatest { allScenes ->
                Log.d("FavoritesActivity", "All scenes updated. Count: ${allScenes.size}")
                // When all scenes change, we might need to refresh both local and cloud views
                // as they depend on this list to map IDs to full Scene objects.
                if (auth.currentUser == null) {
                    localFavoritesViewModel.refreshLocalFavorites(allScenes)
                } else {
                    // Cloud favorites are collected directly, but the mapping to Scene objects happens here.
                    // So, a refresh of the current tab content is good.
                    updateCurrentTabContent()
                }
            }
        }
        lifecycleScope.launch {
            scenesViewModel.isLoading.collectLatest { isLoading ->
                Log.d("FavoritesActivity", "Scenes isLoading: $isLoading")
                // TODO: Handle scenes loading indicator
            }
        }
        lifecycleScope.launch {
            scenesViewModel.error.collectLatest { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@FavoritesActivity, "All Scenes Error: $it", Toast.LENGTH_LONG).show()
                    Log.e("FavoritesActivity", "All Scenes Error: $it")
                    // scenesViewModel.clearError() // If ScenesViewModel has this
                }
            }
        }
    }

    private fun getSceneIdentifier(scene: Scene): String {
        return if (scene.firestoreId.isNotBlank()) {
            scene.firestoreId
        } else {
            "asset_${scene.id}" // Consistent with LocalFavoritesViewModel
        }
    }

    // Removed loadLocalSceneFavoritesFromPrefs - handled by LocalFavoritesRepository/ViewModel
    // Removed saveLocalSceneFavoritesToPrefs - handled by LocalFavoritesRepository/ViewModel


    private fun displayLocalSceneFavorites(localFavoriteScenes: List<Scene>) {
        Log.d("FavoritesActivity", "displayLocalSceneFavorites - Count: ${localFavoriteScenes.size}")
        sceneFavoritesAdapter.submitList(localFavoriteScenes.toList())
        if (tabLayout.selectedTabPosition == 0) {
            sceneFavoritesRecyclerView.visibility = if (localFavoriteScenes.isEmpty()) View.GONE else View.VISIBLE
        }
        // Positions are empty when logged out in this view
        updateOverallEmptyStateForLocal(localFavoriteScenes, emptyList())
    }

    // Renamed for clarity: for logged-IN state, using Firestore data
    private fun updateSceneFavoritesListFromFirestore(firestoreFavorites: List<Favorite>, currentAllScenes: List<Scene>) {
        Log.d("FavoritesActivity", "updateSceneFavoritesListFromFirestore - firestoreFavorites.size: ${firestoreFavorites.size}, currentAllScenes.size: ${currentAllScenes.size}")
        val favoriteSceneIds = firestoreFavorites.filter { it.itemType == "scene" }.map { it.itemId }.toSet()
        val favoriteSceneObjects = currentAllScenes.filter { scene -> favoriteSceneIds.contains(getSceneIdentifier(scene)) }
        sceneFavoritesAdapter.submitList(favoriteSceneObjects.toList())

        if (tabLayout.selectedTabPosition == 0) {
            sceneFavoritesRecyclerView.visibility = if (favoriteSceneObjects.isEmpty()) View.GONE else View.VISIBLE
        }
        updateOverallEmptyStateForFirestore(firestoreFavorites, currentAllScenes)
    }

    // Renamed for clarity: for logged-IN state, using Firestore data
    private fun updatePositionFavoritesListFromFirestore(firestoreFavorites: List<Favorite>) {
        Log.d("FavoritesActivity", "updatePositionFavoritesListFromFirestore - firestoreFavorites.size: ${firestoreFavorites.size}")
        val favoritePositionNames = firestoreFavorites.filter { it.itemType == "position" }.map { it.itemId }.toSet()
        val favoritePositionObjects = positions.filter { position -> favoritePositionNames.contains(position.name) }
        positionFavoritesAdapter.submitList(favoritePositionObjects.toList())

        if (tabLayout.selectedTabPosition == 1) {
            positionFavoritesRecyclerView.visibility = if (favoritePositionObjects.isEmpty()) View.GONE else View.VISIBLE
        }
        updateOverallEmptyStateForFirestore(firestoreFavorites, scenesViewModel.scenes.value)
    }

    // For Logged-OUT state
    private fun updateOverallEmptyStateForLocal(localFavoriteScenes: List<Scene>, localFavoritePositions: List<Position>) {
        Log.d("FavoritesActivity", "updateOverallEmptyStateForLocal - localFavoriteScenes.size: ${localFavoriteScenes.size}, localFavoritePositions.size: ${localFavoritePositions.size}")
        val noSceneFavorites = localFavoriteScenes.isEmpty()
        val noPositionFavorites = localFavoritePositions.isEmpty() // Will always be true for logged out in this activity

        if (noSceneFavorites && noPositionFavorites) {
            emptyFavoritesView.visibility = View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
            positionFavoritesRecyclerView.visibility = View.GONE
        } else {
            emptyFavoritesView.visibility = View.GONE
            if (tabLayout.selectedTabPosition == 0) { // Scenes tab
                sceneFavoritesRecyclerView.visibility = if (noSceneFavorites) View.GONE else View.VISIBLE
                positionFavoritesRecyclerView.visibility = View.GONE
            } else { // Positions tab
                positionFavoritesRecyclerView.visibility = if (noPositionFavorites) View.GONE else View.VISIBLE
                sceneFavoritesRecyclerView.visibility = View.GONE
            }
        }
    }

    // For Logged-IN state (previously updateOverallEmptyState)
    private fun updateOverallEmptyStateForFirestore(firestoreFavorites: List<Favorite>, currentAllScenes: List<Scene>) {
        Log.d("FavoritesActivity", "updateOverallEmptyStateForFirestore - firestoreFavorites.size: ${firestoreFavorites.size}, currentAllScenes.size: ${currentAllScenes.size}")
        val sceneFavoriteItemIds = firestoreFavorites.filter { it.itemType == "scene" }.map { it.itemId }.toSet()
        val actualFavoriteScenes = currentAllScenes.filter { scene -> sceneFavoriteItemIds.contains(getSceneIdentifier(scene)) }

        val positionFavoriteItemIds = firestoreFavorites.filter { it.itemType == "position" }.map { it.itemId }.toSet()
        val actualFavoritePositions = positions.filter { pos -> positionFavoriteItemIds.contains(pos.name) }

        val noSceneFavorites = actualFavoriteScenes.isEmpty()
        val noPositionFavorites = actualFavoritePositions.isEmpty()

        if (noSceneFavorites && noPositionFavorites) {
            emptyFavoritesView.visibility = View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
            positionFavoritesRecyclerView.visibility = View.GONE
        } else {
            emptyFavoritesView.visibility = View.GONE
            if (tabLayout.selectedTabPosition == 0) { // Scenes tab
                sceneFavoritesRecyclerView.visibility = if (noSceneFavorites) View.GONE else View.VISIBLE
                positionFavoritesRecyclerView.visibility = View.GONE
            } else { // Positions tab
                positionFavoritesRecyclerView.visibility = if (noPositionFavorites) View.GONE else View.VISIBLE
                sceneFavoritesRecyclerView.visibility = View.GONE
            }
        }
    }

    private fun loadPositions() {
        val loadedPositions = mutableListOf<Position>()
        try {
            val assetManager = assets
            val assetFiles = assetManager.list("positions")
            assetFiles?.forEach { fileName ->
                if (fileName.endsWith(".webp", ignoreCase = true) || fileName.endsWith(".png", ignoreCase = true) || fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true)) {
                    val nameWithoutExtension = fileName.substringBeforeLast(".")
                    val displayName = nameWithoutExtension.replace("_", " ").split(" ").joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                    loadedPositions.add(Position(displayName, "positions/$fileName", true))
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val customPositionsDir = getExternalFilesDir("positions")
        if (customPositionsDir != null && customPositionsDir.exists()) {
            customPositionsDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true))) {
                    val parts = file.nameWithoutExtension.split("_")
                    val displayName = if (parts.size > 2 && parts.first() == "position" && parts.last().toLongOrNull() != null) {
                        parts.drop(1).dropLast(1).joinToString(" ").split(" ").joinToString(" ") { word ->
                           word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        }
                    } else {
                        file.nameWithoutExtension.replace("_", " ").split(" ").joinToString(" ") { word ->
                            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        }
                    }
                    loadedPositions.add(Position(displayName, file.absolutePath, false))
                }
            }
        }
        positions = loadedPositions
    }

    private fun loadJSONFromAsset(fileName: String): String {
        val inputStream = assets.open(fileName)
        val bufferedReader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()
        var line: String?
        
        while (bufferedReader.readLine().also { line = it } != null) {
            stringBuilder.append(line)
        }
        
        bufferedReader.close()
        return stringBuilder.toString()
    }
    
    // Helper function to capitalize first letter of each word
    private fun String.capitalize(): String {
        return this.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase() else it.toString() 
            }
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred
        drawerToggle.syncState()
    }
    
    // Removed deprecated override fun onBackPressed()

    // Data class for Position items
    data class Position(
        val name: String,
        val imagePath: String,
        val isAsset: Boolean // Added to distinguish asset from file path
    )

    /**
     * Sets up swipe-to-delete functionality for the scene favorites recycler view
     */
    private fun setupSceneSwipeToDelete(recyclerView: RecyclerView) {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(
            0, // No drag and drop
            ItemTouchHelper.RIGHT // Only swipe right to delete
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false // We don't support moving items in this list
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                sceneFavoritesAdapter.removeItem(position)
            }

            // Add visual feedback when swiping
            override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
            ) {
            val itemView = viewHolder.itemView
            val background = ColorDrawable(Color.parseColor("#F44336")) // Red background
            val deleteIcon = ContextCompat.getDrawable(
            this@FavoritesActivity,
            R.drawable.ic_delete
            )?.apply {
            setTint(Color.WHITE)
            }
                    
            // Set the same corner radius as the card
            val cornerRadius = 16f * resources.displayMetrics.density
            
                    val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
            val iconTop = itemView.top + (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
            val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)

            // Show icon based on swipe direction
            when {
            dX > 0 -> { // Swiping to the right
                            val iconLeft = itemView.left + iconMargin
            val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?: 0)
            deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)

            // Draw background with rounded corners
            val backgroundPath = android.graphics.Path()
                backgroundPath.addRoundRect(
                    android.graphics.RectF(
                    itemView.left.toFloat(),
                    itemView.top.toFloat(),
                    itemView.left + dX,
                                    itemView.bottom.toFloat()
                ),
                cornerRadius, cornerRadius,
            android.graphics.Path.Direction.CW
            )
            
                // Save canvas state to apply clipping
                c.save()
                    c.clipPath(backgroundPath)
                            background.setBounds(
                        itemView.left, itemView.top,
                        itemView.left + dX.toInt(), itemView.bottom
                    )
                            background.draw(c)
                    c.restore()
                }
            dX < 0 -> { // Swiping to the left
                val iconRight = itemView.right - iconMargin
                val iconLeft = iconRight - (deleteIcon?.intrinsicWidth ?: 0)
                    deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                // Draw background with rounded corners
                    val backgroundPath = android.graphics.Path()
                            backgroundPath.addRoundRect(
                        android.graphics.RectF(
                                itemView.right + dX,
                                    itemView.top.toFloat(),
                                    itemView.right.toFloat(),
                                    itemView.bottom.toFloat()
                                ),
                                cornerRadius, cornerRadius,
                                android.graphics.Path.Direction.CW
                            )
                            
                            // Save canvas state to apply clipping
                            c.save()
                            c.clipPath(backgroundPath)
                            background.setBounds(
                                itemView.right + dX.toInt(), itemView.top,
                                itemView.right, itemView.bottom
                            )
                            background.draw(c)
                            c.restore()
                        }
                        else -> {}
                    }

                    // Draw the icon
                    deleteIcon?.draw(c)

                    // Add a subtle scale effect when swiping
                    if (isCurrentlyActive) {
                        val scaleFactor = 0.95f + (1 - Math.min(1f, Math.abs(dX) / (itemView.width / 3f))) * 0.05f
                        itemView.scaleX = scaleFactor
                        // Don't change Y scale to avoid wobbling
                        // Don't add rotation to avoid wobbling
                    } else {
                        itemView.scaleX = 1.0f
                        itemView.scaleY = 1.0f
                        itemView.rotation = 0f
                    }

                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
        }

        // Attach the swipe handler to the RecyclerView
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }
    
    /**
     * Sets up swipe-to-delete functionality for the position favorites recycler view
     */
    private fun setupPositionSwipeToDelete(recyclerView: RecyclerView) {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(
            0, // No drag and drop
            ItemTouchHelper.RIGHT // Only swipe right to delete
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false // We don't support moving items in this list
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                positionFavoritesAdapter.removeItem(position)
            }

            // Add visual feedback when swiping
            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val background = ColorDrawable(Color.parseColor("#F44336")) // Red background
                val deleteIcon = ContextCompat.getDrawable(
                    this@FavoritesActivity,
                    R.drawable.ic_delete
                )?.apply {
                    setTint(Color.WHITE)
                }
                    
                // Set the same corner radius as the card
                val cornerRadius = 16f * resources.displayMetrics.density
                
                val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                val iconTop = itemView.top + (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)

                // Show icon based on swipe direction
                when {
                    dX > 0 -> { // Swiping to the right
                        val iconLeft = itemView.left + iconMargin
                        val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?: 0)
                        deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                        // Draw background with rounded corners
                        val backgroundPath = android.graphics.Path()
                        backgroundPath.addRoundRect(
                            android.graphics.RectF(
                                itemView.left.toFloat(),
                                itemView.top.toFloat(),
                                itemView.left + dX,
                                itemView.bottom.toFloat()
                            ),
                            cornerRadius, cornerRadius,
                            android.graphics.Path.Direction.CW
                        )
                        
                        // Save canvas state to apply clipping
                        c.save()
                        c.clipPath(backgroundPath)
                        background.setBounds(
                            itemView.left, itemView.top,
                            itemView.left + dX.toInt(), itemView.bottom
                        )
                        background.draw(c)
                        c.restore()
                    }
                    dX < 0 -> { // Swiping to the left
                        val iconRight = itemView.right - iconMargin
                        val iconLeft = iconRight - (deleteIcon?.intrinsicWidth ?: 0)
                        deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                        // Draw background with rounded corners
                        val backgroundPath = android.graphics.Path()
                        backgroundPath.addRoundRect(
                            android.graphics.RectF(
                                itemView.right + dX,
                                itemView.top.toFloat(),
                                itemView.right.toFloat(),
                                itemView.bottom.toFloat()
                            ),
                            cornerRadius, cornerRadius,
                            android.graphics.Path.Direction.CW
                        )
                        
                        // Save canvas state to apply clipping
                        c.save()
                        c.clipPath(backgroundPath)
                        background.setBounds(
                            itemView.right + dX.toInt(), itemView.top,
                            itemView.right, itemView.bottom
                        )
                        background.draw(c)
                        c.restore()
                    }
                    else -> {}
                }

                // Draw the icon
                deleteIcon?.draw(c)

                    // Add a subtle scale effect when swiping
                    if (isCurrentlyActive) {
                        val scaleFactor = 0.95f + (1 - Math.min(1f, Math.abs(dX) / (itemView.width / 3f))) * 0.05f
                        itemView.scaleX = scaleFactor
                        // Don't change Y scale to avoid wobbling
                        // Don't add rotation to avoid wobbling
                    } else {
                        itemView.scaleX = 1.0f
                        itemView.scaleY = 1.0f
                        itemView.rotation = 0f
                    }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        // Attach the swipe handler to the RecyclerView
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }
}