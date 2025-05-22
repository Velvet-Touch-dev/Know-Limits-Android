package com.velvettouch.nosafeword

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
// import kotlinx.coroutines.flow.first // Not used anymore
import java.io.IOException

class ScenesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScenesRepository()
    private val auth = FirebaseAuth.getInstance()
    private val appContext = application.applicationContext
    private var localScenesWhenLoggedOut: MutableList<Scene> = mutableListOf()
    @Volatile private var isSeedingInProgress = false // Mark volatile for visibility
    @Volatile private var isSyncingLocalScenes = false // Mark volatile

    private companion object {
        private const val TAG = "ScenesViewModel"
        private const val SCENES_ASSET_FILENAME = "scenes.json"
        private const val PREF_DEFAULT_SCENES_SEEDED = "default_scenes_seeded_"
    }

    private val _scenes = MutableStateFlow<List<Scene>>(emptyList())
    val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // StateFlow to represent the current Firebase user
    private val _firebaseUser = MutableStateFlow(auth.currentUser)
    private var firebaseAuthListener: FirebaseAuth.AuthStateListener? = null // Store the listener

    init {
        // Listener to update _firebaseUser StateFlow
        firebaseAuthListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            _firebaseUser.value = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(firebaseAuthListener!!) // Add the stored listener

        // Collect the user StateFlow to handle auth changes
        viewModelScope.launch {
            _firebaseUser.collectLatest { user -> // Use collectLatest to cancel previous handleAuthState if user changes rapidly
                Log.d(TAG, "Auth state collected via StateFlow. User: ${user?.uid}")
                handleAuthState(user)
            }
        }
    }

    // authListener variable is no longer needed

    private suspend fun handleAuthState(currentUser: com.google.firebase.auth.FirebaseUser?) {
        if (currentUser != null) {
            Log.d(TAG, "User is logged in: ${currentUser.uid}.")
            // Critical section: Ensure only one sync/load operation runs at a time for a logged-in user state.
            // isSyncingLocalScenes should protect the sync part.
            // isSeedingInProgress protects the seeding part within loadScenesFromFirestore.

            if (localScenesWhenLoggedOut.isNotEmpty()) {
                if (!isSyncingLocalScenes) {
                    isSyncingLocalScenes = true // Set flag before starting operations
                    try {
                        Log.d(TAG, "Attempting to sync ${localScenesWhenLoggedOut.size} locally added scenes.")
                        syncLocalScenesToFirestore(currentUser.uid) // This is a suspend function
                        // After sync, always reload from Firestore to get the latest state.
                        Log.d(TAG, "Sync of local scenes complete. Loading from Firestore.")
                        loadScenesFromFirestore() // This is a suspend function
                    } finally {
                        isSyncingLocalScenes = false // Reset flag in finally block
                    }
                } else {
                    Log.d(TAG, "Sync already in progress. Will not start another sync or load from Firestore yet.")
                }
            } else { // No local scenes to sync
                Log.d(TAG, "No local scenes to sync. Proceeding to load from Firestore.")
                // This path also handles initial seeding if necessary via loadScenesFromFirestore.
                loadScenesFromFirestore() // This is a suspend function
            }
        } else {
            Log.d(TAG, "User is logged out. Loading default scenes from assets.")
            // Ensure any ongoing Firestore operations for a previous user are handled or cancelled.
            // collectLatest on _firebaseUser helps with this.
            _scenes.value = emptyList() // Clear scenes for logged-out state immediately
            localScenesWhenLoggedOut.clear() // Clear any pending local scenes
            loadDefaultScenesForLoggedOutUser() // This launches its own coroutine
        }
    }

    private suspend fun syncLocalScenesToFirestore(userId: String) {
        // This function now only syncs scenes marked as isCustom=true from localScenesWhenLoggedOut.
        // These are either new custom scenes created offline or default scenes edited offline.
        // Standard, unedited default scenes from assets are not handled here;
        // they are populated by checkAndSeedDefaultScenes if missing from Firestore.

        val scenesToProcess = localScenesWhenLoggedOut.filter { it.isCustom }
        if (scenesToProcess.isEmpty()) {
            Log.d(TAG, "No custom (or edited default) local scenes to sync for user $userId.")
            localScenesWhenLoggedOut.clear() // Clear if all were non-custom or list was empty
            return
        }

        _isLoading.value = true // Indicate sync operation starting
        Log.d(TAG, "Starting sync of ${scenesToProcess.size} custom/edited local scenes for user $userId.")
        var scenesSyncedOrUpdatedCount = 0
        var syncFailedCount = 0

        for (localScene in scenesToProcess) {
            // All scenes here are localScene.isCustom == true
            val sceneToSync = localScene.copy(
                userId = userId,
                isCustom = true // Explicitly ensure it's marked custom for Firestore
            )

            if (localScene.id != 0) { // This was originally a default scene, but edited offline (so isCustom=true)
                Log.d(TAG, "Syncing edited default scene (now custom): '${sceneToSync.title}' (originalId: ${sceneToSync.id}).")
                val existingFirestoreSceneResult = repository.getSceneByOriginalId(localScene.id, userId)

                if (existingFirestoreSceneResult.isSuccess) {
                    val firestoreScene = existingFirestoreSceneResult.getOrNull()
                    val sceneWithCorrectFirestoreId = if (firestoreScene != null) {
                        Log.d(TAG, "Found existing Firestore doc (Id: ${firestoreScene.firestoreId}) for originalId ${localScene.id} (edited default). Will update.")
                        sceneToSync.copy(firestoreId = firestoreScene.firestoreId) // Use existing Firestore ID for update
                    } else {
                        // This case should be rare if default scenes are seeded properly.
                        // It means a default scene was edited offline, but its original counterpart never made it to Firestore.
                        // We'll add it as a new custom scene, but it retains its originalId.
                        Log.w(TAG, "No existing Firestore doc for originalId ${localScene.id} (edited default). Adding as new custom scene, preserving originalId.")
                        sceneToSync.copy(firestoreId = "") // Let Firestore generate new ID
                    }
                    
                    val result = repository.addScene(sceneWithCorrectFirestoreId) // addScene handles upsert
                    if (result.isSuccess) {
                        scenesSyncedOrUpdatedCount++
                    } else {
                        syncFailedCount++
                        Log.e(TAG, "Failed to sync/update edited default scene '${sceneToSync.title}': ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    syncFailedCount++
                    Log.e(TAG, "Failed to query for existing default scene (originalId: ${localScene.id}) before syncing edited version: ${existingFirestoreSceneResult.exceptionOrNull()?.message}")
                }
            } else { // This is a purely new custom scene (originalId is 0 or was never a default)
                Log.d(TAG, "Syncing new custom scene: '${sceneToSync.title}'.")
                val result = repository.addScene(sceneToSync.copy(firestoreId = "")) // Ensure new ID for purely new custom scene
                if (result.isSuccess) {
                    scenesSyncedOrUpdatedCount++
                } else {
                    syncFailedCount++
                    Log.e(TAG, "Failed to sync new custom scene '${sceneToSync.title}': ${result.exceptionOrNull()?.message}")
                }
            }
        }

        localScenesWhenLoggedOut.clear() // Clear all after processing custom/edited ones

        Log.i(TAG, "Sync of custom/edited scenes complete for user $userId. Synced/Updated: $scenesSyncedOrUpdatedCount, Failed: $syncFailedCount.")
        if (syncFailedCount > 0) {
            _error.value = "$syncFailedCount custom/edited scenes failed to sync."
        }
        // _isLoading will be handled by the calling context (handleAuthState -> loadScenesFromFirestore)
    }

    private fun loadScenesFromFirestore() { // Renamed from loadScenes
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            repository.getUserScenesFlow()
                .catch { e ->
                    _error.value = "Failed to load scenes from Firestore: ${e.localizedMessage}"
                    Log.e(TAG, "Error loading scenes from Firestore flow", e)
                    _isLoading.value = false
                }
                .collect { sceneList ->
                    val currentUser = auth.currentUser // Cache for consistent check
                    Log.d(TAG, "Collected scenes from Firestore. Count: ${sceneList.size}. User: ${currentUser?.uid}")

                    if (currentUser != null && !areDefaultScenesSeeded(currentUser.uid)) {
                        Log.d(TAG, "Default scenes not yet seeded for user ${currentUser.uid}. Triggering seed check.")
                        // isSeedingInProgress flag will be managed by checkAndSeedDefaultScenes
                        // _isLoading will be managed by checkAndSeedDefaultScenes and the flow collection
                        checkAndSeedDefaultScenes() // This will internally check and add only missing defaults
                        // The flow from getUserScenesFlow will eventually emit the updated list including seeded scenes.
                        // We should still update the current _scenes.value with what we have for now.
                        _scenes.value = sceneList
                        // If checkAndSeedDefaultScenes runs, it will manage isLoading. If not, we need to manage it.
                        // However, the flow will re-emit, so this isLoading=false might be premature if seeding happens.
                        // Let's rely on the flow to update isLoading after seeding.
                        // If seeding doesn't run (because areDefaultScenesSeeded was true, though we entered this block),
                        // then we need to set isLoading false.
                        // This logic path is now simpler: if not seeded, attempt seed. The flow will update.
                    }
                    // Always update scenes with the latest from Firestore, regardless of seeding attempt.
                    _scenes.value = sceneList
                    _isLoading.value = false // Set loading to false after processing current emission.
                                            // If seeding was triggered, the flow will emit again and update isLoading.

                    if (currentUser != null && sceneList.isEmpty() && areDefaultScenesSeeded(currentUser.uid)) {
                        Log.d(TAG, "Firestore scene list is empty for user ${currentUser.uid}, but defaults were already seeded (this is unusual).")
                    }
                }
        }
    }
    
    private fun loadDefaultScenesForLoggedOutUser() {
        viewModelScope.launch {
            _isLoading.value = true
            localScenesWhenLoggedOut.clear()
            val assetScenes = loadScenesFromAssets(appContext)
            localScenesWhenLoggedOut.addAll(assetScenes.map {
                it.copy(userId = "", isCustom = it.isCustom) // Ensure no userId, respect isCustom from JSON or default
            })
            _scenes.value = ArrayList(localScenesWhenLoggedOut) // Emit a new list
            _isLoading.value = false
            Log.d(TAG, "Loaded ${localScenesWhenLoggedOut.size} default scenes for logged out user.")
        }
    }

    private fun areDefaultScenesSeeded(userId: String): Boolean {
        val prefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DEFAULT_SCENES_SEEDED + userId, false)
    }

    private fun setDefaultScenesSeeded(userId: String, seeded: Boolean) {
        val prefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_DEFAULT_SCENES_SEEDED + userId, seeded).apply()
    }

    private suspend fun checkAndSeedDefaultScenes() {
        val userId = auth.currentUser?.uid ?: return
        if (areDefaultScenesSeeded(userId)) {
            Log.d(TAG, "Default scenes already seeded in Firestore for user $userId.")
            return
        }

        Log.d(TAG, "Firestore empty for user $userId. Attempting to seed default scenes from assets into Firestore.")
        // isSeedingInProgress is true here
        val assetScenes = loadScenesFromAssets(appContext)
        if (assetScenes.isNotEmpty()) {
            val scenesToSeed = assetScenes.map {
                it.copy(userId = userId, isCustom = false, firestoreId = "")
            }
            Log.d(TAG, "Preparing to seed ${scenesToSeed.size} scenes for user $userId.")
            val result = repository.addDefaultScenesBatch(scenesToSeed)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Successfully seeded ${scenesToSeed.size} default scenes to Firestore for user $userId.")
                    setDefaultScenesSeeded(userId, true)
                    // The flow will re-emit, and isLoading will be set to false by the collector.
                },
                onFailure = { e ->
                    _error.value = "Failed to seed default scenes to Firestore: ${e.localizedMessage}"
                    Log.e(TAG, "Failed to seed default scenes to Firestore for user $userId", e)
                    _isLoading.value = false
                }
            )
        } else {
            Log.w(TAG, "No default scenes found in assets to seed into Firestore for user $userId.")
            _isLoading.value = false
        }
        isSeedingInProgress = false // Reset flag after attempt
    }

    private fun loadScenesFromAssets(context: Context): List<Scene> {
        return try {
            val jsonString = context.assets.open(SCENES_ASSET_FILENAME).bufferedReader().use { it.readText() }
            // Assuming scenes.json is an array of Scene objects directly
            // The Scene data class should have default values for firestoreId and userId
            // as they will be set before saving to Firestore.
            val typeToken = object : TypeToken<List<Scene>>() {}.type
            Gson().fromJson<List<Scene>>(jsonString, typeToken) ?: emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Error reading scenes from assets: $SCENES_ASSET_FILENAME", e)
            _error.value = "Could not load default scenes from assets."
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading scenes from assets", e)
            _error.value = "Unexpected error loading default scenes."
            emptyList()
        }
    }

    fun addScene(scene: Scene) {
        val currentUser = auth.currentUser
        if (currentUser != null) { // User is logged in
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                val sceneWithUser = scene.copy(
                    userId = currentUser.uid,
                    isCustom = true, // Explicitly ensure isCustom is true for new scenes added when logged in
                    firestoreId = if(scene.firestoreId.startsWith("local_")) "" else scene.firestoreId
                )
                Log.d(TAG, "Adding scene to Firestore: ${sceneWithUser.title}, isCustom: ${sceneWithUser.isCustom}, originalId: ${sceneWithUser.id}")
                val result = repository.addScene(sceneWithUser) // addScene in repo handles new ID generation if firestoreId is empty
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene '${sceneWithUser.title}' added to Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to add scene: ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error adding scene",e) }
                )
            }
        } else { // User is logged out
            viewModelScope.launch {
                _isLoading.value = true
                val localId = "local_${System.currentTimeMillis()}"
                val localScene = scene.copy(userId = "", isCustom = true, firestoreId = localId)
                localScenesWhenLoggedOut.add(localScene)
                _scenes.value = ArrayList(localScenesWhenLoggedOut) // Emit updated list
                _isLoading.value = false
                Log.d(TAG, "Scene '${scene.title}' added to local list (logged out). Local ID: $localId")
            }
        }
    }

    fun updateScene(scene: Scene) {
        val currentUser = auth.currentUser
        if (currentUser != null) { // Logged in
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                if (scene.firestoreId.isBlank() || scene.firestoreId.startsWith("local_")) {
                    _error.value = "Cannot update scene without valid Firestore ID."; _isLoading.value = false; return@launch
                }
                if (scene.userId.isNotBlank() && scene.userId != currentUser.uid) {
                     _error.value = "Cannot update scene of another user."; _isLoading.value = false; return@launch
                }
                val sceneToUpdate = scene.copy(userId = currentUser.uid) // Ensure userId is correct
                val result = repository.updateScene(sceneToUpdate)
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene updated in Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to update scene: ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error updating scene",e) }
                )
            }
        } else { // Logged out - update local list
            viewModelScope.launch {
                _isLoading.value = true
                val index = localScenesWhenLoggedOut.indexOfFirst { it.firestoreId == scene.firestoreId && it.firestoreId.startsWith("local_") }
                if (index != -1) {
                    // When a scene (even a default one) is edited offline, it becomes custom.
                    localScenesWhenLoggedOut[index] = scene.copy(
                        userId = "", // Ensure no userId for local scenes
                        isCustom = true // Mark as custom because it has been edited
                    )
                    _scenes.value = ArrayList(localScenesWhenLoggedOut)
                    Log.d(TAG, "Local scene '${scene.title}' updated (logged out) and marked as custom.")
                } else {
                    _error.value = "Local scene not found for update."
                    Log.w(TAG, "Local scene with ID ${scene.firestoreId} not found for update.")
                }
                _isLoading.value = false
            }
        }
    }

    fun deleteScene(sceneId: String) { // sceneId is firestoreId or local_...
        val currentUser = auth.currentUser
        if (currentUser != null) { // Logged in
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                if (sceneId.startsWith("local_")) {
                     _error.value = "Cannot delete local scene from Firestore."; _isLoading.value = false; return@launch
                }
                val result = repository.deleteScene(sceneId)
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene deleted from Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to delete scene: ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error deleting scene",e) }
                )
            }
        } else { // Logged out
             viewModelScope.launch {
                _isLoading.value = true
                val removed = localScenesWhenLoggedOut.removeIf { it.firestoreId == sceneId && sceneId.startsWith("local_") }
                if (removed) {
                    _scenes.value = ArrayList(localScenesWhenLoggedOut)
                    Log.d(TAG, "Local scene with ID $sceneId deleted (logged out).")
                } else {
                    _error.value = "Local scene not found for deletion."
                    Log.w(TAG, "Local scene with ID $sceneId not found for deletion.")
                }
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        firebaseAuthListener?.let { auth.removeAuthStateListener(it) } // Clean up the stored listener
        Log.d(TAG, "ScenesViewModel onCleared, auth listener removed.")
    }
}