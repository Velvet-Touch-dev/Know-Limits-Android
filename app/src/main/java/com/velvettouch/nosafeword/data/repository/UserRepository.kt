package com.velvettouch.nosafeword.data.repository

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.velvettouch.nosafeword.data.model.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.UUID

class UserRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val usersCollection = firestore.collection("users")

    companion object {
        private const val PAIRING_CODE_EXPIRATION_MS = 24 * 60 * 60 * 1000 // 24 hours
    }

    fun getUserProfileFlow(uid: String): Flow<UserProfile?> = callbackFlow {
        val docRef = usersCollection.document(uid)
        val listenerRegistration = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Timber.w(error, "Listen failed for user profile: $uid")
                trySend(null).isFailure // Or close(error)
                return@addSnapshotListener
            }
            val userProfile = snapshot?.toObject(UserProfile::class.java)
            trySend(userProfile).isSuccess
        }
        awaitClose {
            Timber.d("Closing user profile listener for $uid")
            listenerRegistration.remove()
        }
    }

    suspend fun getUserProfile(uid: String): UserProfile? {
        return try {
            usersCollection.document(uid).get().await().toObject(UserProfile::class.java)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching user profile for $uid")
            null
        }
    }

    suspend fun createUserProfileIfNotExists(firebaseUser: FirebaseUser): Result<Unit> {
        val userProfileDoc = usersCollection.document(firebaseUser.uid)
        return try {
            val snapshot = userProfileDoc.get().await()
            if (!snapshot.exists()) {
                // Create a map for the new profile, using FieldValue for timestamps
                val newUserProfileData = mapOf(
                    "uid" to firebaseUser.uid,
                    "email" to firebaseUser.email,
                    "displayName" to firebaseUser.displayName,
                    "pairingCode" to null,
                    "pairingCodeTimestamp" to null,
                    "pairedWith" to null,
                    "role" to null,
                    "fcmToken" to null,
                    "profileCreatedAt" to FieldValue.serverTimestamp(),
                    "lastSeen" to FieldValue.serverTimestamp()
                )
                userProfileDoc.set(newUserProfileData).await()
                Timber.d("Created new user profile for ${firebaseUser.uid}")
            } else {
                // Profile exists, update lastSeen
                val updates = mapOf("lastSeen" to FieldValue.serverTimestamp())
                userProfileDoc.update(updates).await()
                Timber.d("User profile already exists for ${firebaseUser.uid}, updated lastSeen.")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error creating/checking user profile for ${firebaseUser.uid}")
            Result.failure(e)
        }
    }

    suspend fun generatePairingCode(uid: String): Result<String> {
        val newCode = UUID.randomUUID().toString().substring(0, 8).uppercase()
        val userProfileUpdate = mapOf(
            "pairingCode" to newCode,
            "pairingCodeTimestamp" to FieldValue.serverTimestamp(),
            "pairedWith" to null, // Clear previous pairing
            "role" to "Dom" // Initiator is Dom
        )
        return try {
            usersCollection.document(uid).set(userProfileUpdate, SetOptions.merge()).await()
            Timber.d("Generated pairing code $newCode for user $uid")
            Result.success(newCode)
        } catch (e: Exception) {
            Timber.e(e, "Error generating pairing code for $uid")
            Result.failure(e)
        }
    }

    suspend fun findUserByPairingCode(code: String): UserProfile? {
        if (code.isBlank()) return null
        return try {
            val querySnapshot = usersCollection
                .whereEqualTo("pairingCode", code)
                // .whereGreaterThan("pairingCodeTimestamp", Date(System.currentTimeMillis() - PAIRING_CODE_EXPIRATION_MS)) // Optional: check expiry
                .limit(1)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                Timber.d("No user found with pairing code: $code")
                return null
            }
            val userDoc = querySnapshot.documents.first()
            val userProfile = userDoc.toObject(UserProfile::class.java)

            // Optional: Check if pairing code is expired server-side if not done in query
            userProfile?.pairingCodeTimestamp?.let {
                if (System.currentTimeMillis() - it.time > PAIRING_CODE_EXPIRATION_MS) {
                    Timber.d("Pairing code $code for user ${userProfile.uid} has expired.")
                    // Optionally clear the expired code
                    // clearPairingCode(userProfile.uid)
                    return null 
                }
            }
            
            // Check if already paired
            if (userProfile?.pairedWith != null) {
                Timber.d("User ${userProfile.uid} found with code $code is already paired with ${userProfile.pairedWith}.")
                return null // Dom is already paired
            }

            Timber.d("User ${userProfile?.uid} found with pairing code $code")
            userProfile
        } catch (e: Exception) {
            Timber.e(e, "Error finding user by pairing code: $code")
            null
        }
    }

    suspend fun setPairingStatus(domUid: String, subUid: String): Result<Unit> {
        val domProfileUpdate = mapOf(
            "pairedWith" to subUid,
            "role" to "Dom", // Dom's role is confirmed
            "pairingCode" to null, // Clear pairing code after successful pairing
            "pairingCodeTimestamp" to null
        )
        val subProfileUpdate = mapOf(
            "pairedWith" to domUid,
            "role" to "Sub",
            "pairingCode" to null, // Sub might not have had one, but clear just in case
            "pairingCodeTimestamp" to null
        )

        return try {
            firestore.runBatch { batch ->
                batch.set(usersCollection.document(domUid), domProfileUpdate, SetOptions.merge())
                batch.set(usersCollection.document(subUid), subProfileUpdate, SetOptions.merge())
            }.await()
            Timber.d("Successfully paired Dom ($domUid) and Sub ($subUid)")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error setting pairing status between $domUid and $subUid")
            Result.failure(e)
        }
    }

    suspend fun clearPairingStatus(uid1: String, uid2: String?): Result<Unit> {
        val profileUpdate = mapOf(
            "pairedWith" to null,
            "role" to null,
            "pairingCode" to null, // Also clear any pending pairing codes
            "pairingCodeTimestamp" to null
        )
        return try {
            firestore.runBatch { batch ->
                batch.set(usersCollection.document(uid1), profileUpdate, SetOptions.merge())
                uid2?.let { // If a second UID is provided (the partner)
                    batch.set(usersCollection.document(it), profileUpdate, SetOptions.merge())
                }
            }.await()
            Timber.d("Cleared pairing status for $uid1 and $uid2")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error clearing pairing status for $uid1 and $uid2")
            Result.failure(e)
        }
    }
    
    suspend fun clearPairingCode(uid: String): Result<Unit> {
        val update = mapOf(
            "pairingCode" to null,
            "pairingCodeTimestamp" to null
        )
        return try {
            usersCollection.document(uid).set(update, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateFcmToken(uid: String, token: String?): Result<Unit> {
        if (uid.isBlank()) return Result.failure(IllegalArgumentException("UID cannot be blank for FCM token update"))
        val tokenUpdate = mapOf("fcmToken" to token) // Store null if token is null (e.g. on sign out)
        return try {
            usersCollection.document(uid).set(tokenUpdate, SetOptions.merge()).await()
            Timber.d("Updated FCM token for user $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error updating FCM token for $uid")
            Result.failure(e)
        }
    }
}
