package com.example.randomsceneapp

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Bundle
import android.os.CountDownTimer
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
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import java.io.IOException
import kotlin.random.Random

class PositionsActivity : AppCompatActivity() {
    
    private lateinit var positionImageView: ImageView
    private lateinit var positionNameTextView: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var autoPlayButton: MaterialButton
    private lateinit var timerTextView: TextView
    private lateinit var autoPlaySettings: LinearLayout
    private lateinit var minTimeSpinner: Spinner
    private lateinit var maxTimeSpinner: Spinner
    private lateinit var toolbar: Toolbar
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle
    
    private var positionImages: List<String> = emptyList()
    private var currentPosition: Int = -1
    private var isAutoPlayOn = false
    private var autoPlayTimer: CountDownTimer? = null
    private var minTimeSeconds = 30 // Default minimum time in seconds
    private var maxTimeSeconds = 60 // Default maximum time in seconds
    private val timeOptions = listOf(10, 15, 20, 30, 45, 60, 90, 120) // Time options in seconds
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_positions)
        
        // Initialize views
        positionImageView = findViewById(R.id.position_image_view)
        positionNameTextView = findViewById(R.id.position_name_text_view)
        randomizeButton = findViewById(R.id.randomize_button)
        autoPlayButton = findViewById(R.id.auto_play_button)
        timerTextView = findViewById(R.id.timer_text_view)
        autoPlaySettings = findViewById(R.id.auto_play_settings)
        minTimeSpinner = findViewById(R.id.min_time_spinner)
        maxTimeSpinner = findViewById(R.id.max_time_spinner)
        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        
        // Show settings by default initially
        autoPlaySettings.visibility = View.VISIBLE
        
        // Set up toolbar with Material 3 style
        setSupportActionBar(toolbar)
        
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
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish() // Close this activity after starting MainActivity
                    true
                }
                R.id.nav_positions -> {
                    // Already on positions page, just close drawer
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
        randomizeButton.setOnClickListener {
            // If auto play is on, stop current timer and start a new one
            if (isAutoPlayOn) {
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
                        displayRandomPosition()
                        positionImageView.startAnimation(fadeIn)
                        
                        // Start new timer
                        startAutoPlay()
                    }
                })
            } else {
                // Regular behavior when auto play is off
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_fast)
                
                positionImageView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayRandomPosition()
                        positionImageView.startAnimation(fadeIn)
                    }
                })
            }
        }
        
        // Initialize with consistent Play button text
        autoPlayButton.text = getString(R.string.play)
        autoPlayButton.setOnClickListener {
            toggleAutoPlay()
        }
        
        // Load position images
        loadPositionImages()
        
        // Display initial random position
        displayRandomPosition()
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
            // Start auto play
            startAutoPlay()
            
            // Change button text to Pause
            autoPlayButton.text = getString(R.string.pause)
            
            // Show timer
            timerTextView.visibility = View.VISIBLE
            
            // Update layout weights to accommodate timer
            updateButtonLayout()
        } else {
            // Stop auto play
            stopAutoPlay()
            
            // Change button text to Play
            autoPlayButton.text = getString(R.string.play)
            
            // Hide timer
            timerTextView.visibility = View.GONE
            
            // Update layout weights for two-button layout
            updateButtonLayout()
        }
    }
    
    private fun updateButtonLayout() {
        val buttonsLayout = findViewById<LinearLayout>(R.id.buttons_container)
        
        if (timerTextView.visibility == View.VISIBLE) {
            // Three-element layout (Play, Timer, Next)
            for (i in 0 until buttonsLayout.childCount) {
                val view = buttonsLayout.getChildAt(i)
                val params = view.layoutParams as LinearLayout.LayoutParams
                params.weight = 1.0f
                view.layoutParams = params
            }
        } else {
            // Two-element layout (Play, Next)
            autoPlayButton.layoutParams = LinearLayout.LayoutParams(
                0, 
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                1.0f
            ).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.button_margin)
            }
            
            randomizeButton.layoutParams = LinearLayout.LayoutParams(
                0, 
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                1.0f
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.button_margin)
            }
        }
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
                        displayRandomPosition()
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
    
    override fun onPause() {
        super.onPause()
        // Stop auto play when activity is paused
        if (isAutoPlayOn) {
            stopAutoPlay()
            isAutoPlayOn = false
            autoPlayButton.setIconResource(R.drawable.ic_play_24)
            timerTextView.visibility = View.GONE
        }
    }
    
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
    
    private fun displayRandomPosition() {
        if (positionImages.isEmpty()) {
            positionNameTextView.text = "No position images found"
            return
        }
        
        // Get a random position that's different from the current one if possible
        var newIndex: Int
        do {
            newIndex = Random.nextInt(positionImages.size)
        } while (positionImages.size > 1 && newIndex == currentPosition)
        
        currentPosition = newIndex
        val imageName = positionImages[currentPosition]
        
        try {
            // Load and display the image
            val inputStream = assets.open("positions/$imageName")
            val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
            positionImageView.setImageDrawable(drawable)
            
            // Display the image name without the extension
            val nameWithoutExtension = imageName.substringBeforeLast(".")
            positionNameTextView.text = nameWithoutExtension.replace("_", " ").capitalize()
            
            // Apply dynamic tint to the randomize button based on your theme
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
            randomizeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
            
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(
                this, 
                "Error loading image: ${e.message}", 
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    // Helper function to capitalize first letter of each word
    private fun String.capitalize(): String {
        return this.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase() else it.toString() 
            }
        }
    }
}
