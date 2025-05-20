package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import com.velvettouch.nosafeword.BaseActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
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
    
    private var sceneFavorites: MutableSet<String> = mutableSetOf()
    private var positionFavorites: MutableSet<String> = mutableSetOf()
    private var scenes: MutableList<Scene> = mutableListOf()
    private var positions: MutableList<Position> = mutableListOf()
    
    private lateinit var sceneFavoritesAdapter: FavoriteScenesAdapter
    private lateinit var positionFavoritesAdapter: FavoritePositionsAdapter
    
    companion object {
        private const val SCENES_FILENAME = "scenes.json"
        private const val SCENE_FAVORITES_PREF = "favorites"
        private const val POSITION_FAVORITES_PREF = "position_favorites"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorites)
        
        // Initialize views
        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        tabLayout = findViewById(R.id.tabs)
        sceneFavoritesRecyclerView = findViewById(R.id.scene_favorites_recycler_view)
        positionFavoritesRecyclerView = findViewById(R.id.position_favorites_recycler_view)
        emptyFavoritesView = findViewById(R.id.empty_favorites_view)
        
        // Set up toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.favorites)
        
        // Set up drawer toggle
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
                    finish() // Close this activity
                    true
                }
                R.id.nav_positions -> {
                    // Launch positions activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PositionsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish() // Close this activity
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
                    // Already on favorites page, just close drawer
                    drawerLayout.closeDrawer(GravityCompat.START)
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
        navigationView.setCheckedItem(R.id.nav_favorites)
        
        // Remove header view if needed
        if (navigationView.headerCount > 0) {
            navigationView.removeHeaderView(navigationView.getHeaderView(0))
        }
        
        // Set up tab layout
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // Switch between scenes and positions favorites
                when (tab.position) {
                    0 -> { // Scenes tab
                        sceneFavoritesRecyclerView.visibility = View.VISIBLE
                        positionFavoritesRecyclerView.visibility = View.GONE
                        updateSceneFavoritesList()
                    }
                    1 -> { // Positions tab
                        sceneFavoritesRecyclerView.visibility = View.GONE
                        positionFavoritesRecyclerView.visibility = View.VISIBLE
                        updatePositionFavoritesList()
                    }
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab) {
                // Not needed
            }
            
            override fun onTabReselected(tab: TabLayout.Tab) {
                // Not needed
            }
        })
        
        // Set up RecyclerView for scene favorites
        sceneFavoritesAdapter = FavoriteScenesAdapter({ scene ->
            // Handle scene favorite click - switching to random mode and displaying the scene
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("DISPLAY_SCENE_ID", scene.id)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish() // Close favorites activity to prevent navigation issues
        }, { scene ->
            // Handle remove scene from favorites
            removeSceneFromFavorites(scene)
        })
        
        sceneFavoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = sceneFavoritesAdapter
            
            // Set up swipe to delete functionality
            setupSceneSwipeToDelete(this)
        }
        
        // Set up RecyclerView for position favorites
        positionFavoritesAdapter = FavoritePositionsAdapter({ position ->
            // Handle position favorite click - switching to positions page and displaying the position
            val intent = Intent(this, PositionsActivity::class.java)
            intent.putExtra("DISPLAY_POSITION_NAME", position.name)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish() // Close favorites activity to prevent navigation issues
        }, { position ->
            // Handle remove position from favorites
            removePositionFromFavorites(position)
        })
        
        positionFavoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@FavoritesActivity)
            adapter = positionFavoritesAdapter
            
            // Set up swipe to delete functionality for position favorites
            setupPositionSwipeToDelete(this)
            
            // Add swipe hint at activity level, not from inside the RecyclerView
        }
        
        // Show the swipe hint
        findViewById<androidx.cardview.widget.CardView>(R.id.swipe_hint_container).visibility = View.VISIBLE
        
        // Load favorites
        loadSceneFavorites()
        loadPositionFavorites()
        
        // Load scenes and positions data
        loadScenes()
        loadPositions()
        
        // Update UI
        updateSceneFavoritesList()
    }
    
    private fun loadSceneFavorites() {
        val prefs = getSharedPreferences(SCENE_FAVORITES_PREF, Context.MODE_PRIVATE)
        sceneFavorites = prefs.getStringSet("favorite_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }
    
    private fun loadPositionFavorites() {
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        positionFavorites = prefs.getStringSet("favorite_position_names", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }
    
    private fun removeSceneFromFavorites(scene: Scene) {
        val sceneId = scene.id.toString()
        
        if (sceneFavorites.contains(sceneId)) {
            // Remove from favorites
            sceneFavorites.remove(sceneId)
            saveSceneFavorites()
            
            // Update the list
            updateSceneFavoritesList()
        }
    }
    
    private fun removePositionFromFavorites(position: Position) {
        if (positionFavorites.contains(position.name)) {
            // Remove from favorites
            positionFavorites.remove(position.name)
            savePositionFavorites()
            
            // Update the list
            updatePositionFavoritesList()
        }
    }
    
    private fun saveSceneFavorites() {
        val prefs = getSharedPreferences(SCENE_FAVORITES_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_ids", sceneFavorites).apply()
    }
    
    private fun savePositionFavorites() {
        val prefs = getSharedPreferences(POSITION_FAVORITES_PREF, Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_position_names", positionFavorites).apply()
    }
    
    private fun updateSceneFavoritesList() {
        // Get favorite scenes
        val favoriteScenes = scenes.filter { sceneFavorites.contains(it.id.toString()) }
        
        if (favoriteScenes.isEmpty()) {
            sceneFavoritesRecyclerView.visibility = View.GONE
            emptyFavoritesView.visibility = View.VISIBLE
        } else {
            sceneFavoritesRecyclerView.visibility = View.VISIBLE
            emptyFavoritesView.visibility = View.GONE
            sceneFavoritesAdapter.submitList(favoriteScenes)
        }
    }
    
    private fun updatePositionFavoritesList() {
        // Get favorite positions
        val favoritePositions = positions.filter { positionFavorites.contains(it.name) }
        
        if (favoritePositions.isEmpty()) {
            positionFavoritesRecyclerView.visibility = View.GONE
            emptyFavoritesView.visibility = View.VISIBLE
        } else {
            positionFavoritesRecyclerView.visibility = View.VISIBLE
            emptyFavoritesView.visibility = View.GONE
            positionFavoritesAdapter.submitList(favoritePositions)
        }
    }
    
    private fun loadScenes() {
        val internalFile = File(filesDir, SCENES_FILENAME)
        var scenesLoadedFromFile = false

        if (internalFile.exists()) {
            try {
                val jsonString = internalFile.bufferedReader().use { it.readText() }
                if (jsonString.isNotBlank()) {
                    val jsonArray = JSONArray(jsonString)
                    val scenesList = mutableListOf<Scene>()
                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i)
                        scenesList.add(Scene(
                            id = jsonObject.getInt("id"),
                            title = jsonObject.getString("title"),
                            content = jsonObject.getString("content")
                        ))
                    }
                    scenes = scenesList
                    scenesLoadedFromFile = true
                }
            } catch (e: Exception) {
                e.printStackTrace() // Log error, then fallback to assets
            }
        }

        if (!scenesLoadedFromFile) {
            try {
                val jsonString = loadJSONFromAsset(SCENES_FILENAME)
                val jsonArray = JSONArray(jsonString)
                val scenesList = mutableListOf<Scene>()
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    scenesList.add(Scene(
                        id = jsonObject.getInt("id"),
                        title = jsonObject.getString("title"),
                        content = jsonObject.getString("content")
                    ))
                }
                scenes = scenesList
            } catch (e: Exception) {
                e.printStackTrace()
                // Consider showing an error to the user or logging
            }
        }
    }
    
    private fun loadPositions() {
        val loadedPositions = mutableListOf<Position>()
        // Load from assets
        try {
            val assetManager = assets
            val assetFiles = assetManager.list("positions")
            assetFiles?.forEach { fileName ->
                if (fileName.endsWith(".webp", ignoreCase = true) || fileName.endsWith(".png", ignoreCase = true) || fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true)) {
                    val nameWithoutExtension = fileName.substringBeforeLast(".")
                    // Ensure consistent capitalization with how names are stored in favorites
                    val displayName = nameWithoutExtension.replace("_", " ").split(" ").joinToString(" ") { word ->
                        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    }
                    loadedPositions.add(Position(displayName, "positions/$fileName", true))
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            // Handle error, e.g., show a toast
        }

        // Load custom positions from app's external files directory
        val customPositionsDir = getExternalFilesDir("positions")
        if (customPositionsDir != null && customPositionsDir.exists()) {
            customPositionsDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".png", ignoreCase = true) || file.name.endsWith(".webp", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true))) {
                    val parts = file.nameWithoutExtension.split("_")
                    // Ensure consistent capitalization
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
        positions = loadedPositions // Assign the combined list
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
    
    override fun onBackPressed() {
        // Close the drawer if it's open, otherwise proceed with normal back button behavior
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
    
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