package com.example.randomsceneapp

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    
    private lateinit var titleTextView: TextView
    private lateinit var contentTextView: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var sceneCardView: MaterialCardView
    private lateinit var shareButton: FloatingActionButton
    private lateinit var addSceneButton: FloatingActionButton
    private lateinit var resetScenesButton: ExtendedFloatingActionButton
    private lateinit var topAppBar: MaterialToolbar
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
    private var favorites: MutableSet<String> = mutableSetOf()
    private var currentMode: Int = MODE_RANDOM
    private var currentToast: Toast? = null
    private var nextSceneId: Int = 1
    
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
        randomizeButton = findViewById(R.id.randomizeButton)
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
        
        // Set up the toolbar
        setSupportActionBar(topAppBar)
        
        // Enable links in the title and content text views
        titleTextView.movementMethod = LinkMovementMethod.getInstance()
        contentTextView.movementMethod = LinkMovementMethod.getInstance()
        
        // Set up RecyclerView for favorites
        favoritesAdapter = FavoriteScenesAdapter { scene ->
            // Handle favorite item click - switching to random mode and displaying the scene
            switchToRandomMode(scene)
        }
        
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
        
        // Set up RecyclerView for edit
        editAdapter = EditScenesAdapter(
            onEditClick = { scene -> showEditDialog(scene) },
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
        originalScenes = scenes.toList()
        
        // Set up bottom navigation
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_random -> {
                    currentMode = MODE_RANDOM
                    updateUI()
                    true
                }
                R.id.navigation_favorites -> {
                    currentMode = MODE_FAVORITES
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
            val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out)
            val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
            
            sceneCardView.startAnimation(fadeOut)
            fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    displayRandomScene()
                    sceneCardView.startAnimation(fadeIn)
                }
            })
        }
        
        // Set share button click listener
        shareButton.setOnClickListener {
            shareCurrentScene()
        }
        
        // Set add scene button click listener
        addSceneButton.setOnClickListener {
            showEditDialog(null) // null indicates creating a new scene
        }
        
        // Set reset scenes button click listener
        resetScenesButton.setOnClickListener {
            showResetConfirmation()
        }
        
        // Display a random scene initially
        displayRandomScene()
        
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
        return when (item.itemId) {
            R.id.action_favorite -> {
                if (currentMode == MODE_RANDOM) {
                    toggleFavorite()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    override fun onPause() {
        // Cancel any showing toast
        currentToast?.cancel()
        super.onPause()
    }
    
    private fun updateUI() {
        when (currentMode) {
            MODE_RANDOM -> {
                // Update app bar title
                topAppBar.title = getString(R.string.app_name)
                
                // Show random content, hide others
                randomContent.visibility = View.VISIBLE
                favoritesContainer.visibility = View.GONE
                editContainer.visibility = View.GONE
                shareButton.show()
                
                // Update toolbar actions
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            }
            MODE_FAVORITES -> {
                // Update app bar title
                topAppBar.title = getString(R.string.favorites)
                
                // Hide random content, show favorites
                randomContent.visibility = View.GONE
                favoritesContainer.visibility = View.VISIBLE
                editContainer.visibility = View.GONE
                updateFavoritesList()
                shareButton.hide()
                
                // Update toolbar actions
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            }
            MODE_EDIT -> {
                // Update app bar title
                topAppBar.title = getString(R.string.edit_scenes)
                
                // Hide random & favorites, show edit
                randomContent.visibility = View.GONE
                favoritesContainer.visibility = View.GONE
                editContainer.visibility = View.VISIBLE
                updateEditList()
                shareButton.hide()
                
                // Update toolbar actions
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
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
        try {
            val jsonString = loadJSONFromAsset(SCENES_FILENAME)
            val jsonArray = JSONArray(jsonString)
            val scenesList = mutableListOf<Scene>()
            var maxId = 0
            
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val id = jsonObject.getInt("id")
                
                // Keep track of the highest ID for new scene creation
                if (id > maxId) {
                    maxId = id
                }
                
                val scene = Scene(
                    id = id,
                    title = jsonObject.getString("title"),
                    content = jsonObject.getString("content")
                )
                scenesList.add(scene)
            }
            
            scenes = scenesList
            nextSceneId = maxId + 1
        } catch (e: Exception) {
            e.printStackTrace()
            titleTextView.text = getString(R.string.error_loading)
            contentTextView.text = "${getString(R.string.check_json)}: ${e.message}"
        }
    }
    
    private fun loadJSONFromAsset(fileName: String): String {
        val inputStream = assets.open(fileName)
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()
        var line: String?
        
        while (bufferedReader.readLine().also { line = it } != null) {
            stringBuilder.append(line)
        }
        
        bufferedReader.close()
        return stringBuilder.toString()
    }
    
    private fun resetToDefaultScenes() {
        // Reset to original scenes
        scenes.clear()
        scenes.addAll(originalScenes)
        
        // Reset ID counter
        nextSceneId = scenes.maxOfOrNull { it.id }?.plus(1) ?: 1
        
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
        
        currentSceneIndex = newIndex
        displayScene(scenes[currentSceneIndex])
    }
    
    private fun switchToRandomMode(scene: Scene) {
        // Find the scene index in the main list
        val index = scenes.indexOfFirst { it.id == scene.id }
        if (index != -1) {
            // First update the current index
            currentSceneIndex = index
            
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
