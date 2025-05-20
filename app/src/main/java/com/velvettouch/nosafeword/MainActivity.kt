package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.velvettouch.nosafeword.BaseActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson // Added for SharedPreferences
import com.google.gson.reflect.TypeToken // Added for SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import kotlin.random.Random

class MainActivity : BaseActivity() {

    private lateinit var titleTextView: TextView
    private lateinit var contentTextView: TextView
    private lateinit var randomizeButton: FloatingActionButton // Now acts as next button
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

    private var scenes: MutableList<Scene> = mutableListOf()
    private var originalScenes: List<Scene> = listOf() // Keep original scenes for reset
    private var currentSceneIndex: Int = -1
    private var sceneHistory: MutableList<Int> = mutableListOf() // History of visited scenes
    private var historyPosition: Int = -1 // Current position in history
    private var favorites: MutableSet<String> = mutableSetOf()
    private var currentMode: Int = MODE_RANDOM
    private var currentToast: Toast? = null
    private var nextSceneId: Int = 1 // Re-declare as a member variable, will be updated in loadScenes

    private val gson = Gson() // Added for SharedPreferences
    private val plannedItemsPrefsName = "PlanNightPrefs"
    private val plannedItemsKey = "plannedItemsList"

    private lateinit var favoritesAdapter: FavoriteScenesAdapter
    private lateinit var editAdapter: EditScenesAdapter

    companion object {
        private const val MODE_RANDOM = 0
        private const val MODE_FAVORITES = 1
        private const val MODE_EDIT = 2

        private const val SCENES_FILENAME = "scenes.json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        titleTextView = findViewById(R.id.titleTextView)
        contentTextView = findViewById(R.id.contentTextView)
        randomizeButton = findViewById(R.id.randomizeButton) // Now next button
        previousButton = findViewById(R.id.previousButton)
        editButton = findViewById(R.id.editButton)
        sceneCardView = findViewById(R.id.sceneCardView)
        shareButton = findViewById(R.id.shareButton)
        topAppBar = findViewById(R.id.topAppBar)
        bottomNavigation = findViewById(R.id.bottom_navigation)
        randomContent = findViewById(R.id.random_content)
        favoritesContainer = findViewById(R.id.favorites_container)
        editContainer = findViewById(R.id.edit_container)
        favoritesRecyclerView = findViewById(R.id.favorites_recycler_view)
        editRecyclerView = findViewById(R.id.edit_recycler_view)
        emptyFavoritesView = findViewById(R.id.empty_favorites_view)
        addSceneButton = findViewById(R.id.add_scene_fab)
        resetScenesButton = findViewById(R.id.reset_scenes_button)

        // Get drawer components
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)

        // Set up the toolbar
        setSupportActionBar(topAppBar)

