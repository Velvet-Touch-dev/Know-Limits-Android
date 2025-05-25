package com.velvettouch.nosafeword

import android.app.Activity // Added for Activity.RESULT_CANCELED
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
// CommonStatusCodes import removed if only SIGN_IN_CANCELLED was used and now replaced by its value
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging // Added for FCM Token
import com.velvettouch.nosafeword.data.repository.UserRepository // Added
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber // Added

class WelcomeActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val userRepository = UserRepository() // Added
    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val RC_SIGN_IN = 9002
        private const val TAG = "WelcomeActivityAuth"
        private const val PREFS_NAME = "NoSafeWordPrefs"
        private const val KEY_WELCOME_COMPLETED = "welcomeCompleted"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth

        // Check if welcome has already been completed
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (sharedPreferences.getBoolean(KEY_WELCOME_COMPLETED, false)) {
            // User has completed welcome and is likely signed in.
            // Ensure FCM token is updated if user is signed in.
            auth.currentUser?.let {
                updateFcmTokenForCurrentUser(it.uid)
            }
            navigateToMainActivity(false) // Don't set the flag again if already set
            return
        }

        // Check if user is already signed in (Firebase Auth)
        if (auth.currentUser != null) {
            // If user is signed in, update FCM token, mark welcome as completed and navigate
            updateFcmTokenForCurrentUser(auth.currentUser!!.uid) // Update FCM token
            navigateToMainActivity()
            return // Finish onCreate early
        }

        setContentView(R.layout.activity_welcome)

        // Configure Google Sign In
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail()
        try {
            // It's good practice to fetch the web client ID from strings.xml
            // Ensure R.string.default_web_client_id is correctly set up in your project's Firebase config
            getString(R.string.default_web_client_id).let { gsoBuilder.requestIdToken(it) }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Failed to get R.string.default_web_client_id. Ensure it's in your strings.xml and linked to your Firebase project.", e)
            // Handle this error appropriately - perhaps disable Google Sign-In or show an error
            Toast.makeText(this, "Error configuring sign-in. Please check app setup.", Toast.LENGTH_LONG).show()
        }
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        val signInButton: Button = findViewById(R.id.sign_in_button)
        signInButton.setOnClickListener {
            signIn()
        }

        val skipButton: TextView = findViewById(R.id.skip_button)
        skipButton.setOnClickListener {
            // User chose to skip/continue without login
            navigateToMainActivity()
        }
    }

    private fun signIn() {
        Log.d(TAG, "signIn: Attempting Google Sign-In.")
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            if (resultCode == Activity.RESULT_OK) {
                try {
                    val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                    if (account?.idToken != null) {
                        Log.d(TAG, "Google Sign-In successful, token received.")
                        firebaseAuthWithGoogle(account.idToken!!)
                    } else {
                        Log.w(TAG, "Google Sign-In successful but idToken is null.")
                        Toast.makeText(this, getString(R.string.sign_in_failed_toast) + " (No ID Token)", Toast.LENGTH_LONG).show()
                    }
                } catch (e: ApiException) {
                    // This catch block is now for unexpected errors when resultCode was OK
                    Log.w(TAG, "Google sign in failed despite RESULT_OK: ${e.statusCode}", e)
                    Toast.makeText(this, getString(R.string.sign_in_failed_toast) + " (API Code: ${e.statusCode})", Toast.LENGTH_LONG).show()
                }
            } else {
                // Handle cancellation (resultCode != Activity.RESULT_OK, e.g., Activity.RESULT_CANCELED)
                Log.d(TAG, "Google Sign-In cancelled by user. ResultCode: $resultCode")
                Toast.makeText(this, getString(R.string.sign_in_cancelled_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase Auth successful. User: ${auth.currentUser?.email}")
                    auth.currentUser?.let { firebaseUser ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val profileResult = userRepository.createUserProfileIfNotExists(firebaseUser)
                            if (profileResult.isSuccess) {
                                // Profile created or already exists, now update FCM token
                                updateFcmTokenForCurrentUser(firebaseUser.uid)
                            } else {
                                Timber.tag(TAG).e(profileResult.exceptionOrNull(), "Failed to ensure user profile exists.")
                                // Optionally show a non-blocking error to user on main thread
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@WelcomeActivity, "Profile sync issue.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            // Proceed with navigation regardless of profile/FCM sync outcome for now
                            // to not block the user, but log errors.
                            withContext(Dispatchers.Main) {
                                navigateToMainActivity()
                            }
                        }
                    } ?: run {
                        // Should not happen if task.isSuccessful and currentUser is null
                        Log.w(TAG, "Firebase Auth successful but currentUser is null.")
                        navigateToMainActivity() // Navigate anyway
                    }
                } else {
                    Log.w(TAG, "Firebase Auth failed.", task.exception)
                    Toast.makeText(this, getString(R.string.sign_in_failed_toast) + " (Firebase Auth)", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun navigateToMainActivity(setWelcomeCompletedFlag: Boolean = true) {
        if (setWelcomeCompletedFlag && auth.currentUser != null) { // Only set flag if user is signed in
            val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            with(sharedPreferences.edit()) {
                putBoolean(KEY_WELCOME_COMPLETED, true)
                apply()
            }
            Log.d(TAG, "Welcome completed flag set to true.")
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish() // Finish WelcomeActivity so user can't navigate back to it
    }

    private fun updateFcmTokenForCurrentUser(uid: String) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.tag(TAG).w(task.exception, "Fetching FCM registration token failed")
                return@addOnCompleteListener
            }

            // Get new FCM registration token
            val token = task.result
            Timber.tag(TAG).d("Current FCM Token: $token")

            // Update in Firestore
            CoroutineScope(Dispatchers.IO).launch {
                val updateResult = userRepository.updateFcmToken(uid, token)
                if (updateResult.isSuccess) {
                    Timber.tag(TAG).d("FCM token updated successfully in WelcomeActivity for user $uid")
                } else {
                    Timber.tag(TAG).e(updateResult.exceptionOrNull(), "Failed to update FCM token in WelcomeActivity for user $uid")
                }
            }
        }
    }
}
