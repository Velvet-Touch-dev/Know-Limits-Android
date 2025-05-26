package com.velvettouch.nosafeword

import android.content.Context
import android.util.Log // Import Android Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.firebase.firestore.QuerySnapshot
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import com.velvettouch.nosafeword.data.repository.UserNotLoggedInException // Import the exception


class ScenesRepository(private val applicationContext: Context) { // Added context

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
        val initialUserId = getCurrentUserId()
        Log.d(TAG, "getUserScenesFlow called. Initial UserID: $initialUserId")

        if (initialUserId == null) {
            Log.w(TAG, "User not logged in at flow collection. Emitting empty list and closing flow.")
            trySend(emptyList())
            channel.close() // Close without error for "not logged in"
            return@callbackFlow
        }

        Log.d(TAG, "User $initialUserId. Setting up listener and auth monitor for scenes.")

        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser?.uid != initialUserId) {
                Log.w(TAG, "User changed or logged out ($initialUserId -> ${firebaseAuth.currentUser?.uid}). Closing scenes flow for $initialUserId.")
                if (!channel.isClosedForSend) {
                    channel.close() // This will trigger awaitClose
                }
            }
        }
        auth.addAuthStateListener(authListener)
        
        // Priming read (optional, but good for immediate UI update)
        // For simplicity, we'll rely on the listener for the first data emission.
        // If a priming read is desired, it would go here, similar to FavoritesRepository.

        if (channel.isClosedForSend) {
            Log.d(TAG, "getUserScenesFlow: Channel closed by auth listener for $initialUserId before listener registration.")
            auth.removeAuthStateListener(authListener) // Clean up immediately
            return@callbackFlow
        }

        Log.d(TAG, "Setting up Firestore listener for scenes, userId: $initialUserId")
        val listenerRegistration = getScenesCollection()
            .whereEqualTo("userId", initialUserId)
            .addSnapshotListener { snapshot, error ->
                if (channel.isClosedForSend) {
                    Log.d(TAG, "Scenes SnapshotListener for $initialUserId: Channel already closed. Ignoring event.")
                    return@addSnapshotListener
                }

                if (auth.currentUser?.uid != initialUserId) {
                    Log.w(TAG, "Scenes SnapshotListener for $initialUserId received event, but user is now ${auth.currentUser?.uid}. Closing channel.")
                    if (!channel.isClosedForSend) channel.close()
                    return@addSnapshotListener
                }

                if (error != null) {
                    Log.e(TAG, "Error in Firestore scenes listener for $initialUserId: ", error)
                    if (!channel.isClosedForSend) channel.close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    Log.d(TAG, "Scenes snapshot received for $initialUserId. Document count: ${snapshot.size()}")
                    val scenes = snapshot.documents.mapNotNull { document ->
                        val scene = document.toObject<Scene>()
                        scene?.apply {
                            firestoreId = document.id
                            // Log.d(TAG, "Mapping scene: '${title}', id: $firestoreId, originalId: ${this.id}, isCustom: ${this.isCustom}, userId: ${this.userId}")
                        }
                        scene
                    }
                    // Log.d(TAG, "Successfully mapped ${scenes.size} scenes. Trying to send to flow for $initialUserId.")
                    if (!channel.isClosedForSend) {
                        val sendResult = trySend(scenes)
                        if (!sendResult.isSuccess) {
                            Log.w(TAG, "Failed to send scenes to flow for $initialUserId. Channel may be closed. isClosedForSend: ${channel.isClosedForSend}")
                        }
                    }
                } else {
                    Log.d(TAG, "Scenes snapshot was null for $initialUserId, no error.")
                    if (!channel.isClosedForSend) trySend(emptyList())
                }
            }

        awaitClose {
            Log.d(TAG, "Scenes flow for $initialUserId cancelled/closed, removing listener registration and auth monitor.")
            listenerRegistration.remove()
            auth.removeAuthStateListener(authListener)
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

    suspend fun addOrUpdateScenesBatch(scenesToProcess: List<Scene>): Result<Unit> {
        val userId = getCurrentUserId()
        if (userId == null) {
            Log.w(TAG, "User not logged in. Cannot process scenes batch.")
            return Result.failure(UserNotLoggedInException("User not logged in for batch scene operation"))
        }
        if (scenesToProcess.isEmpty()) {
            Log.d(TAG, "addOrUpdateScenesBatch: Empty list provided, nothing to do.")
            return Result.success(Unit)
        }

        return try {
            val batch = firestore.batch()
            val scenesCollection = getScenesCollection()

            scenesToProcess.forEach { scene ->
                val sceneWithUser = scene.copy(userId = userId) // Ensure correct userId
                val docRef = if (sceneWithUser.firestoreId.isBlank() || sceneWithUser.firestoreId.startsWith("local_")) {
                    // New scene or local scene being promoted, Firestore will generate ID if we use .document() then set
                    // Or, if we want to control the ID for local_ promotions, we might need a different strategy
                    // For now, let new ones get new IDs, and local_ ones get new IDs if their local_ ID isn't a valid Firestore path.
                    // Let's assume for simplicity, if firestoreId is blank or "local_", we treat it as needing a new Firestore document.
                    scenesCollection.document() // Firestore generates a new ID path
                } else {
                    scenesCollection.document(sceneWithUser.firestoreId) // Existing scene
                }
                // The scene object itself might not have the new firestoreId if it was generated by .document()
                // but the batch operation uses the docRef.
                // If the caller needs the generated IDs, this method would need to return them.
                // For now, just perform the set.
                batch.set(docRef, sceneWithUser.copy(firestoreId = docRef.id)) // Ensure the object in batch has the correct ID
                Log.d(TAG, "Batch: Added/Set scene '${sceneWithUser.title}' with ID ${docRef.id}")
            }
            batch.commit().await()
            Log.i(TAG, "Successfully committed batch of ${scenesToProcess.size} scenes for user $userId.")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scenes batch for user $userId", e)
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

    suspend fun getUserDefaultScenesOnce(userId: String): Result<List<Scene>> {
        return try {
            if (userId.isEmpty()) {
                Log.w(TAG, "getUserDefaultScenesOnce called with empty userId.")
                return Result.failure(IllegalArgumentException("User ID cannot be empty"))
            }
            Log.d(TAG, "Fetching all default scenes once for userId: $userId")
            val snapshot = getScenesCollection()
                .whereEqualTo("userId", userId)
                .whereEqualTo("isCustom", false) // Only get default scenes
                .get()
                .await()
            
            val scenes = snapshot.documents.mapNotNull { document ->
                try {
                    document.toObject<Scene>()?.apply {
                        firestoreId = document.id
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting document ${document.id} to Scene in getUserDefaultScenesOnce", e)
                    null // Skip problematic document
                }
            }
            Log.d(TAG, "Successfully fetched ${scenes.size} default scenes once for userId: $userId")
            Result.success(scenes)
        } catch (e: Exception) {
            Log.e(TAG, "Error in getUserDefaultScenesOnce for userId $userId", e)
            Result.failure(e)
        }
    }

    // New function to get original asset scene details
    // This is a blocking function, call from a coroutine with Dispatchers.IO
    fun getAssetSceneDetailsByOriginalId(originalId: Int): Scene? {
        Log.d(TAG, "Attempting to load asset scene details for originalId: $originalId")
        return try {
            val jsonString = applicationContext.assets.open("scenes.json").bufferedReader().use { it.readText() }
            val listSceneType = object : TypeToken<List<Scene>>() {}.type
            val allAssetScenes: List<Scene> = Gson().fromJson(jsonString, listSceneType)
            val foundScene = allAssetScenes.find { it.id == originalId }
            if (foundScene == null) {
                Log.w(TAG, "Asset scene with originalId $originalId not found in scenes.json.")
            } else {
                Log.d(TAG, "Found asset scene details for originalId $originalId: ${foundScene.title}")
            }
            foundScene
        } catch (e: IOException) {
            Log.e(TAG, "Error loading scenes.json from assets", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing scenes.json for originalId $originalId", e)
            null
        }
    }
}
