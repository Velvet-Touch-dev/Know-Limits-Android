package com.example.randomsceneapp

import android.content.Context
import android.content.Intent
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    
    private lateinit var titleTextView: TextView
    private lateinit var contentTextView: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var sceneCardView: MaterialCardView
    private lateinit var shareButton: FloatingActionButton
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var randomContent: NestedScrollView
    private lateinit var favoritesContainer: FrameLayout
    private lateinit var favoritesRecyclerView: RecyclerView
    private lateinit var emptyFavoritesView: LinearLayout
    
    private var scenes: List<Scene> = emptyList()
    private var currentSceneIndex: Int = -1
    private var favorites: MutableSet<String> = mutableSetOf()
    private var optionsMenu: Menu? = null
    private var currentMode: Int = MODE_RANDOM
    private var currentToast: Toast? = null
    
    private lateinit var favoritesAdapter: FavoriteScenesAdapter
    
    companion object {
        private const val MODE_RANDOM = 0
        private const val MODE_FAVORITES = 1
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
        favoritesRecyclerView = findViewById(R.id.favorites_recycler_view)
        emptyFavoritesView = findViewById(R.id.empty_favorites_view)
        
        // Set up the toolbar
        setSupportActionBar(topAppBar)
        
        // Enable links in the title and content text views
        titleTextView.movementMethod = LinkMovementMethod.getInstance()
        contentTextView.movementMethod = LinkMovementMethod.getInstance()
        
        // Set up RecyclerView with a click handler
        favoritesAdapter = FavoriteScenesAdapter { scene ->
            // Handle favorite item click - Switch to Random mode and display the selected scene
            switchToRandomMode(scene)
        }
        
        favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = favoritesAdapter
        }
        
        // Load favorites from preferences
        loadFavorites()
        
        // Load scenes from JSON file
        loadScenes()
        
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
        
        // Display a random scene initially
        displayRandomScene()
        
        // Update UI based on initial mode
        updateUI()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar, menu)
        this.optionsMenu = menu
        updateFavoriteIcon()
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_favorite -> {
                toggleFavorite()
                updateFavoriteIcon()
                updateFavoritesList()
                true
            }
            R.id.action_settings -> {
                // Settings functionality would go here
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
                // Show random content, hide favorites
                randomContent.visibility = View.VISIBLE
                favoritesContainer.visibility = View.GONE
                shareButton.show()
            }
            MODE_FAVORITES -> {
                // Hide random content, show favorites
                randomContent.visibility = View.GONE
                favoritesContainer.visibility = View.VISIBLE
                updateFavoritesList()
                shareButton.hide()
            }
        }
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
            favoritesAdapter.submitList(favoriteScenes)
        }
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
        
        // Set icon tint based on action
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
    
    private fun updateFavoriteIcon() {
        val favoriteItem = optionsMenu?.findItem(R.id.action_favorite) ?: return
        
        if (currentSceneIndex >= 0 && currentSceneIndex < scenes.size) {
            val scene = scenes[currentSceneIndex]
            val isFavorite = favorites.contains(scene.id.toString())
            
            // Update the icon based on favorite status
            favoriteItem.setIcon(
                if (isFavorite) R.drawable.ic_favorite_filled
                else R.drawable.ic_favorite
            )
        }
    }
    
    private fun loadScenes() {
        try {
            val jsonString = loadJSONFromAsset("scenes.json")
            val jsonArray = JSONArray(jsonString)
            val scenesList = mutableListOf<Scene>()
            
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val scene = Scene(
                    id = jsonObject.getInt("id"),
                    title = jsonObject.getString("title"),
                    content = jsonObject.getString("content")
                )
                scenesList.add(scene)
            }
            
            scenes = scenesList
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
            // First switch modes completely
            currentMode = MODE_RANDOM
            updateUI()
            
            // Set the bottom navigation
            bottomNavigation.selectedItemId = R.id.navigation_random
            
            // Only after UI is updated, set the scene
            currentSceneIndex = index
            displayScene(scene)
        }
    }
    
    private fun displayScene(scene: Scene) {
        // Format both title and content
        val formattedTitle = formatMarkdownText(scene.title)
        val formattedContent = formatMarkdownText(scene.content)
        
        // Set the formatted title and content
        titleTextView.text = HtmlCompat.fromHtml(formattedTitle, HtmlCompat.FROM_HTML_MODE_COMPACT)
        contentTextView.text = HtmlCompat.fromHtml(formattedContent, HtmlCompat.FROM_HTML_MODE_COMPACT)
        
        // Update favorite icon
        updateFavoriteIcon()
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
}
