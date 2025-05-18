package com.example.randomsceneapp

import android.os.Bundle
import android.view.MenuItem
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import java.io.IOException
import kotlin.random.Random

class PositionsActivity : AppCompatActivity() {
    
    private lateinit var positionImageView: ImageView
    private lateinit var positionNameTextView: TextView
    private lateinit var randomizeButton: MaterialButton
    private lateinit var toolbar: Toolbar
    
    private var positionImages: List<String> = emptyList()
    private var currentPosition: Int = -1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_positions)
        
        // Initialize views
        positionImageView = findViewById(R.id.position_image_view)
        positionNameTextView = findViewById(R.id.position_name_text_view)
        randomizeButton = findViewById(R.id.randomize_button)
        toolbar = findViewById(R.id.toolbar)
        
        // Set up toolbar with Material 3 style
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.positions)
        
        // Apply Material 3 dynamic colors
        val typedValue = android.util.TypedValue()
        if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, typedValue, true)) {
            randomizeButton.rippleColor = android.content.res.ColorStateList.valueOf(typedValue.data)
        }
        
        // Load position images
        loadPositionImages()
        
        // Set up randomize button with Material motion
        randomizeButton.setOnClickListener {
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
                }
            })
        }
        
        // Display initial random position
        displayRandomPosition()
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    private fun loadPositionImages() {
        try {
            positionImages = assets.list("positions")?.filter { 
                it.endsWith(".jpg", ignoreCase = true) || 
                it.endsWith(".jpeg", ignoreCase = true) 
            } ?: emptyList()
            
            if (positionImages.isEmpty()) {
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
