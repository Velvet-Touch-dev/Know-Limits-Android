package com.velvettouch.nosafeword

import android.app.Activity // Added for Activity.RESULT_CANCELED
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging // Added for FCM Token
import com.google.android.material.button.MaterialButton
import android.view.MenuItem
// import android.widget.Button // No longer needed if MaterialButton is used everywhere
import android.widget.EditText // Added for pairing code input
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import android.content.ClipboardManager
import android.content.ClipData
import android.view.View // Added for View
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible // Added for easy visibility toggling
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.FrameLayout // Added for loading overlay
import com.google.android.material.navigation.NavigationView
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.velvettouch.nosafeword.LocalFavoritesRepository
import com.velvettouch.nosafeword.FavoritesRepository
import com.velvettouch.nosafeword.Favorite
import com.velvettouch.nosafeword.ScenesRepository // Added
import com.velvettouch.nosafeword.PositionsRepository // Added
import com.velvettouch.nosafeword.data.repository.UserRepository // Added
import com.velvettouch.nosafeword.data.model.UserProfile // Added
import com.google.android.material.textfield.TextInputLayout // Added
import kotlinx.coroutines.flow.collectLatest // Added
import timber.log.Timber // Added
import kotlinx.coroutines.withContext // Added for coroutine context switching
import kotlinx.coroutines.Dispatchers // Added for coroutine dispatchers
// Assuming Scene and PositionItem data classes are in com.velvettouch.nosafeword or imported
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson // Added for JSON
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat // Added for date formatting
import java.util.Date // Added for date
import android.net.Uri // Added for Uri


class SettingsActivity : BaseActivity(), TextToSpeech.OnInitListener {

    private val userRepository by lazy { UserRepository() }
    private val localFavoritesRepository by lazy { LocalFavoritesRepository(applicationContext) }
    private val cloudFavoritesRepository by lazy { FavoritesRepository() }
    private val scenesRepository by lazy { ScenesRepository(applicationContext) } // Added
    private val positionsRepository by lazy { PositionsRepository(applicationContext) } // Added
    private lateinit var scenesViewModel: ScenesViewModel // Added

    // Data Export/Import
    private lateinit var exportDataButton: MaterialButton // Assuming R.id.export_data_button
    private lateinit var importDataButton: MaterialButton // Assuming R.id.import_data_button
    private lateinit var exportDataLauncher: ActivityResultLauncher<Intent>
    private lateinit var importDataLauncher: ActivityResultLauncher<Intent>
    private val gson = Gson() // For JSON operations

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private var settingsChanged = false // Flag to track if theme/color settings changed
    // private var pendingNavigationRunnable: Runnable? = null // No longer needed

    // Extracted NavigationItemSelectedListener
    private val navItemSelectedListener = NavigationView.OnNavigationItemSelectedListener { menuItem ->
        Log.i(TAG, "NavigationView.OnNavigationItemSelectedListener: Item selected: ${menuItem.title}, ID: ${menuItem.itemId}") // Log item selection

        val intent: Intent? = when (menuItem.itemId) {
            R.id.nav_scenes -> Intent(this, MainActivity::class.java)
            R.id.nav_positions -> Intent(this, PositionsActivity::class.java)
            R.id.nav_body_worship -> Intent(this, BodyWorshipActivity::class.java)
            R.id.nav_favorites -> Intent(this, FavoritesActivity::class.java)
            R.id.nav_task_list -> Intent(this, TaskListActivity::class.java)
            R.id.nav_plan_night -> Intent(this, PlanNightActivity::class.java)
            R.id.nav_settings -> {
                Log.d(TAG, "Navigation: Already on SettingsActivity or re-selected.")
                null // No intent needed, just close drawer
            }
            else -> {
                Log.w(TAG, "Navigation: Unknown item ID: ${menuItem.itemId}")
                null
            }
        }

        var shouldFinishActivity = false
        if (intent != null) {
            Log.d(TAG, "Navigation: Preparing to start activity for ${intent.component?.shortClassName}")
            startActivity(intent)
            // Determine if SettingsActivity should be finished
            if (menuItem.itemId != R.id.nav_settings && intent.component?.className != this@SettingsActivity::class.java.name) {
                // Only finish if it's a distinct main section
                val mainSections = setOf(R.id.nav_scenes, R.id.nav_positions, R.id.nav_body_worship, R.id.nav_favorites, R.id.nav_task_list, R.id.nav_plan_night)
                if (mainSections.contains(menuItem.itemId)) {
                    shouldFinishActivity = true
                }
            }
        }

        drawerLayout.closeDrawer(GravityCompat.START) // Close drawer

        if (shouldFinishActivity) {
            Log.d(TAG, "Navigation: Finishing SettingsActivity.")
            finish()
        }
        
        true // Indicate the event was handled
    }

    // TTS for previewing voice
    private lateinit var textToSpeech: TextToSpeech
    private var isTtsReady = false

    // Firebase Auth
    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInButton: MaterialButton

    // Pairing UI Elements
    private lateinit var pairingSettingsCard: MaterialCardView
    private lateinit var pairingStatusText: TextView
    private lateinit var generatePairingCodeButton: MaterialButton
    private lateinit var pairingCodeInputLayout: TextInputLayout
    private lateinit var pairingCodeEditText: EditText
    private lateinit var connectAsSubButton: MaterialButton
    private lateinit var unpairButton: MaterialButton
    private lateinit var copyPairingCodeButton: ImageButton // Added for copy button
    private lateinit var domSectionTitle: TextView // Added for Dom section title
    private lateinit var subSectionTitle: TextView // Added for Sub section title
    private lateinit var pairingDivider: View      // Added for divider
    private lateinit var loadingOverlay: FrameLayout // Added for loading overlay

    private var currentUserProfile: UserProfile? = null


