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
        private const val PREFS_APP_NAME = "NoSafeWordAppPrefs" // General app prefs
        private const val KEY_DELETED_LOGGED_OUT_SCENE_IDS = "deleted_logged_out_scene_ids" // Tracks default scenes deleted locally
        private const val KEY_LOGGED_OUT_LOCAL_SCENES_JSON = "logged_out_local_scenes_json" // Stores the full list of local scenes
    }

    private val gson = Gson() // For JSON serialization

    private fun saveLocalScenesToPrefs(context: Context, scenes: List<Scene>) {
        try {
            val jsonString = gson.toJson(scenes)
            val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_LOGGED_OUT_LOCAL_SCENES_JSON, jsonString).apply()
            Log.d(TAG, "Saved ${scenes.size} local scenes to SharedPreferences.")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local scenes to SharedPreferences", e)
        }
    }

    private fun loadLocalScenesFromPrefs(context: Context): MutableList<Scene>? {
        val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_LOGGED_OUT_LOCAL_SCENES_JSON, null)
        return if (jsonString != null) {
            try {
                val typeToken = object : TypeToken<MutableList<Scene>>() {}.type
                val loadedScenes = gson.fromJson<MutableList<Scene>>(jsonString, typeToken)
                Log.d(TAG, "Loaded ${loadedScenes?.size ?: 0} local scenes from SharedPreferences.")
                loadedScenes
            } catch (e: Exception) {
                Log.e(TAG, "Error loading local scenes from SharedPreferences", e)
                null
            }
        } else {
            Log.d(TAG, "No local scenes found in SharedPreferences.")
            null
        }
    }

    private fun getDeletedLoggedOutSceneIds(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DELETED_LOGGED_OUT_SCENE_IDS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveDeletedLoggedOutSceneIds(context: Context, ids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DELETED_LOGGED_OUT_SCENE_IDS, ids).apply()
    }

    // Helper to get a storable, unique ID for a scene when it's managed locally (logged out)
    // This ID is used for tracking deletions in SharedPreferences.
    private fun getPersistentIdentifierForLocalScene(scene: Scene): String? {
        return if (scene.firestoreId.startsWith("local_")) {
            scene.firestoreId // It's a locally created or an asset scene that was edited and got a local_ ID
        } else if (scene.id != 0 && scene.firestoreId.isBlank()) {
            // It's a pristine default scene from assets, use its original int ID to form a key
            "default_${scene.id}"
        } else if (scene.id != 0 && !scene.firestoreId.isBlank() && !scene.firestoreId.startsWith("local_")) {
            // This case might occur if a scene from Firestore (synced default) is somehow in local list during logged out.
            // Unlikely, but if so, its original ID is still the best bet for "default" nature.
             Log.w(TAG, "getPersistentIdentifierForLocalScene: Scene '${scene.title}' has non-local firestoreId ('${scene.firestoreId}') but also original id '${scene.id}' in local context. Using default_id.")
            "default_${scene.id}"
        }
        else {
            Log.e(TAG, "getPersistentIdentifierForLocalScene: Could not determine persistent ID for scene: title='${scene.title}', firestoreId='${scene.firestoreId}', id='${scene.id}'")
            null // Cannot determine a reliable persistent ID for deletion tracking
        }
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
        } else { // User is logged out
            Log.d(TAG, "Auth state: User is logged out.")
            // Try to load the entire local list (including custom additions/edits) from SharedPreferences
            val savedLocalList = loadLocalScenesFromPrefs(appContext)
            if (savedLocalList != null) {
                Log.d(TAG, "Successfully loaded ${savedLocalList.size} scenes from SharedPreferences for logged-out state.")
                localScenesWhenLoggedOut.clear()
                localScenesWhenLoggedOut.addAll(savedLocalList)
                _scenes.value = ArrayList(localScenesWhenLoggedOut)
                _isLoading.value = false // Assuming loading from prefs is quick
            } else {
                // No saved list in SharedPreferences, or error loading it.
                // Fall back to loading from assets (which respects the separate list of deleted *default* scene IDs).
                Log.d(TAG, "No valid list in SharedPreferences, or this is the first load. Initializing from assets for logged-out user.")
                localScenesWhenLoggedOut.clear() // Ensure it's empty before loadDefaultScenesForLoggedOutUser populates it
                loadDefaultScenesForLoggedOutUser() // This populates localScenesWhenLoggedOut and _scenes
            }
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

        // Fetch existing default scenes from Firestore ONCE to avoid multiple reads in the loop
        val existingUserDefaultScenesResult = repository.getUserDefaultScenesOnce(userId)
        val existingUserDefaultScenesMap = if (existingUserDefaultScenesResult.isSuccess) {
            existingUserDefaultScenesResult.getOrNull()?.associateBy { it.id } ?: emptyMap()
        } else {
            Log.e(TAG, "Failed to fetch existing default scenes for user $userId during sync: ${existingUserDefaultScenesResult.exceptionOrNull()?.message}")
            // Proceed with empty map, individual adds might still work or fail gracefully.
            emptyMap<Int, Scene>()
        }

        for (localScene in scenesToProcess) {
            // All scenes here are localScene.isCustom == true
            val sceneToSync = localScene.copy(
                userId = userId,
                isCustom = true // Explicitly ensure it's marked custom for Firestore
            )

            if (localScene.id != 0) { // This was originally a default scene, but edited offline (so isCustom=true)
                Log.d(TAG, "Syncing edited default scene (now custom): '${sceneToSync.title}' (originalId: ${sceneToSync.id}).")
                
                val firestoreSceneMatch = existingUserDefaultScenesMap[localScene.id]
                
                val sceneWithCorrectFirestoreId = if (firestoreSceneMatch != null) {
                    Log.d(TAG, "Found existing Firestore doc (Id: ${firestoreSceneMatch.firestoreId}) for originalId ${localScene.id} (edited default) from pre-fetched list. Will update.")
                    sceneToSync.copy(firestoreId = firestoreSceneMatch.firestoreId) // Use existing Firestore ID for update
                } else {
                    Log.w(TAG, "No existing Firestore doc for originalId ${localScene.id} (edited default) in pre-fetched list. Adding as new custom scene, preserving originalId.")
                    sceneToSync.copy(firestoreId = "") // Let Firestore generate new ID
                }
                
                val result = repository.addScene(sceneWithCorrectFirestoreId) // addScene handles upsert
                if (result.isSuccess) {
                    scenesSyncedOrUpdatedCount++
                } else {
                    syncFailedCount++
                    Log.e(TAG, "Failed to sync/update edited default scene '${sceneToSync.title}': ${result.exceptionOrNull()?.message}")
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
            // localScenesWhenLoggedOut is cleared by the caller (handleAuthState) if it decides a full reload is needed.
            // Or, if handleAuthState finds it populated, it uses it as is.
            // This function's job is to populate it from assets, respecting deletions.
            if (localScenesWhenLoggedOut.isNotEmpty()){
                 Log.d(TAG, "loadDefaultScenesForLoggedOutUser: localScenesWhenLoggedOut not empty, assuming it's managed by handleAuthState. Size: ${localScenesWhenLoggedOut.size}")
                 _scenes.value = ArrayList(localScenesWhenLoggedOut) // Ensure UI is synced
                 _isLoading.value = false
                 return@launch
            }

            Log.d(TAG, "loadDefaultScenesForLoggedOutUser: localScenesWhenLoggedOut is empty, proceeding to load from assets.")
            val assetScenes = loadScenesFromAssets(appContext)
            val deletedIds = getDeletedLoggedOutSceneIds(appContext)
            Log.d(TAG, "loadDefaultScenesForLoggedOutUser: Found ${deletedIds.size} locally deleted IDs: $deletedIds")

            val scenesToDisplay = assetScenes.filter { assetScene ->
                val persistentId = "default_${assetScene.id}" // Default scenes from assets are identified by their original int id
                val isDeleted = deletedIds.contains(persistentId)
                if(isDeleted) Log.d(TAG, "loadDefaultScenesForLoggedOutUser: Filtering out deleted asset scene '${assetScene.title}' (ID: $persistentId)")
                !isDeleted
            }.map {
                // Ensure pristine state for scenes loaded from assets
                it.copy(userId = "", isCustom = false, firestoreId = "")
            }

            localScenesWhenLoggedOut.addAll(scenesToDisplay)
            _scenes.value = ArrayList(localScenesWhenLoggedOut)
            _isLoading.value = false
            Log.d(TAG, "Loaded ${localScenesWhenLoggedOut.size} default scenes for logged out user (after filtering).")
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
                saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist changes
                _isLoading.value = false
                Log.d(TAG, "Scene '${scene.title}' added to local list (logged out) and saved to Prefs. Local ID: $localId")
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
                var sceneUpdatedSuccessfully = false
                val sceneFromDialog = scene // scene passed to updateScene

                // Attempt to find and update if it's an existing local scene (ID starts with "local_")
                if (sceneFromDialog.firestoreId.startsWith("local_")) {
                    val index = localScenesWhenLoggedOut.indexOfFirst { it.firestoreId == sceneFromDialog.firestoreId }
                    if (index != -1) {
                        localScenesWhenLoggedOut[index] = sceneFromDialog.copy(
                            userId = "", // Ensure no userId
                            isCustom = true // Ensure it's marked custom
                        )
                        _scenes.value = ArrayList(localScenesWhenLoggedOut)
                        saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist changes
                        Log.d(TAG, "Updated existing local scene '${sceneFromDialog.title}' (ID: ${sceneFromDialog.firestoreId}) and saved to Prefs.")
                        sceneUpdatedSuccessfully = true
                    }
                } else {
                    // Attempt to find and update if it was a default scene (identified by original Int id)
                    // This scene is now being customized locally.
                    val originalIntId = sceneFromDialog.id
                    // Find by original 'id' and ensure it's not already a 'local_' prefixed scene we missed.
                    val index = localScenesWhenLoggedOut.indexOfFirst { it.id == originalIntId && !it.firestoreId.startsWith("local_") }
                    if (index != -1) {
                        val newLocalFirestoreId = "local_${System.currentTimeMillis()}"
                        localScenesWhenLoggedOut[index] = sceneFromDialog.copy(
                            firestoreId = newLocalFirestoreId, // Assign a new local-specific firestoreId
                            userId = "",
                            isCustom = true // Mark as custom
                        )
                        _scenes.value = ArrayList(localScenesWhenLoggedOut)
                        saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist changes
                        Log.d(TAG, "Updated default scene '${sceneFromDialog.title}' (OriginalIntID: ${originalIntId}) as new local scene (NewLocalFirestoreID: ${newLocalFirestoreId}) and saved to Prefs.")
                        sceneUpdatedSuccessfully = true
                    }
                }

                if (!sceneUpdatedSuccessfully) {
                    _error.value = "Local scene not found for update (Passed ID: ${sceneFromDialog.firestoreId}, OriginalIntID: ${sceneFromDialog.id})."
                    Log.w(TAG, "Local scene (PassedID: ${sceneFromDialog.firestoreId}, OriginalIntID: ${sceneFromDialog.id}) not found for update.")
                    // Do not save to prefs if update failed to find the scene
                }
                _isLoading.value = false
            }
        }
    }

    fun deleteScene(sceneToDelete: Scene) {
        val currentUser = auth.currentUser
        if (currentUser != null) { // Logged in
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                if (sceneToDelete.firestoreId.isBlank()) {
                     _error.value = "Cannot delete scene from Firestore without a valid Firestore ID."; _isLoading.value = false; return@launch
                }
                if (sceneToDelete.firestoreId.startsWith("local_")) {
                     _error.value = "Cannot delete a 'local_' prefixed scene directly from Firestore this way."; _isLoading.value = false; return@launch
                }
                val result = repository.deleteScene(sceneToDelete.firestoreId)
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene '${sceneToDelete.title}' deleted from Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to delete scene '${sceneToDelete.title}': ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error deleting scene",e) }
                )
            }
        } else { // Logged out - delete from local list
             viewModelScope.launch {
                _isLoading.value = true
                var removed = false
                val persistentId = getPersistentIdentifierForLocalScene(sceneToDelete)

                if (persistentId != null) {
                    if (sceneToDelete.firestoreId.startsWith("local_")) {
                        removed = localScenesWhenLoggedOut.removeIf { it.firestoreId == sceneToDelete.firestoreId }
                    } else if (sceneToDelete.id != 0 && sceneToDelete.firestoreId.isBlank()) {
                        // Pristine default scene
                        removed = localScenesWhenLoggedOut.removeIf { it.id == sceneToDelete.id && it.firestoreId.isBlank() }
                    }

                    if (removed) {
                        Log.d(TAG, "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) removed from list.")
                        val deletedIds = getDeletedLoggedOutSceneIds(appContext) // This tracks *default* scenes deleted.
                        if (persistentId.startsWith("default_")) { // Only add to this specific list if it was originally a default scene.
                            deletedIds.add(persistentId)
                            saveDeletedLoggedOutSceneIds(appContext, deletedIds)
                            Log.d(TAG, "Added $persistentId to SharedPreferences *default* deleted list. New list: $deletedIds")
                        }
                        _scenes.value = ArrayList(localScenesWhenLoggedOut)
                        saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist the entire modified list
                        Log.d(TAG, "Saved updated localScenesWhenLoggedOut to Prefs after deletion.")
                    } else {
                        _error.value = "Local scene '${sceneToDelete.title}' not found for deletion in memory."
                        Log.w(TAG, "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) not found for deletion in memory.")
                        // Do not save to prefs if deletion failed to find the scene
                    }
                } else {
                    _error.value = "Could not determine persistent ID for scene '${sceneToDelete.title}' to mark as deleted."
                    Log.e(TAG, "deleteScene (logged out): Failed to get persistent ID for '${sceneToDelete.title}'")
                }
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun resetDefaultScenesForCurrentUser() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val currentUser = auth.currentUser
            if (currentUser == null) {
                _error.value = "User not logged in. Cannot reset scenes."
                _isLoading.value = false
                Log.w(TAG, "resetDefaultScenesForCurrentUser: User not logged in.")
                return@launch
            }
            val userId = currentUser.uid

            // 1. Load original default scenes from assets
            val assetScenes = loadScenesFromAssets(appContext)
            if (assetScenes.isEmpty()) {
                _error.value = "No default scenes found in assets to reset to."
                _isLoading.value = false
                Log.w(TAG, "resetDefaultScenesForCurrentUser: No scenes in assets file.")
                return@launch
            }

            // 2. Get all current user scenes from Firestore
            val firestoreScenesResult = repository.getAllUserScenesOnce(userId)
            if (firestoreScenesResult.isFailure) {
                _error.value = "Failed to fetch current scenes: ${firestoreScenesResult.exceptionOrNull()?.message}"
                _isLoading.value = false
                Log.e(TAG, "resetDefaultScenesForCurrentUser: Failed to get all user scenes.", firestoreScenesResult.exceptionOrNull())
                return@launch
            }
            val firestoreScenes = firestoreScenesResult.getOrNull() ?: emptyList()
            val firestoreScenesMapByOriginalId = firestoreScenes
                .filter { !it.isCustom && it.id != 0 } // Consider only scenes that were originally default
                .associateBy { it.id }

            var scenesAdded = 0
            var scenesUpdated = 0
            var scenesFailed = 0

            // 3. Compare and update/add
            for (assetScene in assetScenes) {
                val firestoreMatch = firestoreScenesMapByOriginalId[assetScene.id]

                if (firestoreMatch == null) {
                    // Default scene from assets is missing in Firestore, add it back.
                    val sceneToAdd = assetScene.copy(
                        userId = userId,
                        isCustom = false, // Ensure it's marked as not custom
                        firestoreId = "" // Let Firestore generate ID
                    )
                    Log.d(TAG, "Reset: Adding missing default scene '${sceneToAdd.title}' (Original ID: ${sceneToAdd.id})")
                    val addResult = repository.addScene(sceneToAdd)
                    if (addResult.isSuccess) scenesAdded++ else scenesFailed++
                } else {
                    // Default scene exists in Firestore. Check if it was modified.
                    val needsUpdate = firestoreMatch.title != assetScene.title ||
                                      firestoreMatch.content != assetScene.content ||
                                      firestoreMatch.isCustom // If it was marked as custom, it's a modification

                    if (needsUpdate) {
                        val sceneToUpdate = assetScene.copy(
                            firestoreId = firestoreMatch.firestoreId, // Use existing Firestore ID
                            userId = userId,
                            isCustom = false // Ensure it's marked as not custom
                        )
                        Log.d(TAG, "Reset: Updating modified default scene '${sceneToUpdate.title}' (Original ID: ${sceneToUpdate.id})")
                        val updateResult = repository.updateScene(sceneToUpdate)
                        if (updateResult.isSuccess) scenesUpdated++ else scenesFailed++
                    }
                }
            }

            // 4. Handle scenes in Firestore that were originally default but are not in assets (should not happen if assets are source of truth)
            // This part is more about cleanup if Firestore has orphaned "default" scenes.
            // For now, the request is to restore asset defaults.

            _isLoading.value = false
            val summary = "Reset complete. Added: $scenesAdded, Updated: $scenesUpdated, Failed: $scenesFailed."
            Log.i(TAG, summary)
            // Optionally, set a success message or rely on the flow to update the UI.
            // Forcing a refresh of the scenes list:
            if (scenesAdded > 0 || scenesUpdated > 0 || scenesFailed > 0) {
                 // The existing flow collection in loadScenesFromFirestore should pick up changes.
                 // If not, a manual trigger might be needed, but Firestore listeners usually handle this.
                 // Forcing a re-evaluation by the flow if it's not immediate:
                 // loadScenesFromFirestore() // This might be redundant if listeners are quick.
            }
            if (scenesFailed > 0) {
                _error.value = "$scenesFailed operations failed during reset."
            }
            // The ViewModel's `scenes` StateFlow should automatically update due to Firestore listener in `loadScenesFromFirestore`.
        }
    }

    fun resetLocalScenesToDefault() {
        viewModelScope.launch {
            if (auth.currentUser != null) {
                Log.w(TAG, "resetLocalScenesToDefault called while user is logged in. This function is for logged-out state.")
                _error.value = "Reset for logged out state called inappropriately."
                _isLoading.value = false // Ensure loading is reset if we exit early
                return@launch
            }
            _isLoading.value = true
            _error.value = null
            Log.d(TAG, "Resetting local scenes to default (logged out), preserving custom scenes.")

            // 1. Get current local scenes (could be from memory or try loading from prefs if memory is empty)
            var currentLocalScenesInMemory = ArrayList(localScenesWhenLoggedOut) // Work with a copy of in-memory list
            val loadedFromPrefs = loadLocalScenesFromPrefs(appContext)

            val effectiveCurrentLocalScenes: MutableList<Scene> = if (loadedFromPrefs != null) {
                Log.d(TAG, "ResetLocal: Using scenes loaded from SharedPreferences. Count: ${loadedFromPrefs.size}")
                loadedFromPrefs
            } else {
                Log.d(TAG, "ResetLocal: No scenes in SharedPreferences, using current in-memory list. Count: ${currentLocalScenesInMemory.size}")
                currentLocalScenesInMemory
            }
            Log.d(TAG, "ResetLocal: Effective current local scenes count before filtering: ${effectiveCurrentLocalScenes.size}")

            // 2. Identify and preserve custom scenes created while logged out
            val customScenesToKeep = effectiveCurrentLocalScenes.filter {
                // A scene is custom if it was explicitly marked as such,
                // or if it has a 'local_' firestoreId and isn't just an edited default scene
                // (edited defaults are reset, new local ones are kept).
                // A simple check: if it has a 'local_' prefix and its original 'id' is 0 (or not a known default pattern)
                // OR if isCustom is true.
                // For simplicity here, we assume scenes with local_ prefix that were NOT originally defaults are custom.
                // Default scenes have non-zero 'id' and initially blank 'firestoreId'.
                // When a default scene is edited locally, it gets a 'local_' firestoreId AND isCustom=true.
                // When a new scene is added locally, it gets 'local_' firestoreId, id=0 (usually), and isCustom=true.
                (it.isCustom && it.firestoreId.startsWith("local_")) || (it.id == 0 && it.firestoreId.startsWith("local_"))
            }.distinctBy { it.firestoreId.ifEmpty { "new_${it.title}_${it.content.hashCode()}" } } // Ensure uniqueness for new scenes not yet saved
            Log.d(TAG, "ResetLocal: Found ${customScenesToKeep.size} custom scenes to preserve.")


            // 3. Clear the tracking of deleted *default* scenes, so all defaults are reloaded from assets
            saveDeletedLoggedOutSceneIds(appContext, mutableSetOf())
            Log.d(TAG, "ResetLocal: Cleared SharedPreferences for deleted *default* scene IDs.")

            // 4. Load original default scenes from assets
            val assetDefaultScenes = loadScenesFromAssets(appContext).map {
                it.copy(userId = "", isCustom = false, firestoreId = "") // Ensure pristine state
            }
            Log.d(TAG, "ResetLocal: Loaded ${assetDefaultScenes.size} scenes from assets.")

            // 5. Construct the new list: original defaults + preserved custom scenes
            localScenesWhenLoggedOut.clear()
            localScenesWhenLoggedOut.addAll(assetDefaultScenes)
            // Add custom scenes, ensuring no duplicates if a custom scene somehow shares an identifier with a default (unlikely)
            // A more robust way would be to ensure custom scenes have unique IDs not overlapping with default `id`s.
            // For now, simple add, assuming custom scenes (especially new ones) won't clash with default asset scenes by content.
            customScenesToKeep.forEach { customScene ->
                // Avoid adding a custom scene if an identical default scene (by content/title) was just added.
                // This is a basic check. A true "originalId" for custom scenes would be better.
                if (!assetDefaultScenes.any { ds -> ds.title == customScene.title && ds.content == customScene.content }) {
                    localScenesWhenLoggedOut.add(customScene)
                } else {
                     Log.d(TAG, "ResetLocal: Custom scene '${customScene.title}' seems to be a duplicate of an asset scene, not re-adding.")
                }
            }
            // Ensure no duplicates in the final list based on a reliable unique property if possible
            // For local scenes, firestoreId (if local_) or a combination of title/content for new ones.
             val distinctLocalScenes = localScenesWhenLoggedOut.distinctBy {
                if (it.firestoreId.startsWith("local_")) it.firestoreId
                else if (it.id != 0 && it.firestoreId.isBlank()) "default_${it.id}" // Pristine default
                else "${it.title}_${it.content.hashCode()}" // Fallback for other cases
            }
            localScenesWhenLoggedOut.clear()
            localScenesWhenLoggedOut.addAll(distinctLocalScenes)


            // 6. Save the new combined list and update UI
            saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut)
            _scenes.value = ArrayList(localScenesWhenLoggedOut)
            _isLoading.value = false
            Log.i(TAG, "Local scenes reset: Default scenes restored, ${customScenesToKeep.size} custom scenes preserved. Total: ${localScenesWhenLoggedOut.size}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        firebaseAuthListener?.let { auth.removeAuthStateListener(it) } // Clean up the stored listener
        Log.d(TAG, "ScenesViewModel onCleared, auth listener removed.")
    }
}