package com.velvettouch.nosafeword

import android.util.Log // Import Android Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow


class ScenesRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun getScenesCollection() = firestore.collection("scenes")
    private fun getCurrentUserId(): String? = auth.currentUser?.uid

    suspend fun addScene(scene: Scene): Result<Unit> {
        return try {
            val userId = getCurrentUserId()
            if (userId == null) {
                Result.failure(Exception("User not logged in"))
            } else {
                scene.userId = userId
                // If scene.firestoreId is empty, Firestore will generate one.
                // If it's not empty, it will use the provided ID (useful for updates/specific IDs).
                if (scene.firestoreId.isEmpty()) {
                    getScenesCollection().add(scene).await()
                } else {
                    getScenesCollection().document(scene.firestoreId).set(scene).await()
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateScene(scene: Scene): Result<Unit> {
        return try {
            val userId = getCurrentUserId()
            if (userId == null) {
                Result.failure(Exception("User not logged in"))
            } else if (scene.firestoreId.isEmpty()) {
                Result.failure(Exception("Scene has no Firestore ID, cannot update"))
            }
            else {
                scene.userId = userId // Ensure userId is set
                getScenesCollection().document(scene.firestoreId).set(scene).await()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteScene(sceneId: String): Result<Unit> {
        return try {
            val userId = getCurrentUserId()
            if (userId == null) {
                Result.failure(Exception("User not logged in"))
            } else {
                // Optional: You might want to verify the scene belongs to the user before deleting,
                // though Firestore rules should also enforce this.
                getScenesCollection().document(sceneId).delete().await()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object { // Add a companion object for the TAG
        private const val TAG = "ScenesRepository"
    }

    fun getUserScenesFlow(): Flow<List<Scene>> = callbackFlow {
        val userId = getCurrentUserId()
        Log.d(TAG, "getUserScenesFlow called. UserID: $userId")

        if (userId == null) {
            Log.w(TAG, "User not logged in. Emitting empty list and closing flow.")
            trySend(emptyList())
            close(Exception("User not logged in"))
            return@callbackFlow
        }

        Log.d(TAG, "Setting up Firestore listener for userId: $userId")
        val listenerRegistration = getScenesCollection()
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error in Firestore listener: ", error)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    Log.d(TAG, "Snapshot received. Document count: ${snapshot.size()}")
                    val scenes = snapshot.documents.mapNotNull { document ->
                        document.toObject<Scene>()?.apply {
                            firestoreId = document.id
                            // Log each scene being mapped
                            // Log.d(TAG, "Mapping scene: $title, id: $firestoreId, userId: ${this.userId}")
                        }
                    }
                    Log.d(TAG, "Successfully mapped ${scenes.size} scenes. Trying to send to flow.")
                    val sendResult = trySend(scenes)
                    Log.d(TAG, "trySend result: ${sendResult.isSuccess}, isClosedForSend: ${isClosedForSend}")
                    if (!sendResult.isSuccess) {
                        Log.w(TAG, "Failed to send scenes to flow. Channel may be closed.")
                    }
                } else {
                    Log.d(TAG, "Snapshot was null, no error.")
                }
            }
        awaitClose {
            Log.d(TAG, "Flow cancelled, removing listener registration.")
            listenerRegistration.remove()
        }
    }

    suspend fun addDefaultScenesBatch(scenes: List<Scene>): Result<Unit> {
        val userId = getCurrentUserId()
        if (userId == null) {
            return Result.failure(Exception("User not logged in for batch add"))
        }
        return try {
            val batch = firestore.batch()
            val scenesCollection = getScenesCollection()
            scenes.forEach { assetScene ->
                // Create a new Scene object for Firestore, ensuring isCustom is false and userId is set.
                // Also, ensure a new firestoreId is generated by Firestore.
                val sceneForFirestore = Scene(
                    id = assetScene.id, // Keep original ID if it's meaningful (e.g. from JSON)
                    title = assetScene.title,
                    content = assetScene.content,
                    isCustom = false, // Explicitly set default scenes as not custom
                    userId = userId,
                    firestoreId = scenesCollection.document().id // Generate new Firestore ID
                )
                val docRef = scenesCollection.document(sceneForFirestore.firestoreId)
                batch.set(docRef, sceneForFirestore)
            }
            batch.commit().await()
            Log.d(TAG, "Successfully added batch of ${scenes.size} default scenes for user $userId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding default scenes batch for user $userId", e)
            Result.failure(e)
        }
    }

    // Example of getting a single scene if needed
    suspend fun getSceneById(sceneId: String): Result<Scene?> {
        return try {
            val userId = getCurrentUserId()
            if (userId == null) return Result.failure(Exception("User not logged in"))

            val documentSnapshot = getScenesCollection().document(sceneId).get().await()
            val scene = documentSnapshot.toObject<Scene>()?.apply { firestoreId = documentSnapshot.id }

            if (scene != null && scene.userId == userId) {
                Result.success(scene)
            } else if (scene == null) {
                Result.success(null) // Scene not found
            }
            else {
                Result.failure(Exception("User not authorized to access this scene or scene does not exist"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}