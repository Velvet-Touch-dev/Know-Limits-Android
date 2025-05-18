package com.velvettouch.nosafeword

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class DeveloperSettingsActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object Settings {
        // Keys for storing voice settings
        const val PREF_VOICE_SETTINGS = "voice_settings"
        const val PREF_VOICE_PITCH = "voice_pitch"
        const val PREF_VOICE_SPEED = "voice_speed"
        
        // Default values
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_SPEED = 0.7f
    }
    
    // UI elements
    private lateinit var pitchSeekbar: SeekBar
    private lateinit var speedSeekbar: SeekBar
    private lateinit var pitchValueText: TextView
    private lateinit var speedValueText: TextView
    private lateinit var testText: EditText
    private lateinit var testButton: Button
    private lateinit var resetButton: Button
    private lateinit var saveButton: Button
    
    // TTS engine
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false
    
    // Current settings
    private var currentPitch = DEFAULT_PITCH
    private var currentSpeed = DEFAULT_SPEED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_developer_settings)
        
        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)
        
        // Set up UI components
        pitchSeekbar = findViewById(R.id.pitch_seekbar)
        speedSeekbar = findViewById(R.id.speed_seekbar)
        pitchValueText = findViewById(R.id.pitch_value)
        speedValueText = findViewById(R.id.speed_value)
        testText = findViewById(R.id.test_text)
        testButton = findViewById(R.id.test_button)
        resetButton = findViewById(R.id.reset_button)
        saveButton = findViewById(R.id.save_button)
        
        // Load current settings
        loadSettings()
        
        // Set up initial UI state
        updatePitchText()
        updateSpeedText()
        
        // Set up seekbar listeners
        pitchSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Map from 0.5 to 1.5 (covering the range of useful pitch values)
                currentPitch = 0.5f + (progress / 20.0f)
                updatePitchText()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        
        speedSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Map from 0.5 to 1.5 (covering the range of useful speed values)
                currentSpeed = 0.5f + (progress / 20.0f)
                updateSpeedText()
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        
        // Set up button listeners
        testButton.setOnClickListener {
            testVoice()
        }
        
        resetButton.setOnClickListener {
            resetToDefaults()
        }
        
        saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Voice settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    override fun onDestroy() {
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
                
                // First, try to find high-quality female voice
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true) && 
                            (voice.name.contains("premium", ignoreCase = true) || 
                             voice.name.contains("enhanced", ignoreCase = true))) {
                            textToSpeech.voice = voice
                            femaleVoiceFound = true
                            break
                        }
                    }
                }
                
                // If no high-quality female voice, try any female voice
                if (!femaleVoiceFound) {
                    for (voice in voices) {
                        if (voice.name.contains("female", ignoreCase = true)) {
                            textToSpeech.voice = voice
                            break
                        }
                    }
                }
                
                // Apply current settings
                applyVoiceSettings()
            }
        } else {
            Toast.makeText(this, "Failed to initialize text-to-speech", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updatePitchText() {
        pitchValueText.text = String.format("%.2f", currentPitch)
    }
    
    private fun updateSpeedText() {
        speedValueText.text = String.format("%.2f", currentSpeed)
    }
    
    private fun applyVoiceSettings() {
        textToSpeech.setPitch(currentPitch)
        textToSpeech.setSpeechRate(currentSpeed)
    }
    
    private fun testVoice() {
        if (isTtsReady) {
            // Stop any ongoing speech
            if (textToSpeech.isSpeaking) {
                textToSpeech.stop()
            }
            
            // Apply current settings
            applyVoiceSettings()
            
            // Get text to speak
            val textToSpeak = testText.text.toString().trim()
            if (textToSpeak.isNotEmpty()) {
                // Speak the text
                textToSpeech.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "test")
            } else {
                Toast.makeText(this, "Please enter text to test", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Text-to-speech is not ready", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences(Settings.PREF_VOICE_SETTINGS, Context.MODE_PRIVATE)
        currentPitch = prefs.getFloat(Settings.PREF_VOICE_PITCH, Settings.DEFAULT_PITCH)
        currentSpeed = prefs.getFloat(Settings.PREF_VOICE_SPEED, Settings.DEFAULT_SPEED)
        
        // Set seekbar positions
        pitchSeekbar.progress = ((currentPitch - 0.5f) * 20).toInt()
        speedSeekbar.progress = ((currentSpeed - 0.5f) * 20).toInt()
    }
    
    private fun resetToDefaults() {
        // Reset to default values
        currentPitch = Settings.DEFAULT_PITCH
        currentSpeed = Settings.DEFAULT_SPEED
        
        // Update UI
        pitchSeekbar.progress = ((currentPitch - 0.5f) * 20).toInt()
        speedSeekbar.progress = ((currentSpeed - 0.5f) * 20).toInt()
        
        // Update text display
        updatePitchText()
        updateSpeedText()
        
        // Apply settings to TTS
        applyVoiceSettings()
        
        // Give feedback
        Toast.makeText(this, "Reset to default settings", Toast.LENGTH_SHORT).show()
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences(Settings.PREF_VOICE_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(Settings.PREF_VOICE_PITCH, currentPitch)
            .putFloat(Settings.PREF_VOICE_SPEED, currentSpeed)
            .apply()
    }
}