    companion object {
        private const val RC_SIGN_IN_SETTINGS = 9002 // Differentiate from MainActivity's RC_SIGN_IN
        private const val TAG = "SettingsActivityAuth"

        // Theme mode constants
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val THEME_SYSTEM = 0
        
        // Color palette constants
        const val COLOR_DEFAULT = 0
        const val COLOR_PURPLE = 1
        const val COLOR_PINK = 2
        const val COLOR_JUST_BLACK = 3
        const val COLOR_PITCH_BLACK = 4

        private const val KEY_SETTINGS_CHANGED = "key_settings_changed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Restore settingsChanged state if activity was recreated (e.g., after theme change)
        savedInstanceState?.let {
            settingsChanged = it.getBoolean(KEY_SETTINGS_CHANGED, false)
        }

        scenesViewModel = ViewModelProvider(this).get(ScenesViewModel::class.java) // Initialize ViewModel
        
        // Initialize TextToSpeech for voice preview
        textToSpeech = TextToSpeech(this, this)

        // Set up toolbar with navigation icon
        val toolbar = findViewById<Toolbar>(R.id.settings_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.settings) // Set toolbar title

        // Set up drawer components
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        
        // Set up ActionBarDrawerToggle
        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        )
        // No longer need to override onDrawerClosed for pendingNavigationRunnable
        drawerToggle.isDrawerIndicatorEnabled = true
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Remove header view
        navigationView.removeHeaderView(navigationView.getHeaderView(0))

        // Set up navigation view listener
        setupNavigationListener() // Call a separate function to set up the listener

        // Set up theme selection
        setupThemeSelector()
        
        // Set up voice settings card
        setupVoiceSettingsCard()
        
        // Set up voice instructions toggle
        setupVoiceInstructionsToggle()

        // Initialize Firebase Auth
        auth = Firebase.auth

        // Configure Google Sign-In
        var webClientId: String? = null
        try {
            webClientId = getString(R.string.default_web_client_id)
            Log.d(TAG, "onCreate: Successfully retrieved default_web_client_id.")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Failed to retrieve R.string.default_web_client_id. Check google-services.json and Gradle sync.", e)
            Toast.makeText(this, "Error: Missing default_web_client_id for Sign-In.", Toast.LENGTH_LONG).show()
        }

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        if (webClientId != null) {
            gsoBuilder.requestIdToken(webClientId)
        } else {
            Log.w(TAG, "onCreate: webClientId is null. Google Sign-In might not function correctly for Firebase auth.")
        }
        val gso = gsoBuilder.build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Setup Google Sign-In Button
        googleSignInButton = findViewById(R.id.google_sign_in_button_settings)
        updateAuthButtonUI() // Set initial state of the button

        // Initialize Pairing UI Elements
        pairingSettingsCard = findViewById(R.id.pairing_settings_card)
        pairingStatusText = findViewById(R.id.pairing_status_text)
        generatePairingCodeButton = findViewById(R.id.generate_pairing_code_button)
        pairingCodeInputLayout = findViewById(R.id.pairing_code_input_layout)
        pairingCodeEditText = findViewById(R.id.pairing_code_edit_text)
        connectAsSubButton = findViewById(R.id.connect_as_sub_button)
        unpairButton = findViewById(R.id.unpair_button)
        copyPairingCodeButton = findViewById(R.id.copy_pairing_code_button) 
        domSectionTitle = findViewById(R.id.dom_section_title)         // Initialize Dom title
        subSectionTitle = findViewById(R.id.sub_section_title)         // Initialize Sub title
        pairingDivider = findViewById(R.id.pairing_divider)             // Initialize divider
        loadingOverlay = findViewById(R.id.loading_overlay) 

        setupPairingClickListeners()
        observeUserProfile()

        // Setup Data Export/Import
        // These buttons (R.id.export_data_button, R.id.import_data_button) need to be added to activity_settings.xml
        // For now, we'll assume they exist and proceed with setup.
        // If they don't exist, findViewById will return null and cause a crash later.
        // This will be addressed when UI elements are confirmed/added to XML.
        try {
            exportDataButton = findViewById(R.id.export_data_button)
            importDataButton = findViewById(R.id.import_data_button)
            setupDataManagementSection()
        } catch (e: Exception) {
            Log.e(TAG, "Export/Import buttons not found in layout. Ensure they are added to R.layout.activity_settings.", e)
            // Optionally disable functionality or show a message if buttons are critical
        }
        initializeActivityLaunchers()

