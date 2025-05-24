package com.velvettouch.nosafeword

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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase

class WelcomeActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val RC_SIGN_IN = 9002 // Different from MainActivity's to avoid potential conflicts if both are ever active
        private const val TAG = "WelcomeActivityAuth"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth

        // Check if user is already signed in
        if (auth.currentUser != null) {
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
                Log.w(TAG, "Google sign in failed: ${e.statusCode}", e)
                Toast.makeText(this, getString(R.string.sign_in_failed_toast) + " (API Code: ${e.statusCode})", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Firebase Auth successful. User: ${auth.currentUser?.email}")
                    // Sign-in success, update UI accordingly (e.g., navigate to main app screen)
                    navigateToMainActivity()
                } else {
                    Log.w(TAG, "Firebase Auth failed.", task.exception)
                    Toast.makeText(this, getString(R.string.sign_in_failed_toast) + " (Firebase Auth)", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun navigateToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish() // Finish WelcomeActivity so user can't navigate back to it
    }
}
