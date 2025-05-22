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
                        val scene = document.toObject<Scene>()
                        scene?.apply {
                            firestoreId = document.id
                            Log.d(TAG, "Mapping scene: '${title}', id: $firestoreId, originalId: ${this.id}, isCustom: ${this.isCustom}, userId: ${this.userId}")
                        }
                        scene // return the scene or null
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

    suspend fun getExistingDefaultSceneOriginalIds(userId: String): Result<Set<Int>> {
        return try {
            val snapshot = getScenesCollection()
                .whereEqualTo("userId", userId)
                .whereEqualTo("isCustom", false) // Only check against existing default scenes
                .get()
                .await()
            val ids = snapshot.documents.mapNotNull { document ->
                // Safely access the 'id' field, ensuring it's treated as Int (or Long then Int)
                val sceneObject = document.toObject<Scene>()
                sceneObject?.id // Assuming 'id' in Scene class is Int
            }.toSet()
            Log.d(TAG, "Fetched existing default original IDs for user $userId: $ids")
            Result.success(ids)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching existing default scene original IDs for user $userId", e)
            Result.failure(e)
        }
    }

    suspend fun hasDefaultSceneWithOriginalId(originalId: Int, userId: String): Result<Boolean> {
        return try {
            val querySnapshot = getScenesCollection()
                .whereEqualTo("userId", userId)
                .whereEqualTo("id", originalId) // Query by the Int 'id' field
                .whereEqualTo("isCustom", false)
                .limit(1)
                .get()
                .await()
            val exists = !querySnapshot.isEmpty
            Log.d(TAG, "Checked for default scene with originalId $originalId for user $userId. Exists: $exists")
            Result.success(exists)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for default scene with originalId $originalId for user $userId", e)
            Result.failure(e)
        }
    }

    suspend fun getSceneByOriginalId(originalId: Int, userId: String): Result<Scene?> {
        return try {
            val querySnapshot = getScenesCollection()
                .whereEqualTo("userId", userId)
                .whereEqualTo("id", originalId) // Query by the Int 'id' field (original asset ID)
                .limit(1)
                .get()
                .await()

            if (querySnapshot.isEmpty) {
                Log.d(TAG, "No scene found with originalId $originalId for user $userId.")
                Result.success(null)
            } else {
                val document = querySnapshot.documents.first()
                val scene = document.toObject<Scene>()?.apply {
                    firestoreId = document.id // Populate firestoreId from the document
                }
                if (scene != null) {
                    Log.d(TAG, "Found scene '${scene.title}' (FirestoreId: ${scene.firestoreId}) with originalId $originalId for user $userId.")
                    Result.success(scene)
                } else {
                    Log.e(TAG, "Failed to convert document to Scene object for originalId $originalId, user $userId.")
                    Result.failure(Exception("Failed to convert Firestore document to Scene object."))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting scene by originalId $originalId for user $userId", e)
            Result.failure(e)
        }
    }

    suspend fun addDefaultScenesBatch(scenes: List<Scene>): Result<Unit> {
        val userId = getCurrentUserId()
        if (userId == null) {
            Log.w(TAG, "User not logged in. Cannot add default scenes batch.")
            return Result.failure(Exception("User not logged in for batch add"))
        }

        return try {
            val existingOriginalIdsResult = getExistingDefaultSceneOriginalIds(userId)
            if (existingOriginalIdsResult.isFailure) {
                Log.e(TAG, "Failed to get existing default scene IDs for user $userId, aborting batch add.", existingOriginalIdsResult.exceptionOrNull())
                return Result.failure(existingOriginalIdsResult.exceptionOrNull() ?: Exception("Failed to get existing default scene IDs"))
            }
            val existingOriginalIds = existingOriginalIdsResult.getOrNull() ?: emptySet()

            val scenesToActuallySeed = scenes.filter { assetScene ->
                val shouldSeed = !existingOriginalIds.contains(assetScene.id)
                if (!shouldSeed) {
                    Log.d(TAG, "Default scene with originalId ${assetScene.id} ('${assetScene.title}') already exists for user $userId. Skipping from batch.")
                }
                shouldSeed
            }

            if (scenesToActuallySeed.isEmpty()) {
                Log.i(TAG, "No new default scenes to seed for user $userId. All provided scenes seem to exist or list was empty.")
                return Result.success(Unit)
            }

            Log.d(TAG, "Preparing to seed ${scenesToActuallySeed.size} new default scenes for user $userId.")
            val batch = firestore.batch()
            val scenesCollection = getScenesCollection()
            scenesToActuallySeed.forEach { assetScene ->
                val sceneForFirestore = Scene(
                    id = assetScene.id,
                    title = assetScene.title,
                    content = assetScene.content,
                    isCustom = false, // Default scenes are not custom
                    userId = userId,
                    firestoreId = scenesCollection.document().id // Generate new Firestore ID for this new entry
                )
                val docRef = scenesCollection.document(sceneForFirestore.firestoreId)
                batch.set(docRef, sceneForFirestore)
                Log.d(TAG, "Adding scene '${assetScene.title}' (originalId: ${assetScene.id}, new FirestoreId: ${sceneForFirestore.firestoreId}) to batch for user $userId.")
            }
            batch.commit().await()
            Log.i(TAG, "Successfully added batch of ${scenesToActuallySeed.size} new default scenes to Firestore for user $userId")
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

    suspend fun getAllUserScenesOnce(userId: String): Result<List<Scene>> {
        return try {
            if (userId.isEmpty()) {
                Log.w(TAG, "getAllUserScenesOnce called with empty userId.")
                return Result.failure(IllegalArgumentException("User ID cannot be empty"))
            }
            Log.d(TAG, "Fetching all scenes once for userId: $userId")
            val snapshot = getScenesCollection()
                .whereEqualTo("userId", userId)
                .get()
                .await()
            
            val scenes = snapshot.documents.mapNotNull { document ->
                try {
                    document.toObject<Scene>()?.apply {
                        firestoreId = document.id
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting document ${document.id} to Scene", e)
                    null // Skip problematic document
                }
            }
            Log.d(TAG, "Successfully fetched ${scenes.size} scenes once for userId: $userId")
            Result.success(scenes)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAllUserScenesOnce for userId $userId", e)
            Result.failure(e)
        }
    }
}