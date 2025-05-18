package com.velvettouch.nosafeword

import com.velvettouch.nosafeword.DeveloperSettingsActivity.Settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationView
import java.util.Locale
import kotlin.random.Random

class BodyWorshipActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    
    private lateinit var bodyWorshipTextView: TextView
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
    
    // TTS variables
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false
    private var isTtsEnabled = true // Default value, will be updated from preferences
    
    // Voice settings
    private var voicePitch = 1.0f
    private var voiceSpeed = 0.9f
    private var gestureCount = 0 // For accessing developer settings
    
    private var isAutoPlayOn = false
    private var autoPlayTimer: CountDownTimer? = null
    private var minTimeSeconds = 10 // Default minimum time: 10 seconds
    private var maxTimeSeconds = 20 // Default maximum time: 20 seconds
    private val timeOptions = listOf(10, 15, 20, 30, 45, 60, 90, 120) // Time options in seconds
    
    // Lists for generating body worship instructions
    private val actionWords = listOf(
        "Kiss", "Lick", "Touch", "Caress", "Nibble", "Stroke", "Massage", "Trace", 
        "Suck", "Bite", "Adore", "Worship", "Hold", "Gaze at", "Tease", "Play with", 
        "Rub", "Admire", "Press", "Cup"
    )
    
    private val bodyParts = listOf(
        "Neck", "Lips", "Collarbones", "Shoulders", "Breasts", "Nipples", "Waist", 
        "Hips", "Thighs", "Inner thighs", "Back", "Tummy", "Ass", "Cheeks", "Ears", 
        "Feet", "Hands", "Fingers", "Calves", "Pussy"
    )
    
    // Voice settings constants (moved from DeveloperSettingsActivity)
    companion object VoiceSettings {
        const val PREF_VOICE_SETTINGS = "voice_settings"
        const val PREF_VOICE_PITCH = "voice_pitch"
        const val PREF_VOICE_SPEED = "voice_speed"
        
        // Default values
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_SPEED = 0.9f
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_body_worship)
        
        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)
        
        // Load TTS preference
        val sharedPreferences = getSharedPreferences("com.velvettouch.nosafeword_preferences", MODE_PRIVATE)
        isTtsEnabled = sharedPreferences.getBoolean(getString(R.string.pref_tts_enabled_key), true)
        
        // Load voice settings from developer settings
        loadVoiceSettings()
        
        // Initialize views
        bodyWorshipTextView = findViewById(R.id.body_worship_text_view)
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
        supportActionBar?.title = getString(R.string.body_worship)
        
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
                    // Navigate to positions activity
                    drawerLayout.closeDrawer(GravityCompat.START)
                    val intent = Intent(this, PositionsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(intent)
                    finish() // Close this activity
                    true
                }
                R.id.nav_body_worship -> {
                    // Already on body worship page, just close drawer
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
        navigationView.setCheckedItem(R.id.nav_body_worship)
        
        // Remove header view if needed
        if (navigationView.headerCount > 0) {
            navigationView.removeHeaderView(navigationView.getHeaderView(0))
        }
        
        // Set up auto play spinners
        setupSpinners()
        
        // Apply Material 3 dynamic colors
        val typedValue = android.util.TypedValue()
        if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)) {
            randomizeButton.rippleColor = android.content.res.ColorStateList.valueOf(typedValue.data)
            // Set text color to match the button color
            bodyWorshipTextView.setTextColor(typedValue.data)
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
                
                bodyWorshipTextView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayRandomInstruction()
                        bodyWorshipTextView.startAnimation(fadeIn)
                        
                        // Start new timer
                        startAutoPlay()
                    }
                })
            } else {
                // Regular behavior when auto play is off
                val fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in_fast)
                
                bodyWorshipTextView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayRandomInstruction()
                        bodyWorshipTextView.startAnimation(fadeIn)
                    }
                })
            }
        }
        
        // Initialize with consistent Play button text
        autoPlayButton.text = getString(R.string.play)
        autoPlayButton.setOnClickListener {
            toggleAutoPlay()
        }
        
        // Display initial random instruction
        displayRandomInstruction()
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
        minTimeSpinner.setSelection(timeOptions.indexOf(minTimeSeconds).takeIf { it >= 0 } ?: 0) // Default to 10s
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
        maxTimeSpinner.setSelection(timeOptions.indexOf(maxTimeSeconds).takeIf { it >= 0 } ?: 2) // Default to 20s
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
                // Display next random instruction
                val fadeOut = AnimationUtils.loadAnimation(this@BodyWorshipActivity, R.anim.fade_out_fast)
                val fadeIn = AnimationUtils.loadAnimation(this@BodyWorshipActivity, R.anim.fade_in_fast)
                
                bodyWorshipTextView.startAnimation(fadeOut)
                fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        displayRandomInstruction()
                        bodyWorshipTextView.startAnimation(fadeIn)
                        
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
    
    override fun onDestroy() {
        // Shutdown TTS when the activity is destroyed
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language to US English
            val result = textToSpeech.setLanguage(Locale.US)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Text-to-speech language not supported", Toast.LENGTH_SHORT).show()
            } else {
                isTtsReady = true
                
                // Try to set a female voice if available
                val voices = textToSpeech.voices
                var femaleVoiceFound = false
                
                // First, try to find high-quality female voice that supports SSML
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true) && 
                            voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)) {
                            textToSpeech.voice = voice
                            femaleVoiceFound = true
                            break
                        }
                    }
                }
                
                // Next, look for premium/enhanced female voices
                if (!femaleVoiceFound) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true) && 
                            (voice.name.contains("premium", ignoreCase = true) || 
                             voice.name.contains("enhanced", ignoreCase = true) || 
                             voice.name.contains("high", ignoreCase = true))) {
                            textToSpeech.voice = voice
                            femaleVoiceFound = true
                            break
                        }
                    }
                }
                
                // If no premium female voice found, try any female voice
                if (!femaleVoiceFound) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true)) {
                            textToSpeech.voice = voice
                            femaleVoiceFound = true
                            break
                        }
                    }
                }
                
                // Set speech parameters to sound natural
                textToSpeech.setPitch(voicePitch)      // Use saved pitch
                textToSpeech.setSpeechRate(voiceSpeed)  // Use saved speed
            }
        } else {
            Toast.makeText(this, "Failed to initialize text-to-speech", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun displayRandomInstruction() {
        // Select random action word and body part
        val randomAction = actionWords.random()
        val randomBodyPart = bodyParts.random()
        
        // Format the instruction without asterisks: "Kiss her inner thighs"
        val instruction = "$randomAction her $randomBodyPart"
        
        // Display the formatted instruction
        bodyWorshipTextView.text = instruction
        
        // Apply dynamic tint to the randomize button based on your theme
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        randomizeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(typedValue.data)
        bodyWorshipTextView.setTextColor(typedValue.data)
        
        // Add access to developer settings with triple tap on text
        bodyWorshipTextView.setOnClickListener {
            gestureCount++
            if (gestureCount >= 3) {
                openDeveloperSettings()
                gestureCount = 0
            }
            
            // Reset counter after 2 seconds of no clicks
            android.os.Handler(mainLooper).postDelayed({
                if (gestureCount > 0) {
                    gestureCount = 0
                }
            }, 2000)
        }
        
        // Speak the instruction with TTS
        speakNextTask(instruction)
    }
    
    private fun speakNextTask(instruction: String) {
        if (isTtsReady && isTtsEnabled) {
            // Stop any ongoing speech
            if (textToSpeech.isSpeaking) {
                textToSpeech.stop()
            }
            
            // Create a Bundle for speech parameters
            val params = Bundle()
            
            // Simply speak the instruction with a natural voice
            textToSpeech.speak(instruction, TextToSpeech.QUEUE_FLUSH, params, "instruction")
        }
    }
    
    /**
     * Load voice settings from shared preferences
     */
    private fun loadVoiceSettings() {
        val prefs = getSharedPreferences(VoiceSettings.PREF_VOICE_SETTINGS, Context.MODE_PRIVATE)
        voicePitch = prefs.getFloat(VoiceSettings.PREF_VOICE_PITCH, VoiceSettings.DEFAULT_PITCH)
        voiceSpeed = prefs.getFloat(VoiceSettings.PREF_VOICE_SPEED, VoiceSettings.DEFAULT_SPEED)
    }
    
    /**
     * Open the developer settings activity
     */
    private fun openDeveloperSettings() {
        val intent = Intent(this, DeveloperSettingsActivity::class.java)
        startActivity(intent)
    }
}