        // Setup custom back press handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (settingsChanged) {
                    // Theme or color has changed, go back to MainActivity and ensure it refreshes
                    val intent = Intent(this@SettingsActivity, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    finish() // Finish SettingsActivity
                } else {
                    // If no custom action, disable this callback and let default behavior proceed
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showLoadingOverlay() {
        runOnUiThread {
            Log.d(TAG, "Showing loading overlay.") // Log updated
            // loadingOverlay.visibility = View.VISIBLE // Overlay visibility change removed
            // loadingOverlay.isClickable = true  // Kept commented as per previous steps
            // loadingOverlay.isFocusable = true // Kept commented as per previous steps

            // Disable export/import buttons when overlay is shown for them
            if (::exportDataButton.isInitialized) exportDataButton.isEnabled = false
            if (::importDataButton.isInitialized) importDataButton.isEnabled = false
        }
    }

    private fun hideLoadingOverlay() {
        runOnUiThread { 
            Log.d(TAG, "Hiding loading overlay.") // Log updated
            // loadingOverlay.visibility = View.GONE // Overlay visibility change removed
            // loadingOverlay.isClickable = false // Kept commented
            // loadingOverlay.isFocusable = false // Kept commented

            // Re-enable export/import buttons when overlay is hidden
            if (::exportDataButton.isInitialized) exportDataButton.isEnabled = true
            if (::importDataButton.isInitialized) importDataButton.isEnabled = true

            // Removed all explicit re-enabling/unlocking/listener re-application logic
            // as the primary approach is now to not disable UI elements in the first place.
            // if (::navigationView.isInitialized) {
            //     navigationView.isEnabled = true
            //     Log.d(TAG, "NavigationView explicitly re-enabled.")
            // }
            // if (::drawerLayout.isInitialized) {
            //     drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            //     Log.d(TAG, "DrawerLayout explicitly unlocked.")
            // }
            // if (::navigationView.isInitialized) {
            //     navigationView.setNavigationItemSelectedListener(null) 
            //     navigationView.setNavigationItemSelectedListener(navItemSelectedListener) 
            //     Log.d(TAG, "NavigationView listener re-applied.")
            // }

            Log.d(TAG, "Loading overlay hidden. No UI elements were explicitly disabled/re-enabled by this overlay logic.")
        }
    }

    private fun setupNavigationListener() {
        Log.d(TAG, "setupNavigationListener: Attempting to set NavigationItemSelectedListener.")
        
        // Log state of menu items before setting listener
        if (::navigationView.isInitialized) {
            Log.d(TAG, "setupNavigationListener: Checking menu item states before setting listener.")
            for (i in 0 until navigationView.menu.size()) {
                val item = navigationView.menu.getItem(i)
                Log.d(TAG, "Menu item '${item.title}': isEnabled=${item.isEnabled}, isVisible=${item.isVisible}")
                if (item.hasSubMenu()) {
                    item.subMenu?.let { subMenu -> // Safe call for subMenu
                        for (j in 0 until subMenu.size()) {
                            val subItem = subMenu.getItem(j)
                            Log.d(TAG, "  SubMenu item '${subItem.title}': isEnabled=${subItem.isEnabled}, isVisible=${subItem.isVisible}")
                        }
                    }
                }
            }
        }

        navigationView.setNavigationItemSelectedListener(null) // Clear existing
        navigationView.setNavigationItemSelectedListener(navItemSelectedListener) // Use the extracted listener
        Log.d(TAG, "setupNavigationListener: NavigationItemSelectedListener SET successfully.")
    }
    
    override fun onDestroy() {
        // Clean up TTS resources
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
            }
        } else {
            Toast.makeText(this, "Failed to initialize text-to-speech", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        // Sync the toggle state after onRestoreInstanceState has occurred
        drawerToggle.syncState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the settingsChanged flag if it's true, so it persists across recreation
        if (settingsChanged) {
            outState.putBoolean(KEY_SETTINGS_CHANGED, settingsChanged)
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Log.d(TAG, "onOptionsItemSelected called with item: ${item.title}, id: ${item.itemId}")
        // Check if the item is the action bar home/up button (hamburger icon)
        // Note: For ActionBarDrawerToggle, item.itemId might not be android.R.id.home if the toggle handles it.
        // The drawerToggle.onOptionsItemSelected(item) is the primary check.
        if (item.itemId == android.R.id.home && (::drawerToggle.isInitialized && !drawerToggle.isDrawerIndicatorEnabled)) {
             Log.d(TAG, "Standard home button selected in onOptionsItemSelected (drawer indicator disabled).");
        } else if (::drawerToggle.isInitialized && drawerToggle.isDrawerIndicatorEnabled) {
            Log.d(TAG, "Processing potential drawer toggle click in onOptionsItemSelected.");
        }

        if (::drawerToggle.isInitialized && drawerToggle.onOptionsItemSelected(item)) {
            Log.d(TAG, "DrawerToggle handled onOptionsItemSelected.")
            return true
        }
        Log.d(TAG, "DrawerToggle DID NOT handle onOptionsItemSelected or was not initialized. Passing to super.")
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        // Update the selected menu item in the drawer
        navigationView.setCheckedItem(R.id.nav_settings)
        
        // Update voice settings display
        try {
            val voiceSettingsValue = findViewById<TextView>(R.id.voice_settings_value)
            updateVoiceSettingsDisplay(voiceSettingsValue)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Update Auth button UI state in onResume as well
        updateAuthButtonUI()
        // Re-check user profile on resume in case of external changes, though flow should cover it.
        // observeUserProfile() // Might be redundant if flow is robust
    }

    private fun observeUserProfile() {
        lifecycleScope.launch {
            auth.currentUser?.uid?.let { uid ->
                userRepository.getUserProfileFlow(uid).collectLatest { userProfile ->
                    currentUserProfile = userProfile
                    updatePairingUI()
                }
            } ?: run {
                // No user logged in, ensure pairing UI is hidden/disabled
                currentUserProfile = null
                updatePairingUI()
            }
        }
        // Also listen to auth state changes to re-trigger profile observation
        auth.addAuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser == null) {
                currentUserProfile = null
                updatePairingUI()
            } else {
                // User logged in or changed, re-observe
                lifecycleScope.launch {
                    firebaseAuth.currentUser?.uid?.let { uid ->
                        userRepository.getUserProfileFlow(uid).collectLatest { userProfile ->
                            currentUserProfile = userProfile
                            updatePairingUI()
                        }
                    }
                }
            }
        }
    }

    private fun updatePairingUI() {
        val isSignedIn = auth.currentUser != null
        pairingSettingsCard.isVisible = isSignedIn

        if (!isSignedIn) {
            pairingStatusText.text = "Sign in to manage pairing."
            return
        }

        currentUserProfile?.let { profile ->
            if (profile.pairedWith != null) {
                // User is paired
                lifecycleScope.launch {
                    val partnerProfile = userRepository.getUserProfile(profile.pairedWith!!)
                    val partnerName = partnerProfile?.displayName ?: partnerProfile?.email ?: profile.pairedWith
                    pairingStatusText.text = "Paired with: ${partnerName}\nYour Role: ${profile.role ?: "N/A"}"
                }
                generatePairingCodeButton.isVisible = false
                pairingCodeInputLayout.isVisible = false
                connectAsSubButton.isVisible = false
                unpairButton.isVisible = true
                copyPairingCodeButton.isVisible = false
                domSectionTitle.isVisible = false
                subSectionTitle.isVisible = false
                pairingDivider.isVisible = false
            } else if (profile.pairingCode != null) {
                // User has an active pairing code (is Dom waiting for Sub)
                pairingStatusText.text = "Your Pairing Code: ${profile.pairingCode}\nShare this with your Sub. Waiting for connection..."
                generatePairingCodeButton.isVisible = false // Or make it "Cancel Pairing Code"
                pairingCodeInputLayout.isVisible = false
                connectAsSubButton.isVisible = false
                unpairButton.isVisible = true // Allow cancelling the pairing process/code
                copyPairingCodeButton.isVisible = true
                domSectionTitle.isVisible = true // Show Dom title as generate button is conceptually there (though hidden for "Cancel")
                subSectionTitle.isVisible = false
                pairingDivider.isVisible = false // No Sub section visible
            } else {
                // User is not paired and has no active code - Show both sections
                pairingStatusText.text = "Status: Not Paired"
                generatePairingCodeButton.isVisible = true
                pairingCodeInputLayout.isVisible = true
                connectAsSubButton.isVisible = true
                unpairButton.isVisible = false
                copyPairingCodeButton.isVisible = false
                domSectionTitle.isVisible = true
                subSectionTitle.isVisible = true
                pairingDivider.isVisible = true
            }
        } ?: run {
            // Profile not loaded yet or user just signed out
            pairingStatusText.text = "Loading pairing status..."
            generatePairingCodeButton.isVisible = false
            pairingCodeInputLayout.isVisible = false
            connectAsSubButton.isVisible = false
            unpairButton.isVisible = false
            copyPairingCodeButton.isVisible = false
            domSectionTitle.isVisible = false
            subSectionTitle.isVisible = false
            pairingDivider.isVisible = false
        }
    }

    private fun setupPairingClickListeners() {
        generatePairingCodeButton.setOnClickListener {
            auth.currentUser?.uid?.let { uid ->
                lifecycleScope.launch {
                    val result = userRepository.generatePairingCode(uid)
                    if (result.isSuccess) {
                        Toast.makeText(this@SettingsActivity, "Pairing code generated!", Toast.LENGTH_SHORT).show()
                        // UI will update via observer
                    } else {
                        Toast.makeText(this@SettingsActivity, "Failed to generate code: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        connectAsSubButton.setOnClickListener {
            val code = pairingCodeEditText.text.toString().trim()
            if (code.isEmpty()) {
                pairingCodeInputLayout.error = "Code cannot be empty"
                return@setOnClickListener
            }
            pairingCodeInputLayout.error = null
            auth.currentUser?.uid?.let { subUid ->
                lifecycleScope.launch {
                    val domProfile = userRepository.findUserByPairingCode(code)
                    if (domProfile == null) {
                        Toast.makeText(this@SettingsActivity, "Invalid or expired pairing code, or Dom already paired.", Toast.LENGTH_LONG).show()
                    } else if (domProfile.uid == subUid) {
                        Toast.makeText(this@SettingsActivity, "You cannot pair with yourself.", Toast.LENGTH_LONG).show()
                    }
                    else {
                        val pairResult = userRepository.setPairingStatus(domProfile.uid, subUid)
                        if (pairResult.isSuccess) {
                            Toast.makeText(this@SettingsActivity, "Successfully paired with ${domProfile.displayName ?: domProfile.email ?: "Dom"}!", Toast.LENGTH_LONG).show()
                            pairingCodeEditText.text?.clear()
                            // UI will update via observer
                        } else {
                            Toast.makeText(this@SettingsActivity, "Pairing failed: ${pairResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        unpairButton.setOnClickListener {
            currentUserProfile?.let { profile ->
                val partnerUid = profile.pairedWith
                lifecycleScope.launch {
                    val unpairResult = userRepository.clearPairingStatus(profile.uid, partnerUid)
                    if (unpairResult.isSuccess) {
                        Toast.makeText(this@SettingsActivity, "Successfully unpaired.", Toast.LENGTH_SHORT).show()
                        // UI will update via observer
                    } else {
                        Toast.makeText(this@SettingsActivity, "Unpairing failed: ${unpairResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } ?: run { // Also handles cancelling a generated pairing code if not yet paired
                 auth.currentUser?.uid?.let { uid ->
                     lifecycleScope.launch {
                         userRepository.clearPairingCode(uid) // Clears only pairingCode and timestamp
                         Toast.makeText(this@SettingsActivity, "Pairing code cancelled.", Toast.LENGTH_SHORT).show()
                     }
                 }
            }
        }

        copyPairingCodeButton.setOnClickListener {
            currentUserProfile?.pairingCode?.let { codeToCopy ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Pairing Code", codeToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Pairing code copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }
 
    // Removed deprecated override fun onBackPressed()

    private fun setupThemeSelector() {
        try {
            val themeCard = findViewById<MaterialCardView>(R.id.theme_card)
            
            // Get current theme and color settings
            val currentTheme = loadThemeSetting()
            val currentColor = loadColorSetting()
            
            // Setup theme selector dialog
            themeCard.setOnClickListener {
                // Inflate the dialog layout
                val dialogView = layoutInflater.inflate(R.layout.dialog_theme_settings, null)
                val themeRadioGroup = dialogView.findViewById<RadioGroup>(R.id.theme_radio_group)
                val colorRadioGroup = dialogView.findViewById<RadioGroup>(R.id.color_radio_group)
                
                // Set the current theme option
                when (currentTheme) {
                    THEME_LIGHT -> themeRadioGroup.check(R.id.theme_light_radio)
                    THEME_DARK -> themeRadioGroup.check(R.id.theme_dark_radio)
                    THEME_SYSTEM -> themeRadioGroup.check(R.id.theme_system_radio)
                }
                
                // Set the current color palette option
                when (currentColor) {
                    COLOR_PURPLE -> colorRadioGroup.check(R.id.color_purple_radio)
                    COLOR_JUST_BLACK -> colorRadioGroup.check(R.id.color_just_black_radio)
                    COLOR_PITCH_BLACK -> colorRadioGroup.check(R.id.color_pitch_black_radio)
                    else -> colorRadioGroup.check(R.id.color_default_radio) // Default is now pink
                }
                
                // Show/hide "Just Black" option based on the selected theme
                val justBlackRadio = dialogView.findViewById<android.widget.RadioButton>(R.id.color_just_black_radio)
                val justBlackDivider = dialogView.findViewById<android.view.View>(R.id.just_black_divider)
                adjustJustBlackVisibility(justBlackRadio, justBlackDivider, themeRadioGroup.checkedRadioButtonId)
                
                // Listen for theme changes to update Just Black visibility
                themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                    adjustJustBlackVisibility(justBlackRadio, justBlackDivider, checkedId)
                    
                    // If switching to light mode and a black theme is selected, reset to default color
                    if (checkedId == R.id.theme_light_radio) {
                        if (colorRadioGroup.checkedRadioButtonId == R.id.color_just_black_radio ||
                            colorRadioGroup.checkedRadioButtonId == R.id.color_pitch_black_radio) {
                            colorRadioGroup.check(R.id.color_default_radio)
                        }
                    }
                }
                
                // Create and show dialog
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.theme_settings)
                    .setView(dialogView)
                    .setPositiveButton(R.string.save) { _, _ ->
                        // Get selected theme
                        val selectedTheme = when (themeRadioGroup.checkedRadioButtonId) {
                            R.id.theme_light_radio -> THEME_LIGHT
                            R.id.theme_dark_radio -> THEME_DARK
                            R.id.theme_system_radio -> THEME_SYSTEM
                            else -> THEME_SYSTEM // Default
                        }
                        
                        // Get selected color palette
                        val selectedColor = when (colorRadioGroup.checkedRadioButtonId) {
                            R.id.color_purple_radio -> COLOR_PURPLE
                            R.id.color_just_black_radio -> COLOR_JUST_BLACK
                            R.id.color_pitch_black_radio -> COLOR_PITCH_BLACK
                            else -> COLOR_DEFAULT
                        }
                        
                        // Save and apply settings if changed
                        val themeChanged = selectedTheme != currentTheme
                        val colorChanged = selectedColor != currentColor
                        
                        if (themeChanged) {
                            saveThemeSetting(selectedTheme)
                        }
                        
                        if (colorChanged) {
                            saveColorSetting(selectedColor)
                        }
                        
                        if (themeChanged || colorChanged) {
                            settingsChanged = true // Mark that settings have changed
                            // Apply theme and color changes
                            applyThemeAndColor(selectedTheme, selectedColor)
                            
                            // Update the theme display text
                            updateThemeSelection(selectedTheme, selectedColor)
                            
                            // Show toast if color palette changed
                            if (colorChanged) {
                                Toast.makeText(this, R.string.color_palette_changed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            
            // Initial update of the theme display
            updateThemeSelection(currentTheme, currentColor)
        } catch (e: Exception) {
            // Just log the exception and continue
            e.printStackTrace()
        }
    }
    
    private fun adjustJustBlackVisibility(justBlackRadio: android.widget.RadioButton, justBlackDivider: android.view.View, themeRadioId: Int) {
        // Show Just Black and Pitch Black options for Dark Mode or System Default
        val visibility = if (themeRadioId == R.id.theme_dark_radio || themeRadioId == R.id.theme_system_radio) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        
        justBlackRadio.visibility = visibility
        justBlackDivider.visibility = visibility
        
        // Get the Pitch Black options too
        val pitchBlackRadio = (justBlackRadio.parent as RadioGroup).findViewById<android.widget.RadioButton>(R.id.color_pitch_black_radio)
        val pitchBlackDivider = (justBlackDivider.parent as android.view.ViewGroup).findViewById<android.view.View>(R.id.pitch_black_divider)
        
        // Also control visibility of Pitch Black option
        pitchBlackRadio.visibility = visibility
        pitchBlackDivider.visibility = visibility
        
        // Clear the checked state when hiding - fixes the selection issue
        if (visibility == android.view.View.GONE) {
            if (justBlackRadio.isChecked) {
                justBlackRadio.isChecked = false
            }
            if (pitchBlackRadio.isChecked) {
                pitchBlackRadio.isChecked = false
            }
        }
    }

    private fun updateThemeSelection(themeMode: Int, colorMode: Int) {
        try {
            val themeValue = findViewById<TextView>(R.id.theme_value)
            
            // Get the theme mode string
            val themeModeString = when (themeMode) {
                THEME_LIGHT -> getString(R.string.theme_light)
                THEME_DARK -> getString(R.string.theme_dark)
                else -> getString(R.string.theme_system)
            }
            
            // Get the color mode string
            val colorModeString = when (colorMode) {
                COLOR_PURPLE -> getString(R.string.color_purple)
                COLOR_JUST_BLACK -> "Void Purple"
                COLOR_PITCH_BLACK -> "Pitch Black"
                else -> getString(R.string.color_default) // Default is now pink
            }
            
            // Combine them
            if (colorMode == COLOR_DEFAULT) {
                themeValue.text = themeModeString
            } else {
                val coloredThemeText = "$themeModeString - $colorModeString"
                themeValue.text = coloredThemeText
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Load the saved theme setting from preferences
     */
    private fun loadThemeSetting(): Int {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getInt("theme_mode", THEME_SYSTEM) // Default to system theme
    }

    /**
     * Save the theme setting to preferences
     */
    private fun saveThemeSetting(themeMode: Int) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("theme_mode", themeMode).apply()
    }
    
    /**
     * Load the saved color palette setting from preferences
     */
    private fun loadColorSetting(): Int {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getInt("color_mode", COLOR_DEFAULT) // Default to default colors
    }
    
    /**
     * Save the color palette setting to preferences
     */
    private fun saveColorSetting(colorMode: Int) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("color_mode", colorMode).apply()
    }

    /**
     * Apply the selected theme and color palette
     */
    private fun applyThemeAndColor(themeMode: Int, colorMode: Int) {
        when (themeMode) {
            THEME_LIGHT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                // If a black theme was selected with light mode, save default color as a safeguard.
                // This ensures the saved state is consistent.
                if (colorMode == COLOR_JUST_BLACK || colorMode == COLOR_PITCH_BLACK) {
                    saveColorSetting(COLOR_DEFAULT) // Override saved color to default
                }
            }
            THEME_DARK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            THEME_SYSTEM -> {
                if (colorMode == COLOR_JUST_BLACK || colorMode == COLOR_PITCH_BLACK) {
                    // If System Default is chosen with a black theme, force Dark Mode
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    // Otherwise, follow system for System Default
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
        }

        // Recreate the activity to apply theme and color changes immediately for SettingsActivity
        // The settingsChanged flag will be saved in onSaveInstanceState and restored in onCreate
        recreate()
    }
    
    private fun setupVoiceSettingsCard() {
        try {
            val voiceSettingsCard = findViewById<MaterialCardView>(R.id.voice_settings_card)
            val voiceSettingsValue = findViewById<TextView>(R.id.voice_settings_value)
            
            // Update the display with current settings
            updateVoiceSettingsDisplay(voiceSettingsValue)
            
            // Set click listener to launch voice settings
            voiceSettingsCard.setOnClickListener {
                val intent = Intent(this, DeveloperSettingsActivity::class.java)
                startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateVoiceSettingsDisplay(textView: TextView) {
        try {
            // Load current voice settings
            val prefs = getSharedPreferences(DeveloperSettingsActivity.Settings.PREF_VOICE_SETTINGS, Context.MODE_PRIVATE)
            val pitch = prefs.getFloat(DeveloperSettingsActivity.Settings.PREF_VOICE_PITCH, 
                                      DeveloperSettingsActivity.Settings.DEFAULT_PITCH)
            val speed = prefs.getFloat(DeveloperSettingsActivity.Settings.PREF_VOICE_SPEED, 
                                      DeveloperSettingsActivity.Settings.DEFAULT_SPEED)
            
            // Format and display
            val displayText = String.format("Pitch: %.2f, Speed: %.2f", pitch, speed)
            textView.text = displayText
        } catch (e: Exception) {
            e.printStackTrace()
            textView.text = "Adjust voice pitch and speed"
        }
    }
    private fun setupVoiceInstructionsToggle() {
        // Try to find a card named voice_card and set up toggle
        try {
            val voiceCard = findViewById<MaterialCardView>(R.id.voice_card)
            val voiceSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.voice_switch)
            
            // Get current TTS setting
            val ttsEnabled = isTtsEnabled()
            voiceSwitch.isChecked = ttsEnabled
            
            // Set up click listener for the card
            voiceCard.setOnClickListener {
                voiceSwitch.toggle()
                saveTtsSetting(voiceSwitch.isChecked)
            }
            
            // Set up listener for the switch
            voiceSwitch.setOnCheckedChangeListener { _, isChecked ->
                saveTtsSetting(isChecked)
            }
            
        } catch (e: Exception) {
            // Just log the exception and continue
            e.printStackTrace()
        }
    }
    
    private fun isTtsEnabled(): Boolean {
        val prefs = getSharedPreferences("com.velvettouch.nosafeword_preferences", Context.MODE_PRIVATE)
        return prefs.getBoolean(getString(R.string.pref_tts_enabled_key), true) // Default to enabled
    }
    
    private fun saveTtsSetting(enabled: Boolean) {
        val prefs = getSharedPreferences("com.velvettouch.nosafeword_preferences", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(getString(R.string.pref_tts_enabled_key), enabled).apply()
    }

    private fun signIn() {
        Log.d(TAG, "signIn: Attempting to launch Google Sign-In flow from Settings.")
        showLoadingOverlay() // Show loading overlay
        if (::googleSignInButton.isInitialized) googleSignInButton.isEnabled = false // Disable button
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN_SETTINGS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        if (requestCode == RC_SIGN_IN_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                try {
                    val account = task.getResult(ApiException::class.java)!!
                    Log.d(TAG, "onActivityResult: Google Sign-In successful from Settings, token: ${account.idToken?.take(10)}...")
                    // firebaseAuthWithGoogle will handle hiding the overlay and re-enabling button on completion
                    firebaseAuthWithGoogle(account.idToken!!)
                } catch (e: ApiException) {
                    Log.w(TAG, "Google sign in failed from Settings despite RESULT_OK", e)
                    Toast.makeText(this, getString(R.string.sign_in_failed_toast) + " (API Code: ${e.statusCode})", Toast.LENGTH_LONG).show()
                    hideLoadingOverlay() // Hide overlay on failure
                    if (::googleSignInButton.isInitialized) googleSignInButton.isEnabled = true // Re-enable button
                }
            } else {
                // Handle cancellation (resultCode != Activity.RESULT_OK, e.g., Activity.RESULT_CANCELED)
                Log.d(TAG, "Google Sign-In cancelled by user from Settings. ResultCode: $resultCode")
                Toast.makeText(this, getString(R.string.sign_in_cancelled_toast), Toast.LENGTH_SHORT).show()
                hideLoadingOverlay() // Hide overlay on cancellation
                if (::googleSignInButton.isInitialized) googleSignInButton.isEnabled = true // Re-enable button
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        Log.d(TAG, "firebaseAuthWithGoogle: Attempting Firebase auth with Google token from Settings.")
        // Loading overlay is already shown by signIn(). Button is already disabled.
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    Log.d(TAG, "firebaseAuthWithGoogle: Firebase Authentication successful from Settings. User: ${user?.email}")
                    Toast.makeText(this, "Sign-In Successful: ${user?.displayName ?: user?.email}", Toast.LENGTH_LONG).show()

                    user?.let { firebaseUser ->
                        // The hideLoadingOverlay and updateAuthButtonUI will be called within this scope
                        // to ensure they run after profile creation/check.
                        lifecycleScope.launch { 
                            val profileResult = userRepository.createUserProfileIfNotExists(firebaseUser)
                            if (profileResult.isSuccess) {
                                // Profile created or already exists, now update FCM token
                                updateFcmTokenForCurrentUser(firebaseUser.uid)
                            } else {
                                Timber.tag(TAG).e(profileResult.exceptionOrNull(), "Failed to ensure user profile exists from Settings.")
                                Toast.makeText(this@SettingsActivity, "Profile sync issue.", Toast.LENGTH_SHORT).show()
                            }
                    // Ensure these UI updates run on the main thread after coroutine work
                    withContext(Dispatchers.Main) {
                        hideLoadingOverlay()
                        updateAuthButtonUI() // Update button to "Sign Out" and re-enables it
                    }
                }
            } ?: run {
                // User is null even after successful task, should not happen but handle defensively
                // Ensure UI updates run on the main thread
                runOnUiThread {
                    hideLoadingOverlay()
                    updateAuthButtonUI() // Re-enables button
                }
            }
        } else {
            Log.w(TAG, "firebaseAuthWithGoogle: Firebase Authentication failed from Settings.", task.exception)
            Toast.makeText(this, "Firebase Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
            // Ensure UI updates run on the main thread
            runOnUiThread {
                hideLoadingOverlay() // Hide overlay on failure
                updateAuthButtonUI() // Ensure button is "Sign In" and re-enables it
            }
        }
    }
}

private fun signOut() {
    Log.d(TAG, "signOut: Attempting to sign out.")
    showLoadingOverlay() // Show loading overlay
    if (::googleSignInButton.isInitialized) googleSignInButton.isEnabled = false // Disable button
    lifecycleScope.launch {
        val user = auth.currentUser
        if (user != null) {
                Log.d(TAG, "Backing up cloud favorites to local before sign-out for user ${user.uid}")
                val backupResult = backupCloudDataToLocal()
                if (backupResult.isSuccess) {
                    Log.d(TAG, "Cloud data successfully backed up to local.")
                } else {
                    Log.w(TAG, "Failed to back up cloud data to local. Proceeding with sign-out anyway.", backupResult.exceptionOrNull())
                    Toast.makeText(this@SettingsActivity, "Note: Could not save cloud data locally before sign out.", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.d(TAG, "No user logged in, no cloud data to back up. Clearing local favorites.")
                // If no user was logged in, but signOut was somehow called, ensure local is clean.
                // This case might be rare if UI prevents sign-out when not signed-in.
                localFavoritesRepository.clearAllLocalFavorites()
            }

            // Clear local data BEFORE Firebase sign out, so ViewModel sees clean state when auth changes.
            LocalFavoritesRepository(applicationContext).clearAllLocalFavorites()
            Log.i(TAG, "Cleared all local favorites and scenes data from SharedPreferences before sign-out.")
            
            val appPrefs = applicationContext.getSharedPreferences("NoSafeWordAppPrefs", Context.MODE_PRIVATE)
            appPrefs.edit().remove("deleted_logged_out_scene_ids").apply()
            Log.i(TAG, "Cleared locally deleted default scene IDs from SharedPreferences before sign-out.")

            // Firebase sign out - this will trigger auth state listeners
            auth.signOut()

            // Google sign out
            googleSignInClient.signOut().addOnCompleteListener(this@SettingsActivity) { googleSignOutTask ->
                Log.d(TAG, "signOut: Google Sign-Out complete. Success: ${googleSignOutTask.isSuccessful}")
                // Firebase sign out is already done.
                // Toast and UI update are handled after both.
            // Ensure UI updates run on the main thread
            runOnUiThread {
                Toast.makeText(this@SettingsActivity, "Signed Out", Toast.LENGTH_SHORT).show()
                hideLoadingOverlay() // Hide overlay after all sign out operations
                updateAuthButtonUI() // Update button to "Sign In" and re-enables it
            }
        }
    }
}

    // New suspend function in SettingsActivity
    private suspend fun backupCloudDataToLocal(): Result<Unit> {
        // auth.currentUser should be checked by the caller (signOut method)
        // This function uses auth.currentUser internally via cloudFavoritesRepository
        Log.d(TAG, "backupCloudDataToLocal: Fetching cloud data for current user.")

        val currentUserId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in for backup"))

        // Clear all previous local data first
        localFavoritesRepository.clearAllLocalFavorites()
        Log.d(TAG, "Cleared all local data before backup.")

        var overallSuccess = true
        var errorMessage = ""

        // 1. Backup Favorite IDs
        val cloudFavoritesResult = cloudFavoritesRepository.getCloudFavoritesListSnapshot() // This is the first, correct call
        if (cloudFavoritesResult.isSuccess) {
            val cloudFavorites = cloudFavoritesResult.getOrNull()
            if (!cloudFavorites.isNullOrEmpty()) {
                Log.d(TAG, "Fetched ${cloudFavorites.size} cloud favorites for backup.")
                cloudFavorites.forEach { favorite ->
                    when (favorite.itemType?.lowercase()) { // Use lowercase for robust matching
                        "scene" -> localFavoritesRepository.addLocalFavoriteScene(favorite.itemId)
                        "position" -> localFavoritesRepository.addLocalFavoritePosition(favorite.itemId)
                        // Add other itemTypes if they exist for favorites, e.g., "custom_scene", "custom_position"
                        // For now, assuming custom items are favorited with "scene" or "position" type
                        // and their specific data is handled by scene/position backup below.
                        else -> Log.w(TAG, "Unknown favorite itemType during backup: ${favorite.itemType} for itemId ${favorite.itemId}")
                    }
                }
                Log.d(TAG, "Finished populating local favorite IDs from cloud backup.")
            } else {
                Log.d(TAG, "No cloud favorites found to back up (IDs).")
            }
        } else {
            overallSuccess = false
            errorMessage += "Failed to backup favorite IDs. "
            Log.e(TAG, "Failed to fetch cloud favorites for backup", cloudFavoritesResult.exceptionOrNull())
        }

        // 2. Backup Scene Data (all scenes for the user)
        val allUserScenesResult = scenesRepository.getAllUserScenesOnce(currentUserId)
        if (allUserScenesResult.isSuccess) {
            val userScenes = allUserScenesResult.getOrNull()
            if (!userScenes.isNullOrEmpty()) {
                localFavoritesRepository.saveLocalScenes(userScenes)
                Log.d(TAG, "Successfully backed up ${userScenes.size} scenes to local.")
            } else {
                Log.d(TAG, "No scenes found for user $currentUserId to back up.")
                localFavoritesRepository.saveLocalScenes(emptyList()) // Save empty list if none found
            }
        } else {
            overallSuccess = false
            errorMessage += "Failed to backup scene data. "
            Log.e(TAG, "Failed to fetch user scenes for backup", allUserScenesResult.exceptionOrNull())
        }

        // 3. Backup Custom Position Data (non-asset positions for the user)
        val allUserPositionsResult = positionsRepository.getAllUserPositionsOnce(currentUserId)
        if (allUserPositionsResult.isSuccess) {
            val userPositions = allUserPositionsResult.getOrNull()
            if (!userPositions.isNullOrEmpty()) {
                val customPositions = userPositions.filter { !it.isAsset }
                localFavoritesRepository.saveLocalCustomPositions(customPositions)
                Log.d(TAG, "Successfully backed up ${customPositions.size} custom positions to local.")
            } else {
                Log.d(TAG, "No positions found for user $currentUserId to back up (for custom positions).")
                localFavoritesRepository.saveLocalCustomPositions(emptyList()) // Save empty list
            }
        } else {
            overallSuccess = false
            errorMessage += "Failed to backup custom position data. "
            Log.e(TAG, "Failed to fetch user positions for backup", allUserPositionsResult.exceptionOrNull())
        }

        return if (overallSuccess) {
            Log.d(TAG, "Cloud data backup to local completed successfully.")
            Result.success(Unit)
        } else {
            Log.e(TAG, "Cloud data backup to local failed: $errorMessage")
            Result.failure(Exception("Backup failed: $errorMessage"))
        }
    }

    private fun updateAuthButtonUI() {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // User is signed in
            googleSignInButton.text = "Sign Out (${currentUser.displayName ?: currentUser.email})"
            googleSignInButton.setOnClickListener {
                Log.d(TAG, "Sign Out button clicked.")
                signOut()
            }
            // Optionally change icon for sign out
            // googleSignInButton.icon = ContextCompat.getDrawable(this, R.drawable.ic_sign_out) // Example
        } else {
            // User is signed out
            googleSignInButton.text = "Sign in with Google"
            googleSignInButton.setOnClickListener {
                Log.d(TAG, "Sign In button clicked.")
                signIn()
            }
            // Optionally change icon back for sign in
            googleSignInButton.setIconResource(R.drawable.googleg_standard_color_18) // Assuming this is your sign-in icon
        }
        if (::googleSignInButton.isInitialized) googleSignInButton.isEnabled = true // Ensure button is enabled when UI updates
    }

    private fun updateFcmTokenForCurrentUser(uid: String) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.tag(TAG).w(task.exception, "Fetching FCM registration token failed in SettingsActivity")
                return@addOnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result
            Timber.tag(TAG).d("Current FCM Token (SettingsActivity): $token")

            // Update in Firestore
            lifecycleScope.launch(Dispatchers.IO) { // Use lifecycleScope for coroutines in Activity
                val updateResult = userRepository.updateFcmToken(uid, token)
                if (updateResult.isSuccess) {
                    Timber.tag(TAG).d("FCM token updated successfully in SettingsActivity for user $uid")
                } else {
                    Timber.tag(TAG).e(updateResult.exceptionOrNull(), "Failed to update FCM token in SettingsActivity for user $uid")
                }
            }
        }
    }

    // --- Data Export/Import Methods ---

    private fun setupDataManagementSection() {
        // These IDs R.id.export_data_button and R.id.import_data_button must exist in R.layout.activity_settings
        // If they were not found in onCreate, these lines will also fail or do nothing.
        if (::exportDataButton.isInitialized) {
            exportDataButton.setOnClickListener {
                handleExportData()
            }
        }
        if (::importDataButton.isInitialized) {
            importDataButton.setOnClickListener {
                handleImportData()
            }
        }
    }

    private fun initializeActivityLaunchers() {
        exportDataLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { uri ->
                    performFileExport(uri)
                }
            }
        }

        importDataLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { uri ->
                    performFileImport(uri)
                }
            }
        }
    }

    private fun handleExportData() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            // Suggest a filename
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            putExtra(Intent.EXTRA_TITLE, "NoSafeWord_Export_$timeStamp.json")
        }
        try {
            exportDataLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error launching file picker for export.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error launching exportDataLauncher", e)
        }
    }

    private fun handleImportData() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json" // Or */* if you want to allow any file type and validate later
        }
        try {
            importDataLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error launching file picker for import.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error launching importDataLauncher", e)
        }
    }

    private fun performFileExport(uri: Uri) {
        lifecycleScope.launch {
            showLoadingOverlay() // Show some progress indicator
            val exporter = DataExporter(applicationContext, scenesViewModel, positionsRepository, localFavoritesRepository, cloudFavoritesRepository, auth)
            val exportData = exporter.generateExportData()

            if (exportData == null) {
                hideLoadingOverlay()
                Toast.makeText(this@SettingsActivity, "Failed to generate export data.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val jsonString = gson.toJson(exportData)

            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonString.toByteArray())
                }
                Toast.makeText(this@SettingsActivity, "Data exported successfully.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing export file", e)
                Toast.makeText(this@SettingsActivity, "Error saving export file.", Toast.LENGTH_LONG).show()
            } finally {
                hideLoadingOverlay()
            }
        }
    }

    private fun performFileImport(uri: Uri) {
        lifecycleScope.launch {
            showLoadingOverlay()
            var jsonString: String? = null
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    jsonString = inputStream.bufferedReader().use(BufferedReader::readText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading import file", e)
                Toast.makeText(this@SettingsActivity, "Error reading import file.", Toast.LENGTH_LONG).show()
                hideLoadingOverlay()
                return@launch
            }

            if (jsonString == null) {
                Toast.makeText(this@SettingsActivity, "Failed to read data from file.", Toast.LENGTH_LONG).show()
                hideLoadingOverlay()
                return@launch
            }

            val importer = DataImporter(applicationContext, scenesViewModel, positionsRepository, localFavoritesRepository, cloudFavoritesRepository, auth)
            val success = importer.importDataFromJson(jsonString!!)

            if (success) {
                Toast.makeText(this@SettingsActivity, "Data imported successfully. Please restart the app if changes are not immediately visible.", Toast.LENGTH_LONG).show()
                // Data might be stale in ViewModels, ideally they should be refreshed or observe changes.
                // Forcing a refresh or relying on LiveData/Flow updates is key.
                // A simple way is to re-fetch or re-trigger observers if ViewModels support it.
                // For now, a toast message is a placeholder.
            } else {
                Toast.makeText(this@SettingsActivity, "Failed to import data.", Toast.LENGTH_LONG).show()
            }
            hideLoadingOverlay()
        }
    }
}
