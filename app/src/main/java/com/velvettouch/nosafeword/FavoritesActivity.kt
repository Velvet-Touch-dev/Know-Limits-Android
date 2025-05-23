package com.velvettouch.nosafeword

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
import com.velvettouch.nosafeword.BaseActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private val favoritesViewModel: FavoritesViewModel by viewModels()
    private val scenesViewModel: ScenesViewModel by viewModels() // Add ScenesViewModel

    // private var allScenes: List<Scene> = emptyList() // No longer needed as a direct member, will be passed by combine
    private var positions: MutableList<Position> = mutableListOf() // Still needed to map Favorite to Position object

    private lateinit var sceneFavoritesAdapter: FavoriteScenesAdapter
    private lateinit var positionFavoritesAdapter: FavoritePositionsAdapter

    companion object {
        private const val SCENES_FILENAME = "scenes.json"
        // Constants for SharedPreferences are no longer needed here for favorites
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)

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
                        // Ensure lists are updated with current data
                        updateSceneFavoritesList(favoritesViewModel.favorites.value, scenesViewModel.scenes.value)
                        updatePositionFavoritesList(favoritesViewModel.favorites.value) // This already calls updateOverallEmptyState
                    }
                    1 -> { // Positions tab
                        sceneFavoritesRecyclerView.visibility = View.GONE
                        positionFavoritesRecyclerView.visibility = View.VISIBLE
                        // Ensure lists are updated with current data
                        updateSceneFavoritesList(favoritesViewModel.favorites.value, scenesViewModel.scenes.value) // Call to ensure overall empty state is accurate
                        updatePositionFavoritesList(favoritesViewModel.favorites.value)
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        sceneFavoritesAdapter = FavoriteScenesAdapter({ scene ->
            val intent = Intent(this, MainActivity::class.java)
            // MainActivity needs to know if it's a firestoreId or a local asset based id.
            // For now, assuming MainActivity can handle firestoreId if that's what we are favoriting.
            intent.putExtra("DISPLAY_SCENE_ID", scene.firestoreId) // Use firestoreId
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }, { scene ->
            favoritesViewModel.removeFavorite(scene.firestoreId, "scene") // Use firestoreId
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
            favoritesViewModel.removeFavorite(position.name, "position")
        })

        positionFavoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = positionFavoritesAdapter
            setupPositionSwipeToDelete(this)
        }

        findViewById<androidx.cardview.widget.CardView>(R.id.swipe_hint_container).visibility = View.VISIBLE

        // Load local positions data (scenes will be loaded from ScenesViewModel)
        loadPositions()

        observeViewModels() // Renamed to reflect observing multiple ViewModels
        favoritesViewModel.loadFavorites() // Initial load
        // scenesViewModel.loadScenes() // This call is not needed; ScenesViewModel loads internally.

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

    private fun observeViewModels() {
        lifecycleScope.launch {
            combine( // Use combine directly
                favoritesViewModel.favorites,
                scenesViewModel.scenes
            ) { favoritesList, scenesList ->
                Log.d("FavoritesActivity", "observeViewModels: Combined event: favorites.size=${favoritesList.size}, allScenes.size=${scenesList.size}")
                // Pass both lists to updateSceneFavoritesList
                updateSceneFavoritesList(favoritesList, scenesList)
                // Positions list doesn't depend on allScenes from ViewModel for its own items,
                // but updateOverallEmptyState (called within updatePositionFavoritesList) will need it.
                updatePositionFavoritesList(favoritesList)
            }.collectLatest {
                // This block is primarily to trigger the collection.
                // The main logic is in the transformation block of combine.
                Log.d("FavoritesActivity", "observeViewModels: collectLatest triggered after combine.")
            }
        }

        lifecycleScope.launch {
            favoritesViewModel.isLoading.collectLatest { isLoading ->
                Log.d("FavoritesActivity", "Favorites isLoading: $isLoading")
                // Optionally update a global loading indicator
            }
        }
        lifecycleScope.launch {
            favoritesViewModel.error.collectLatest { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@FavoritesActivity, "Favorites Error: $it", Toast.LENGTH_LONG).show()
                    Log.e("FavoritesActivity", "Favorites Error: $it")
                    favoritesViewModel.clearError()
                }
            }
        }

        lifecycleScope.launch {
            scenesViewModel.isLoading.collectLatest { isLoading ->
                Log.d("FavoritesActivity", "Scenes isLoading: $isLoading")
                // Optionally update a global loading indicator
            }
        }
        lifecycleScope.launch {
            scenesViewModel.error.collectLatest { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@FavoritesActivity, "Scenes Error: $it", Toast.LENGTH_LONG).show()
                    Log.e("FavoritesActivity", "Scenes Error: $it")
                    // scenesViewModel.clearError() // If ScenesViewModel has this
                }
            }
        }
    }

    private fun updateSceneFavoritesList(allFavorites: List<Favorite>, currentAllScenes: List<Scene>) {
        Log.d("FavoritesActivity", "updateSceneFavoritesList BEGIN - allFavorites.size: ${allFavorites.size}, currentAllScenes.size: ${currentAllScenes.size}")
        val favoriteSceneIds = allFavorites.filter { it.itemType == "scene" }.map { it.itemId }.toSet()
        Log.d("FavoritesActivity", "updateSceneFavoritesList - favoriteSceneIds.size: ${favoriteSceneIds.size}, IDs: $favoriteSceneIds")

        val favoriteSceneObjects = currentAllScenes.filter { scene ->
            val isFav = favoriteSceneIds.contains(scene.firestoreId)
            if (isFav) Log.d("FavoritesActivity", "updateSceneFavoritesList - Match found: Scene Title='${scene.title}', firestoreId='${scene.firestoreId}'")
            isFav
        }
        Log.d("FavoritesActivity", "updateSceneFavoritesList - favoriteSceneObjects.size: ${favoriteSceneObjects.size}")
        sceneFavoritesAdapter.submitList(favoriteSceneObjects.toList())

        if (tabLayout.selectedTabPosition == 0) {
            sceneFavoritesRecyclerView.visibility = if (favoriteSceneObjects.isEmpty()) View.GONE else View.VISIBLE
            Log.d("FavoritesActivity", "updateSceneFavoritesList - ScenesTab selected. RecyclerView visible: ${sceneFavoritesRecyclerView.visibility == View.VISIBLE}")
        }
        updateOverallEmptyState(allFavorites, currentAllScenes)
    }

    private fun updatePositionFavoritesList(allFavorites: List<Favorite>) {
        Log.d("FavoritesActivity", "updatePositionFavoritesList BEGIN - allFavorites.size: ${allFavorites.size}")
        val favoritePositionNames = allFavorites.filter { it.itemType == "position" }.map { it.itemId }.toSet()
        Log.d("FavoritesActivity", "updatePositionFavoritesList - favoritePositionNames.size: ${favoritePositionNames.size}, Names: $favoritePositionNames")

        val favoritePositionObjects = positions.filter { position -> favoritePositionNames.contains(position.name) }
        Log.d("FavoritesActivity", "updatePositionFavoritesList - favoritePositionObjects.size: ${favoritePositionObjects.size}")
        positionFavoritesAdapter.submitList(favoritePositionObjects.toList())

        if (tabLayout.selectedTabPosition == 1) {
            positionFavoritesRecyclerView.visibility = if (favoritePositionObjects.isEmpty()) View.GONE else View.VISIBLE
            Log.d("FavoritesActivity", "updatePositionFavoritesList - PositionsTab selected. RecyclerView visible: ${positionFavoritesRecyclerView.visibility == View.VISIBLE}")
        }
        // Pass the current scenes list to updateOverallEmptyState
        updateOverallEmptyState(allFavorites, scenesViewModel.scenes.value)
    }

    private fun updateOverallEmptyState(allFavorites: List<Favorite>, currentAllScenes: List<Scene>) {
        Log.d("FavoritesActivity", "updateOverallEmptyState BEGIN - allFavorites.size: ${allFavorites.size}, currentAllScenes.size: ${currentAllScenes.size}")
        val sceneFavoriteItemIds = allFavorites.filter { it.itemType == "scene" }.map { it.itemId }.toSet()
        val actualFavoriteScenes = currentAllScenes.filter { scene -> sceneFavoriteItemIds.contains(scene.firestoreId) }

        val positionFavoriteItemIds = allFavorites.filter { it.itemType == "position" }.map { it.itemId }.toSet()
        val actualFavoritePositions = positions.filter { pos -> positionFavoriteItemIds.contains(pos.name) }
        Log.d("FavoritesActivity", "updateOverallEmptyState - actualFavoriteScenes.size: ${actualFavoriteScenes.size}, actualFavoritePositions.size: ${actualFavoritePositions.size}")

        val noSceneFavorites = actualFavoriteScenes.isEmpty()
        val noPositionFavorites = actualFavoritePositions.isEmpty()

        if (noSceneFavorites && noPositionFavorites) {
            emptyFavoritesView.visibility = View.VISIBLE
            sceneFavoritesRecyclerView.visibility = View.GONE
            positionFavoritesRecyclerView.visibility = View.GONE
            Log.d("FavoritesActivity", "updateOverallEmptyState - All empty. EmptyView VISIBLE. Both RVs GONE.")
        } else {
            emptyFavoritesView.visibility = View.GONE
            Log.d("FavoritesActivity", "updateOverallEmptyState - Not all empty. EmptyView GONE.")
            // Visibility for the selected tab's RecyclerView is handled by its respective updateXxxList method.
            // Here, we just ensure the non-selected tab's RecyclerView is hidden if it's empty.
            if (tabLayout.selectedTabPosition == 0) { // Scenes tab selected
                positionFavoritesRecyclerView.visibility = View.GONE // Hide positions RV
                sceneFavoritesRecyclerView.visibility = if (noSceneFavorites) View.GONE else View.VISIBLE
            } else { // Positions tab selected
                sceneFavoritesRecyclerView.visibility = View.GONE // Hide scenes RV
                positionFavoritesRecyclerView.visibility = if (noPositionFavorites) View.GONE else View.VISIBLE
            }
        }
    }
    
    // loadScenes() is no longer needed as scenes are provided by ScenesViewModel
    // private fun loadScenes() { ... }

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