        // Set up ActionBarDrawerToggle
        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            topAppBar,
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
                    // Already on scenes page, just close drawer
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_positions -> {
                    // Launch positions activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PositionsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    true
                }
                R.id.nav_body_worship -> {
                    // Launch body worship activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, BodyWorshipActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    true
                }
                R.id.nav_task_list -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, TaskListActivity::class.java)
                    // intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP // Optional: decide if you want to clear top
                    startActivity(intent)
                    // finish() // Optional: decide if MainActivity should finish
                    true
                }
                R.id.nav_plan_night -> {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PlanNightActivity::class.java)
                    // intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP // Optional
                    startActivity(intent)
                    true
                }
                R.id.nav_favorites -> {
                    // Launch favorites activity
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

        // Remove header view
        navigationView.removeHeaderView(navigationView.getHeaderView(0))

        // Rest of onCreate implementation
        // Enable links in the title and content text views
        titleTextView.movementMethod = LinkMovementMethod.getInstance()
        contentTextView.movementMethod = LinkMovementMethod.getInstance()

        // Set up RecyclerView for favorites
        favoritesAdapter = FavoriteScenesAdapter({ scene ->
            // Handle favorite item click - switching to random mode and displaying the scene
            switchToRandomMode(scene)
        }, { scene ->
            // Handle removal from favorites
            removeFromFavorites(scene)
        })

        favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = favoritesAdapter

            // Set up swipe to remove functionality
            val swipeHandler = object : ItemTouchHelper.SimpleCallback(
                0, // No drag and drop
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT // Enable swipe in both directions
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
                    val scene = favoritesAdapter.currentList[position]
                    removeFromFavorites(scene)
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
                    val background = ColorDrawable(Color.parseColor("#F44336")) // Red color
                    val deleteIcon = ContextCompat.getDrawable(
                        this@MainActivity,
                        R.drawable.ic_delete
                    )?.apply {
                        setTint(Color.WHITE)
                    }

                    val iconMargin = (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                    val iconTop = itemView.top + (itemView.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
                    val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)

                    // Swiping to the right
                    when {
                        dX > 0 -> {
                            val iconLeft = itemView.left + iconMargin
                            val iconRight = iconLeft + (deleteIcon?.intrinsicWidth ?: 0)
                            deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                            background.setBounds(
                                itemView.left, itemView.top,
                                itemView.left + dX.toInt(), itemView.bottom
                            )
                        }
                        dX < 0 -> {
                            val iconRight = itemView.right - iconMargin
                            val iconLeft = iconRight - (deleteIcon?.intrinsicWidth ?: 0)
                            deleteIcon?.setBounds(iconLeft, iconTop, iconRight, iconBottom)

                            background.setBounds(
                                itemView.right + dX.toInt(), itemView.top,
                                itemView.right, itemView.bottom
                            )
                        }
                        else -> background.setBounds(0, 0, 0, 0)
                    }

                    background.draw(c)
                    deleteIcon?.draw(c)

                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }

            // Attach the swipe handler to the RecyclerView
            ItemTouchHelper(swipeHandler).attachToRecyclerView(this)
        }

        // Set up RecyclerView for edit (now search)
        editAdapter = EditScenesAdapter(
            onEditClick = { scene -> switchToRandomMode(scene) },  // Changed to redirect to random mode
            onDeleteClick = { scene -> showDeleteConfirmation(scene) }
        )

        editRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = editAdapter
        }

        // Load favorites from preferences
        loadFavorites()

        // Load scenes from JSON file
        loadScenes()

        // Save original scenes for reset
        // originalScenes = scenes.toList() // This is now handled within loadScenes to always use assets for originalScenes

        // Set up bottom navigation
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_random -> {
                    currentMode = MODE_RANDOM
                    updateUI()
                    true
                }
                R.id.navigation_edit -> {
                    currentMode = MODE_EDIT
                    updateUI()
                    true
                }
                else -> false
            }
        }

        // Set button click listeners
        randomizeButton.setOnClickListener {
            val slideOut = AnimationUtils.loadAnimation(this, R.anim.slide_out_left)
            val slideIn = AnimationUtils.loadAnimation(this, R.anim.slide_in_right)

            sceneCardView.startAnimation(slideOut)
            slideOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    // Display next scene
                    displayNextScene()
                    sceneCardView.startAnimation(slideIn)
                }
            })
        }

        // Set previous button click listener
        previousButton.setOnClickListener {
            val slideOut = AnimationUtils.loadAnimation(this, R.anim.slide_out_right)
            val slideIn = AnimationUtils.loadAnimation(this, R.anim.slide_in_left)

            sceneCardView.startAnimation(slideOut)
            slideOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    // Go to the previous scene in history if available
                    if (historyPosition > 0) {
                        // Move back in history
                        historyPosition--

                        // Set current index to the previous item in history
                        currentSceneIndex = sceneHistory[historyPosition]

                        // Display the scene
                        displayScene(scenes[currentSceneIndex])

                        // Update button state
                        // previousButton.isEnabled = historyPosition > 0 // Now handled by updatePreviousButtonState
                        updatePreviousButtonState()
                    } else {
                        // Show a message if there's no previous scene
                        showMaterialToast("No previous scene available", false)
                        updatePreviousButtonState() // Ensure button is visually disabled
                    }
                    sceneCardView.startAnimation(slideIn)
                }
            })
        }

        // Set share button click listener
        shareButton.setOnClickListener {
            shareCurrentScene()
        }

        // Set edit button click listener
        editButton.setOnClickListener {
            if (currentSceneIndex >= 0 && currentSceneIndex < scenes.size) {
                // Show the edit dialog for the current scene
                showEditDialog(scenes[currentSceneIndex])
            }
        }

        // Set add scene button click listener
        addSceneButton.setOnClickListener {
            showEditDialog(null) // null indicates creating a new scene
        }

        // Set reset scenes button click listener
        resetScenesButton.setOnClickListener {
            showResetConfirmation()
        }

        // Initialize the sceneHistory list with the initial scene
        sceneHistory.clear()
        historyPosition = -1

        // Check for specific scene to display from intent
        val displaySceneId = intent.getIntExtra("DISPLAY_SCENE_ID", -1)
        val displaySceneTitle = intent.getStringExtra("DISPLAY_SCENE_TITLE")
        var sceneFound = false

        if (displaySceneId != -1) {
            val sceneIndex = scenes.indexOfFirst { it.id == displaySceneId }
            if (sceneIndex != -1) {
                currentSceneIndex = sceneIndex
                sceneHistory.add(currentSceneIndex)
                historyPosition = sceneHistory.size - 1
                displayScene(scenes[currentSceneIndex])
                sceneFound = true
            }
        }

        if (!sceneFound && displaySceneTitle != null) {
            val sceneIndex = scenes.indexOfFirst { it.title.equals(displaySceneTitle, ignoreCase = true) }
            if (sceneIndex != -1) {
                currentSceneIndex = sceneIndex
                sceneHistory.add(currentSceneIndex)
                historyPosition = sceneHistory.size - 1
                displayScene(scenes[currentSceneIndex])
                sceneFound = true
            }
        }

        if (!sceneFound) {
            // If no specific scene found by ID or title, display initial random scene
            displayRandomScene()
        }

        // Update UI based on initial mode
        updateUI()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the appropriate menu based on the current mode
        when (currentMode) {
            MODE_RANDOM -> {
                menuInflater.inflate(R.menu.top_app_bar, menu)
                updateFavoriteIcon(menu)
            }
            MODE_EDIT -> {
                menuInflater.inflate(R.menu.edit_menu, menu)

                // Set up search functionality
                val searchItem = menu.findItem(R.id.action_search)
                val searchView = searchItem.actionView as SearchView
                searchView.queryHint = getString(R.string.search_scenes)

                searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean {
                        return false // We handle search as user types
                    }

                    override fun onQueryTextChange(newText: String?): Boolean {
                        // Filter the edit list based on search query
                        filterScenes(newText)
                        return true
                    }
                })

                // When search is closed, reset to showing all scenes
                searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                    override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                        return true
                    }

                    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                        updateEditList() // Reset to show all scenes
                        return true
                    }
                })
            }
            else -> {} // No menu for other modes
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }

        return when (item.itemId) {
            R.id.action_favorite -> {
                if (currentMode == MODE_RANDOM) {
                    toggleFavorite()
                }
                true
            }
            R.id.action_add_to_plan -> {
                if (currentMode == MODE_RANDOM) {
                    addCurrentSceneToPlan()
                }
                true
            }
            R.id.action_add_to_plan -> {
                if (currentMode == MODE_RANDOM) {
                    addCurrentSceneToPlan()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
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
        // Cancel any showing toast
        currentToast?.cancel()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Update the selected menu item in the drawer
        navigationView.setCheckedItem(R.id.nav_scenes)
    }

    private fun updateUI() {
        when (currentMode) {
            MODE_RANDOM -> {
                // Update app bar title
                topAppBar.title = getString(R.string.app_name)
                
                // Show random content, hide others
                randomContent.visibility = View.VISIBLE
                editContainer.visibility = View.GONE
                shareButton.visibility = View.VISIBLE
                // Update button states
                randomizeButton.visibility = View.VISIBLE
                previousButton.visibility = View.VISIBLE
                editButton.visibility = View.VISIBLE
                
                // Enable/disable previous button based on history
                previousButton.isEnabled = historyPosition > 0
            }
            MODE_EDIT -> {
                // Update app bar title
                topAppBar.title = getString(R.string.edit_scenes)
                
                // Hide random & favorites, show edit
                randomContent.visibility = View.GONE
                editContainer.visibility = View.VISIBLE
                updateEditList()
                shareButton.visibility = View.GONE
                randomizeButton.visibility = View.GONE
                previousButton.visibility = View.GONE
                editButton.visibility = View.GONE
            }
        }
        
        // Recreate options menu to update search visibility
        invalidateOptionsMenu()
    }


    private fun filterScenes(query: String?) {
        if (query.isNullOrBlank()) {
            // If query is empty, show all scenes
            updateEditList()
            return
        }

        // Filter scenes based on query text
        val filteredScenes = scenes.filter { scene ->
            scene.title.contains(query, ignoreCase = true) ||
                    scene.content.contains(query, ignoreCase = true)
        }

        // Update adapter with filtered list
        editAdapter.submitList(filteredScenes)
    }

    private fun updateFavoritesList() {
        // Get favorite scenes
        val favoriteScenes = scenes.filter { favorites.contains(it.id.toString()) }

        if (favoriteScenes.isEmpty()) {
            favoritesRecyclerView.visibility = View.GONE
            emptyFavoritesView.visibility = View.VISIBLE
        } else {
            favoritesRecyclerView.visibility = View.VISIBLE
            emptyFavoritesView.visibility = View.GONE
            favoritesAdapter.submitList(null) // Clear the list first
            favoritesAdapter.submitList(favoriteScenes) // Update with new list
        }
    }

    private fun updateEditList() {
        // Update the edit adapter with all scenes
        editAdapter.submitList(scenes.toList())
    }

    private fun loadFavorites() {
        val prefs = getSharedPreferences("favorites", Context.MODE_PRIVATE)
        favorites = prefs.getStringSet("favorite_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveFavorites() {
        val prefs = getSharedPreferences("favorites", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_ids", favorites).apply()
    }

    private fun toggleFavorite() {
        if (currentSceneIndex < 0 || currentSceneIndex >= scenes.size) return

        val scene = scenes[currentSceneIndex]
        val sceneId = scene.id.toString()

        if (favorites.contains(sceneId)) {
            favorites.remove(sceneId)
            showMaterialToast(getString(R.string.favorite_removed), false)
        } else {
            favorites.add(sceneId)
            showMaterialToast(getString(R.string.favorite_added), true)
        }

        saveFavorites()
        invalidateOptionsMenu() // Refresh the options menu to update the favorite icon
    }

    private fun removeFromFavorites(scene: Scene) {
        val sceneId = scene.id.toString()

        if (favorites.contains(sceneId)) {
            // Remove from favorites
            favorites.remove(sceneId)
            saveFavorites()

            // Show toast
            showMaterialToast(getString(R.string.favorite_removed), false)

            // Update the favorites list
            updateFavoritesList()
        }
    }

    private fun showMaterialToast(message: String, isAddedToFavorite: Boolean) {
        // Cancel any previous toast to avoid stacking
        currentToast?.cancel()

        // Inflate custom layout
        val inflater = LayoutInflater.from(this)
        val layout = inflater.inflate(R.layout.toast_material_you, null)

        // Set text and icon
        val text = layout.findViewById<TextView>(R.id.toast_text)
        text.text = message

        // Set icon based on action
        val icon = layout.findViewById<TextView>(R.id.toast_icon)
        icon.text = if (isAddedToFavorite) "❤️" else "💔"

        // Create and show custom toast
        val toast = Toast(applicationContext)
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 150)
        toast.duration = Toast.LENGTH_SHORT
        toast.view = layout
        toast.show()

        // Store reference to cancel later if needed
        currentToast = toast
    }

    private fun updateFavoriteIcon(menu: Menu? = null) {
        // Check if current scene is favorited
        if (currentSceneIndex >= 0 && currentSceneIndex < scenes.size) {
            val scene = scenes[currentSceneIndex]
            val isFavorite = favorites.contains(scene.id.toString())

            // Update the icon in the menu
            menu?.findItem(R.id.action_favorite)?.let { menuItem ->
                menuItem.setIcon(
                    if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
                )
            }
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
                    var maxId = 0

                    for (i in 0 until jsonArray.length()) {
                        val jsonObject = jsonArray.getJSONObject(i)
                        val id = jsonObject.getInt("id")
                        if (id > maxId) {
                            maxId = id
                        }
                        scenesList.add(Scene(
                            id = id,
                            title = jsonObject.getString("title"),
                            content = jsonObject.getString("content")
                        ))
                    }
                    scenes = scenesList // Do not shuffle here, preserve user's order if loaded from file
                    nextSceneId = maxId + 1
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
                var maxId = 0

                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val id = jsonObject.getInt("id")
                    if (id > maxId) {
                        maxId = id
                    }
                    scenesList.add(Scene(
                        id = id,
                        title = jsonObject.getString("title"),
                        content = jsonObject.getString("content")
                    ))
                }
                scenesList.shuffle() // Shuffle only if loading from assets
                scenes = scenesList
                nextSceneId = maxId + 1
            } catch (e: Exception) {
                e.printStackTrace()
                titleTextView.text = getString(R.string.error_loading)
                contentTextView.text = "${getString(R.string.check_json)}: ${e.message}"
            }
        }
        
        // Always load originalScenes from assets for reset functionality
        try {
            val jsonString = loadJSONFromAsset(SCENES_FILENAME)
            val jsonArray = JSONArray(jsonString)
            val assetScenesList = mutableListOf<Scene>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                assetScenesList.add(Scene(
                    id = jsonObject.getInt("id"),
                    title = jsonObject.getString("title"),
                    content = jsonObject.getString("content")
                ))
            }
            originalScenes = assetScenesList.toList() // Keep original scenes for reset
        } catch (e: Exception) {
            e.printStackTrace()
            // Handle error loading original scenes if necessary, though app might be in a bad state
        }
    }

    private fun loadJSONFromAsset(fileName: String): String {
        return assets.open(fileName).bufferedReader().use { it.readText() }
    }

    private fun resetToDefaultScenes() {
        // Reset to original scenes (which are always loaded from assets)
        scenes.clear()
        scenes.addAll(originalScenes) // originalScenes is now reliably from assets

        // Randomize the order of scenes
        scenes.shuffle()

        // Reset ID counter
        nextSceneId = scenes.maxOfOrNull { it.id }?.plus(1) ?: 1
        
        // Delete the custom scenes file from internal storage
        val internalFile = File(filesDir, SCENES_FILENAME)
        if (internalFile.exists()) {
            internalFile.delete()
        }

        // Update UI
        updateEditList()

        // Update favorites list (remove any favorites for deleted scenes)
        val validIds = scenes.map { it.id.toString() }.toSet()
        favorites.removeAll { !validIds.contains(it) }
        saveFavorites()
        updateFavoritesList()

        // If in random mode, display a random scene
        if (currentMode == MODE_RANDOM) {
            displayRandomScene()
        }

        // Show toast
        showMaterialToast(getString(R.string.scenes_reset), true)
    }

    private fun saveScenesToFile() {
        try {
            // Create a JSON array with all scenes
            val jsonArray = JSONArray()

            for (scene in scenes) {
                val jsonObject = JSONObject().apply {
                    put("id", scene.id)
                    put("title", scene.title)
                    put("content", scene.content)
                }
                jsonArray.put(jsonObject)
            }

            // Write to internal storage file
            val file = File(filesDir, SCENES_FILENAME)
            BufferedWriter(FileWriter(file)).use { writer ->
                writer.write(jsonArray.toString(2)) // Pretty print with 2-space indentation
            }

            // Copy to assets for future loads
            // Note: This won't actually work at runtime since assets are read-only
            // For a real app, you'd need to use the internal file as the source of truth

        } catch (e: Exception) {
            e.printStackTrace()
            showMaterialToast("Error saving scenes: ${e.message}", false)
        }
    }

    // Display a random scene and add it to history
    private fun displayRandomScene() {
        if (scenes.isEmpty()) {
            titleTextView.text = getString(R.string.error_loading)
            contentTextView.text = getString(R.string.check_json)
            return
        }

        // Get a random scene that's different from the current one if possible
        var newIndex: Int
        do {
            newIndex = Random.nextInt(scenes.size)
        } while (scenes.size > 1 && newIndex == currentSceneIndex)

        // Update current index
        currentSceneIndex = newIndex

        // Update history - remove any forward history and add the new item
        if (historyPosition < sceneHistory.size - 1) {
            // Remove forward history if we're not at the end
            sceneHistory = sceneHistory.subList(0, historyPosition + 1)
        }

        // Add new scene to history
        sceneHistory.add(currentSceneIndex)
        historyPosition = sceneHistory.size - 1

        // Display the scene
        displayScene(scenes[currentSceneIndex])

        // Update button state
        // previousButton.isEnabled = historyPosition > 0 // Now handled by updatePreviousButtonState
        updatePreviousButtonState()
    }

    /**
     * Display the next scene in sequence and add it to history
     */
    private fun displayNextScene() {
        if (scenes.isEmpty()) {
            titleTextView.text = getString(R.string.error_loading)
            contentTextView.text = getString(R.string.check_json)
            return
        }

        // Move to the next scene, wrapping around if necessary
        val newIndex = (currentSceneIndex + 1) % scenes.size

        // Update current index
        currentSceneIndex = newIndex

        // Update history - remove any forward history and add the new item
        if (historyPosition < sceneHistory.size - 1) {
            // Remove forward history if we're not at the end
            sceneHistory = sceneHistory.subList(0, historyPosition + 1)
        }

        // Add new scene to history
        sceneHistory.add(currentSceneIndex)
        historyPosition = sceneHistory.size - 1

        // Display the scene
        displayScene(scenes[currentSceneIndex])

        // Update button state
        // previousButton.isEnabled = historyPosition > 0 // Now handled by updatePreviousButtonState
        updatePreviousButtonState()
        previousButton.isEnabled = historyPosition > 0
    }

    private fun switchToRandomMode(scene: Scene) {
        // Find the scene index in the main list
        val index = scenes.indexOfFirst { it.id == scene.id }
        if (index != -1) {
            // Update the current index
            currentSceneIndex = index

            // Update history - remove any forward history and add the new item
            if (historyPosition < sceneHistory.size - 1) {
                // Remove forward history if we're not at the end
                sceneHistory = sceneHistory.subList(0, historyPosition + 1)
            }

            // Add new scene to history
            sceneHistory.add(currentSceneIndex)
            historyPosition = sceneHistory.size - 1

            // Then switch to random mode
            currentMode = MODE_RANDOM
            bottomNavigation.selectedItemId = R.id.navigation_random

            // Force UI update immediately to avoid race conditions
            updateUI()

            // Display the selected scene immediately
            displayScene(scenes[currentSceneIndex])

            // Show a toast to confirm the selection
            showMaterialToast(getString(R.string.scene_selected), true)
        }
    }

    private fun displayScene(scene: Scene) {
        // Format both title and content
        val formattedTitle = formatMarkdownText(scene.title)
        val formattedContent = formatMarkdownText(scene.content)

        // Set the formatted title and content
        titleTextView.text = HtmlCompat.fromHtml(formattedTitle, HtmlCompat.FROM_HTML_MODE_COMPACT)
        contentTextView.text = HtmlCompat.fromHtml(formattedContent, HtmlCompat.FROM_HTML_MODE_COMPACT)

        // Refresh the options menu to update the favorite icon
        invalidateOptionsMenu()

        // Update the state of the previous button
        updatePreviousButtonState()
    }

    private fun updatePreviousButtonState() {
        val isFirstSceneInHistory = historyPosition <= 0
        previousButton.isEnabled = !isFirstSceneInHistory
        previousButton.alpha = if (isFirstSceneInHistory) 0.5f else 1.0f
    }

    private fun formatMarkdownText(text: String): String {
        // Convert markdown links [text](url) to HTML links <a href="url">text</a>
        var formattedText = text.replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">$1</a>")

        // Convert newlines to HTML breaks
        formattedText = formattedText.replace("\n", "<br>")

        return formattedText
    }

    private fun shareCurrentScene() {
        if (currentSceneIndex < 0 || currentSceneIndex >= scenes.size) return

        val scene = scenes[currentSceneIndex]
        val shareText = "${scene.title}\n\n${scene.content}"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, scene.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        // Start the chooser activity for sharing
        startActivity(Intent.createChooser(intent, getString(R.string.share_via)))
    }

    private fun addCurrentSceneToPlan() {
        if (currentSceneIndex < 0 || currentSceneIndex >= scenes.size) {
            showMaterialToast("No scene selected to add.", false)
            return
        }
        val currentScene = scenes[currentSceneIndex]

        // Load existing planned items
        val prefs = getSharedPreferences(plannedItemsPrefsName, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(plannedItemsKey, null)
        val typeToken = object : TypeToken<MutableList<PlannedItem>>() {}.type
        val plannedItems: MutableList<PlannedItem> = gson.fromJson(jsonString, typeToken) ?: mutableListOf()

        // Check if scene (by ID) is already planned
        val sceneIdString = currentScene.id.toString() // Scene ID is Int, convert to String for PlannedItem
        val alreadyPlanned = plannedItems.any { it.id == sceneIdString && it.type == "Scene" }

        if (alreadyPlanned) {
            showMaterialToast("'${currentScene.title}' is already in your plan.", false)
        } else {
            val newItem = PlannedItem(
                id = sceneIdString,
                name = currentScene.title,
                type = "Scene",
                details = currentScene.content, // Assuming Scene content can be used as details
                order = plannedItems.size // Assign next order
            )
            plannedItems.add(newItem)
            // Re-calculate order for all items to ensure consistency if items can be removed/reordered elsewhere
            plannedItems.forEachIndexed { index, item -> item.order = index }
            // No need to sort here if PlanNightActivity handles sorting on load/modification

            // Save updated list
            val updatedJsonString = gson.toJson(plannedItems)
            prefs.edit().putString(plannedItemsKey, updatedJsonString).apply()
            showMaterialToast("'${currentScene.title}' added to Plan your Night.", true) // Using true for 'added' style
        }
    }

    private fun showEditDialog(scene: Scene?) {
        // Inflate the custom dialog layout
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_scene, null)

        // Get references to the edit text fields
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.edit_title_input)
        val contentInput = dialogView.findViewById<TextInputEditText>(R.id.edit_content_input)

        // Set initial values if editing an existing scene
        if (scene != null) {
            titleInput.setText(scene.title)
            contentInput.setText(scene.content)
        }

        // Create and show the dialog
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (scene == null) getString(R.string.new_scene) else getString(R.string.edit_scene))
            .setView(dialogView)
            .setPositiveButton(R.string.save, null) // Set to null initially
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .create()

        // Show the dialog
        dialog.show()

        // Override the positive button to prevent automatic dismissal when validation fails
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newTitle = titleInput.text.toString().trim()
            val newContent = contentInput.text.toString().trim()

            // Validate inputs
            if (newTitle.isEmpty()) {
                titleInput.error = "Title cannot be empty"
                return@setOnClickListener
            }

            if (newContent.isEmpty()) {
                contentInput.error = "Content cannot be empty"
                return@setOnClickListener
            }

            // Save the scene
            if (scene == null) {
                // Create new scene
                val newScene = Scene(
                    id = nextSceneId++,
                    title = newTitle,
                    content = newContent
                )
                scenes.add(newScene)
                showMaterialToast(getString(R.string.scene_saved), true)
            } else {
                // Update existing scene
                val index = scenes.indexOfFirst { it.id == scene.id }
                if (index != -1) {
                    scenes[index] = Scene(
                        id = scene.id,
                        title = newTitle,
                        content = newContent
                    )
                    showMaterialToast(getString(R.string.scene_saved), true)

                    // If currently viewing this scene, update the display
                    if (currentMode == MODE_RANDOM && currentSceneIndex == index) {
                        displayScene(scenes[index])
                    }
                }
            }

            // Save scenes to file
            saveScenesToFile()

            // Update adapters
            updateEditList()
            updateFavoritesList()

            // Dismiss the dialog
            dialog.dismiss()
        }
    }

    private fun showDeleteConfirmation(scene: Scene) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage("Are you sure you want to delete \"${scene.title}\"?")
            .setPositiveButton(R.string.delete) { _, _ ->
                // Remove the scene
                scenes.removeAll { it.id == scene.id }

                // Remove from favorites if present
                favorites.remove(scene.id.toString())
                saveFavorites()

                // Update UI
                updateEditList()
                showMaterialToast(getString(R.string.scene_deleted), false)

                // If currently viewing this scene, show another one
                if (currentMode == MODE_RANDOM && currentSceneIndex != -1 &&
                    currentSceneIndex < scenes.size && scenes[currentSceneIndex].id == scene.id) {
                    displayRandomScene()
                }

                // Save scenes to file
                saveScenesToFile()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showResetConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_to_default)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.reset_to_default) { _, _ ->
                resetToDefaultScenes()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}