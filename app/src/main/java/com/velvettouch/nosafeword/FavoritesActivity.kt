package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import com.velvettouch.nosafeword.BaseActivity
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
import kotlinx.coroutines.launch
import java.util.Locale // Ensured import

import java.io.IOException
class FavoritesActivity : BaseActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var tabLayout: TabLayout
    private lateinit var sceneFavoritesRecyclerView: RecyclerView
    private lateinit var positionFavoritesRecyclerView: RecyclerView
    private lateinit var emptyFavoritesView: View

    private val cloudFavoritesViewModel: FavoritesViewModel by viewModels { FavoritesViewModelFactory(application) }
    private val localFavoritesViewModel: LocalFavoritesViewModel by viewModels()
    private val scenesViewModel: ScenesViewModel by viewModels()
    private lateinit var positionsRepository: PositionsRepository

    private lateinit var sceneFavoritesAdapter: FavoriteScenesAdapter
    private lateinit var positionFavoritesAdapter: FavoritePositionsAdapter

    private lateinit var auth: FirebaseAuth
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    companion object {
        // Constants if any
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

        auth = FirebaseAuth.getInstance()
        positionsRepository = PositionsRepository(applicationContext)

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
            this, drawerLayout, toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        drawerToggle.isDrawerIndicatorEnabled = true
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        navigationView.setNavigationItemSelectedListener { menuItem ->
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = when (menuItem.itemId) {
                R.id.nav_scenes -> Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_positions -> Intent(this, PositionsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_body_worship -> Intent(this, BodyWorshipActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_task_list -> Intent(this, TaskListActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_plan_night -> Intent(this, PlanNightActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                R.id.nav_favorites -> null // Already here
                R.id.nav_settings -> Intent(this, SettingsActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
                else -> null
            }
            intent?.let {
                startActivity(it)
                if (menuItem.itemId != R.id.nav_favorites) finish()
            }
            true
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
                    }
                    1 -> { // Positions tab
                        sceneFavoritesRecyclerView.visibility = View.GONE
                        positionFavoritesRecyclerView.visibility = View.VISIBLE
                    }
                }
                updateCurrentTabContent()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        sceneFavoritesAdapter = FavoriteScenesAdapter({ scene ->
            val intent = Intent(this, MainActivity::class.java)
            val sceneIdToDisplay = getSceneIdentifier(scene)
            intent.putExtra("DISPLAY_SCENE_ID", sceneIdToDisplay)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }, { scene ->
            if (auth.currentUser != null) {
                cloudFavoritesViewModel.removeCloudFavorite(getSceneIdentifier(scene), "scene")
            } else {
                localFavoritesViewModel.removeLocalFavoriteScene(getSceneIdentifier(scene), scenesViewModel.scenes.value)
            }
        })

        sceneFavoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = sceneFavoritesAdapter
            setupSceneSwipeToDelete(this)
        }

        positionFavoritesAdapter = FavoritePositionsAdapter({ positionItem ->
            val intent = Intent(this, PositionsActivity::class.java)
            intent.putExtra("DISPLAY_POSITION_NAME", positionItem.name)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }, { positionItem ->
            if (auth.currentUser != null) {
                cloudFavoritesViewModel.removeCloudFavorite(positionItem.id, "position")
            } else {
                localFavoritesViewModel.removeLocalFavoritePosition(positionItem.id)
            }
        })

        positionFavoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = positionFavoritesAdapter
            setupPositionSwipeToDelete(this)
        }

        findViewById<androidx.cardview.widget.CardView>(R.id.swipe_hint_container).visibility = View.VISIBLE
        observeViewModels()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false // Disable this callback
                    onBackPressedDispatcher.onBackPressed() // Delegate to default
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        setupAuthStateListener()
        authStateListener?.let { auth.addAuthStateListener(it) }
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
            Log.d("FavoritesActivity", "AuthStateListener: User signed out. Loading local favorites.")
            lifecycleScope.launch {
                scenesViewModel.scenes.collectLatest { allScenes ->
                    if (auth.currentUser == null) { // Double check auth state
                        localFavoritesViewModel.refreshLocalFavoriteScenes(allScenes)
                    }
                }
            }
            lifecycleScope.launch {
                if (auth.currentUser == null) { // Double check auth state
                    localFavoritesViewModel.refreshLocalFavoritePositions()
                }
            }
        } else {
            Log.d("FavoritesActivity", "AuthStateListener: User signed in. Triggering merge and loading cloud favorites.")
            cloudFavoritesViewModel.loadCloudFavorites(performMerge = true)
        }
        updateCurrentTabContent() // This needs to be called after state might have changed
    }

    private fun updateCurrentTabContent() {
        val selectedTabPosition = tabLayout.selectedTabPosition
        val currentTab = if (selectedTabPosition == -1 && tabLayout.tabCount > 0) 0 else selectedTabPosition

        if (currentTab == -1) {
            emptyFavoritesView.visibility = View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
            positionFavoritesRecyclerView.visibility = View.GONE
            return
        }
        if (selectedTabPosition == -1 && tabLayout.tabCount > 0) {
            tabLayout.getTabAt(0)?.select()
        }

        if (auth.currentUser == null) { // Logged Out
            Log.d("FavoritesActivity", "updateCurrentTabContent: User Logged Out.")
            val localFavScenes = localFavoritesViewModel.localFavoriteScenes.value
            val localFavPositions = localFavoritesViewModel.localFavoritePositions.value

            if (currentTab == 0) { // Scenes tab
                sceneFavoritesRecyclerView.visibility = View.VISIBLE
                positionFavoritesRecyclerView.visibility = View.GONE
                displayLocalSceneFavorites(localFavScenes)
            } else { // Positions tab
                sceneFavoritesRecyclerView.visibility = View.GONE
                positionFavoritesRecyclerView.visibility = View.VISIBLE
                positionFavoritesAdapter.submitList(localFavPositions.toList())
                Log.d("FavoritesActivity", "Displaying ${localFavPositions.size} local favorite positions.")
            }
            updateOverallEmptyStateForLocal(localFavScenes, localFavPositions)
        } else { // Logged In
            Log.d("FavoritesActivity", "updateCurrentTabContent: User Logged In.")
            val cloudFavs = cloudFavoritesViewModel.favorites.value
            val allAppScenes = scenesViewModel.scenes.value

            if (currentTab == 0) { // Scenes tab
                sceneFavoritesRecyclerView.visibility = View.VISIBLE
                positionFavoritesRecyclerView.visibility = View.GONE
                updateSceneFavoritesListFromFirestore(cloudFavs, allAppScenes)
            } else { // Positions tab
                sceneFavoritesRecyclerView.visibility = View.GONE
                positionFavoritesRecyclerView.visibility = View.VISIBLE
                updatePositionFavoritesListFromFirestore(cloudFavs)
            }
        }
    }

    private fun observeViewModels() {
        lifecycleScope.launch {
            cloudFavoritesViewModel.favorites.collectLatest { cloudFavoriteList ->
                if (auth.currentUser != null) {
                    Log.d("FavoritesActivity", "Cloud favorites updated. Count: ${cloudFavoriteList.size}")
                    updateCurrentTabContent()
                }
            }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.isLoading.collectLatest { /* TODO: Handle loading */ }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.isMerging.collectLatest { /* TODO: Handle merging */ }
        }
        lifecycleScope.launch {
            cloudFavoritesViewModel.error.collectLatest { error ->
                error?.let {
                    if (auth.currentUser != null || !it.contains("User not logged in", ignoreCase = true)) {
                        Toast.makeText(this@FavoritesActivity, "Cloud Favorites Error: $it", Toast.LENGTH_LONG).show()
                    }
                    cloudFavoritesViewModel.clearError()
                }
            }
        }

        lifecycleScope.launch {
            localFavoritesViewModel.localFavoriteScenes.collectLatest { localFavoriteList ->
                if (auth.currentUser == null) {
                    Log.d("FavoritesActivity", "Local scene favorites updated. Count: ${localFavoriteList.size}")
                    updateCurrentTabContent()
                }
            }
        }
        lifecycleScope.launch {
            localFavoritesViewModel.localFavoritePositions.collectLatest { localFavoritePositionsList ->
                if (auth.currentUser == null) {
                    Log.d("FavoritesActivity", "Local position favorites updated. Count: ${localFavoritePositionsList.size}")
                    updateCurrentTabContent()
                }
            }
        }
        lifecycleScope.launch {
            localFavoritesViewModel.isLoading.collectLatest { /* TODO: Handle loading */ }
        }
        lifecycleScope.launch {
            localFavoritesViewModel.error.collectLatest { error ->
                error?.let {
                    if (auth.currentUser == null) {
                        Toast.makeText(this@FavoritesActivity, "Local Favorites Error: $it", Toast.LENGTH_LONG).show()
                    }
                    localFavoritesViewModel.clearError()
                }
            }
        }

        lifecycleScope.launch {
            scenesViewModel.scenes.collectLatest { allScenes ->
                Log.d("FavoritesActivity", "All scenes updated. Count: ${allScenes.size}")
                if (auth.currentUser == null) {
                    localFavoritesViewModel.refreshLocalFavoriteScenes(allScenes)
                } else {
                    updateCurrentTabContent() // For logged-in, this ensures scene details are up-to-date for favorites
                }
            }
        }
        lifecycleScope.launch {
            scenesViewModel.isLoading.collectLatest { /* TODO: Handle loading */ }
        }
        lifecycleScope.launch {
            scenesViewModel.error.collectLatest { error ->
                error?.let {
                    Toast.makeText(this@FavoritesActivity, "All Scenes Error: $it", Toast.LENGTH_LONG).show()
                    // scenesViewModel.clearError()
                }
            }
        }
    }

    private fun getSceneIdentifier(scene: Scene): String {
        return if (scene.firestoreId.isNotBlank()) scene.firestoreId else "asset_${scene.id}"
    }

    private fun displayLocalSceneFavorites(localFavoriteScenes: List<Scene>) {
        Log.d("FavoritesActivity", "displayLocalSceneFavorites - Count: ${localFavoriteScenes.size}")
        sceneFavoritesAdapter.submitList(localFavoriteScenes.toList())
        // Visibility handled by updateOverallEmptyStateForLocal
    }

    private fun updateSceneFavoritesListFromFirestore(firestoreFavorites: List<Favorite>, currentAllScenes: List<Scene>) {
        Log.d("FavoritesActivity", "updateSceneFavoritesListFromFirestore - firestoreFavorites.size: ${firestoreFavorites.size}, currentAllScenes.size: ${currentAllScenes.size}")
        val favoriteSceneIds = firestoreFavorites.filter { it.itemType == "scene" }.map { it.itemId }.toSet()
        val favoriteSceneObjects = currentAllScenes.filter { scene -> favoriteSceneIds.contains(getSceneIdentifier(scene)) }
        sceneFavoritesAdapter.submitList(favoriteSceneObjects.toList())
        updateOverallEmptyStateForFirestore(firestoreFavorites, currentAllScenes, localFavoritesViewModel.localFavoritePositions.value) // Pass current positions
    }

    private fun updatePositionFavoritesListFromFirestore(firestoreFavorites: List<Favorite>) {
        Log.d("FavoritesActivity", "updatePositionFavoritesListFromFirestore - firestoreFavorites.size: ${firestoreFavorites.size}")
        val favoritePositionItemIds = firestoreFavorites.filter { it.itemType == "position" }.map { it.itemId }

        lifecycleScope.launch {
            val favoritePositionItems = mutableListOf<PositionItem>()
            for (itemId in favoritePositionItemIds) {
                val positionItem = positionsRepository.getPositionById(itemId)
                positionItem?.let { favoritePositionItems.add(it) }
            }
            positionFavoritesAdapter.submitList(favoritePositionItems.toList())
            Log.d("FavoritesActivity", "Fetched and submitted ${favoritePositionItems.size} position items to adapter.")
            updateOverallEmptyStateForFirestore(firestoreFavorites, scenesViewModel.scenes.value, favoritePositionItems)
        }
    }

    private fun updateOverallEmptyStateForLocal(localFavoriteScenes: List<Scene>, localFavoritePositions: List<PositionItem>) {
        Log.d("FavoritesActivity", "updateOverallEmptyStateForLocal - Scenes: ${localFavoriteScenes.size}, Positions: ${localFavoritePositions.size}, Selected Tab: ${tabLayout.selectedTabPosition}")
        val isScenesTabSelected = tabLayout.selectedTabPosition == 0
        val isPositionsTabSelected = tabLayout.selectedTabPosition == 1

        val noSceneFavorites = localFavoriteScenes.isEmpty()
        val noPositionFavorites = localFavoritePositions.isEmpty()

        if (isScenesTabSelected) {
            emptyFavoritesView.visibility = if (noSceneFavorites) View.VISIBLE else View.GONE
            sceneFavoritesRecyclerView.visibility = if (noSceneFavorites) View.GONE else View.VISIBLE
            positionFavoritesRecyclerView.visibility = View.GONE
        } else if (isPositionsTabSelected) {
            emptyFavoritesView.visibility = if (noPositionFavorites) View.VISIBLE else View.GONE
            positionFavoritesRecyclerView.visibility = if (noPositionFavorites) View.GONE else View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
        } else { // No tab selected or invalid state
            emptyFavoritesView.visibility = View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
            positionFavoritesRecyclerView.visibility = View.GONE
        }
    }

    private fun updateOverallEmptyStateForFirestore(
        firestoreFavorites: List<Favorite>, // All favorite objects from Firestore
        currentAllScenes: List<Scene>,     // All available scenes in the app
        actualFavoritePositions: List<PositionItem> // List of PositionItem objects that are favorites
    ) {
        Log.d("FavoritesActivity", "updateOverallEmptyStateForFirestore - Firestore Favs: ${firestoreFavorites.size}, All Scenes: ${currentAllScenes.size}, Actual Fav Positions: ${actualFavoritePositions.size}, Selected Tab: ${tabLayout.selectedTabPosition}")
        val isScenesTabSelected = tabLayout.selectedTabPosition == 0
        val isPositionsTabSelected = tabLayout.selectedTabPosition == 1

        // Determine if there are any scene favorites from the Firestore list
        val favoriteSceneIds = firestoreFavorites.filter { it.itemType == "scene" }.map { it.itemId }.toSet()
        val actualFavoriteScenes = currentAllScenes.filter { scene -> favoriteSceneIds.contains(getSceneIdentifier(scene)) }
        val noSceneFavorites = actualFavoriteScenes.isEmpty()

        // Position favorites are already provided as actualFavoritePositions
        val noPositionFavorites = actualFavoritePositions.isEmpty()

        if (isScenesTabSelected) {
            emptyFavoritesView.visibility = if (noSceneFavorites) View.VISIBLE else View.GONE
            sceneFavoritesRecyclerView.visibility = if (noSceneFavorites) View.GONE else View.VISIBLE
            positionFavoritesRecyclerView.visibility = View.GONE
        } else if (isPositionsTabSelected) {
            emptyFavoritesView.visibility = if (noPositionFavorites) View.VISIBLE else View.GONE
            positionFavoritesRecyclerView.visibility = if (noPositionFavorites) View.GONE else View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
        } else { // No tab selected or invalid state
            emptyFavoritesView.visibility = View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
            positionFavoritesRecyclerView.visibility = View.GONE
        }
    }

    private fun loadJSONFromAsset(fileName: String): String {
        return try {
            assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.e("FavoritesActivity", "Error reading $fileName from assets", e)
            ""
        }
    }
    
    private fun String.capitalizeWords(): String { // Changed from capitalize to capitalizeWords for clarity
        return this.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
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
        drawerToggle.syncState()
    }

    private fun setupSceneSwipeToDelete(recyclerView: RecyclerView) {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (viewHolder.adapterPosition != RecyclerView.NO_POSITION) {
                    sceneFavoritesAdapter.removeItem(viewHolder.adapterPosition)
                }
            }
            override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                val itemView = viewHolder.itemView
                val background = ColorDrawable(Color.parseColor("#F44336"))
                val deleteIcon = ContextCompat.getDrawable(this@FavoritesActivity, R.drawable.ic_delete)?.apply { setTint(Color.WHITE) }
                val cornerRadius = 16f * resources.displayMetrics.density
                val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                val iconTop = itemView.top + iconMargin
                val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)

                if (dX > 0) { // Swiping to the right
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?: 0)
                    deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    val backgroundPath = android.graphics.Path().apply {
                        addRoundRect(android.graphics.RectF(itemView.left.toFloat(), itemView.top.toFloat(), itemView.left + dX, itemView.bottom.toFloat()), cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    }
                    c.save()
                    c.clipPath(backgroundPath)
                    background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    background.draw(c)
                    c.restore()
                }
                deleteIcon?.draw(c)
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }
    
    private fun setupPositionSwipeToDelete(recyclerView: RecyclerView) {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                 if (viewHolder.adapterPosition != RecyclerView.NO_POSITION) {
                    positionFavoritesAdapter.removeItem(viewHolder.adapterPosition)
                }
            }
            override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                val itemView = viewHolder.itemView
                val background = ColorDrawable(Color.parseColor("#F44336"))
                val deleteIcon = ContextCompat.getDrawable(this@FavoritesActivity, R.drawable.ic_delete)?.apply { setTint(Color.WHITE) }
                val cornerRadius = 16f * resources.displayMetrics.density
                val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                val iconTop = itemView.top + iconMargin
                val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)

                if (dX > 0) { // Swiping to the right
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?: 0)
                    deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                     val backgroundPath = android.graphics.Path().apply {
                        addRoundRect(android.graphics.RectF(itemView.left.toFloat(), itemView.top.toFloat(), itemView.left + dX, itemView.bottom.toFloat()), cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                    }
                    c.save()
                    c.clipPath(backgroundPath)
                    background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                    background.draw(c)
                    c.restore()
                }
                deleteIcon?.draw(c)
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }
}
