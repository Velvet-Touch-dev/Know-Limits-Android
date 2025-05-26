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
import kotlinx.coroutines.withContext // Added import
import kotlinx.coroutines.Dispatchers // Added import
// import kotlinx.coroutines.flow.first // Not used anymore
import java.io.IOException

class ScenesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScenesRepository(application.applicationContext)
    private val localFavoritesRepository = LocalFavoritesRepository(application.applicationContext) // Added
    private val auth = FirebaseAuth.getInstance()
    private val appContext = application.applicationContext
    private var localScenesWhenLoggedOut: MutableList<Scene> = mutableListOf() // This might be simplified or removed
    @Volatile private var isSeedingInProgress = false
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
            // Locally created or an asset scene that was edited offline and got a local_ ID
            scene.firestoreId
        } else if (!scene.firestoreId.isBlank()) {
            // Scene has a non-local_ firestoreId (synced from Firestore, could be custom or a user's copy of a default)
            // This is the primary identifier for scenes that have been in Firestore.
            scene.firestoreId
        } else if (scene.id != 0 && scene.firestoreId.isBlank()) {
            // It's a pristine default scene from assets, not yet touched or synced for the user.
            // Its original int ID is used to form a key for local deletion tracking.
            "default_${scene.id}"
        } else {
            // Fallback or error case
            Log.e(TAG, "getPersistentIdentifierForLocalScene: Could not determine persistent ID for scene: title='${scene.title}', firestoreId='${scene.firestoreId}', originalIntId='${scene.id}'")
            null
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
        firebaseAuthListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val previousUser = _firebaseUser.value // Capture before update
            val newUser = firebaseAuth.currentUser
            _firebaseUser.value = newUser

            if (previousUser != null && newUser == null) { // User just logged out
                Log.d(TAG, "User ${previousUser.uid} logged out. Clearing session-specific data.")
                val appPrefs = appContext.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
                
                // Clear cached scenes from SharedPreferences.
                // handleAuthState(null) will load defaults and save them as the new baseline for the logged-out state.
                appPrefs.edit().remove(KEY_LOGGED_OUT_LOCAL_SCENES_JSON).apply()
                
                // Clear the ViewModel's immediate cache for logged-out scenes.
                localScenesWhenLoggedOut.clear() 
                Log.d(TAG, "Cleared KEY_LOGGED_OUT_LOCAL_SCENES_JSON and ViewModel's localScenesWhenLoggedOut.")
                // The change of _firebaseUser.value to null will trigger handleAuthState(null).
            }
        }
        auth.addAuthStateListener(firebaseAuthListener!!)

        viewModelScope.launch {
            _firebaseUser.collectLatest { user ->
                Log.d(TAG, "Auth state collected via StateFlow. User: ${user?.uid}")
                handleAuthState(user)
            }
        }
    }

    private suspend fun handleAuthState(currentUser: com.google.firebase.auth.FirebaseUser?) {
        if (currentUser != null) {
            Log.d(TAG, "User is logged in: ${currentUser.uid}.")
            _isLoading.value = true // Set loading true at the beginning of logged-in handling

            val appPrefs = appContext.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
            // Load any scenes created/modified during the last logged-out session.
            // These are treated as "anonymous" offline creations to be adopted by the current user.
            val deviceOfflineScenes = loadLocalScenesFromPrefs(appContext)

            if (deviceOfflineScenes != null && deviceOfflineScenes.isNotEmpty()) {
                // Consume the offline scenes immediately by clearing them from prefs.
                // This is the primary guard against re-processing on multiple triggers.
                Log.d(TAG, "Found ${deviceOfflineScenes.size} offline scenes. Clearing them from SharedPreferences now to prevent re-sync.")
                appPrefs.edit().remove(KEY_LOGGED_OUT_LOCAL_SCENES_JSON).apply()

                Log.d(TAG, "Processing the ${deviceOfflineScenes.size} loaded offline scenes for potential upload to user ${currentUser.uid}.")
                val scenesToUpload = deviceOfflineScenes.filter {
                    it.isCustom && (it.firestoreId.isBlank() || it.firestoreId.startsWith("local_"))
                }

                if (scenesToUpload.isNotEmpty()) {
                    if (!isSyncingLocalScenes) { // Secondary guard for true concurrency
                        isSyncingLocalScenes = true
                        try {
                            Log.d(TAG, "Attempting to upload ${scenesToUpload.size} new/modified local custom scenes for user ${currentUser.uid}.")
                            syncLocalScenesToFirestore(currentUser.uid, scenesToUpload)
                        } finally {
                            isSyncingLocalScenes = false
                        }
                    } else {
                        Log.d(TAG, "Upload/sync already in progress for user ${currentUser.uid} (isSyncingLocalScenes=true). Skipping.")
                    }
                } else {
                    Log.d(TAG, "No new/modified local custom scenes found in the loaded offline scenes to upload for user ${currentUser.uid}.")
                }
            } else {
                 Log.d(TAG, "No offline scenes found in SharedPreferences to process for user ${currentUser.uid}.")
            }

            // Clear other logged-out session preferences.
            // KEY_LOGGED_OUT_LOCAL_SCENES_JSON was already cleared if scenes were present.
            // Clearing it again ensures it's gone if deviceOfflineScenes was initially null/empty.
            appPrefs.edit()
                .remove(KEY_LOGGED_OUT_LOCAL_SCENES_JSON) 
                .remove(KEY_DELETED_LOGGED_OUT_SCENE_IDS)
                .apply()
            Log.d(TAG, "Cleared SharedPreferences for logged-out session data (scenes, deleted default IDs).")
            
            // ViewModel's localScenesWhenLoggedOut cache is only for the active logged-out duration and should be empty here.
            if (localScenesWhenLoggedOut.isNotEmpty()) {
                Log.w(TAG, "handleAuthState(loggedIn): localScenesWhenLoggedOut was not empty (unexpected). Clearing it.")
                localScenesWhenLoggedOut.clear()
            }

            // If a sync was attempted, it might have already updated isLoading.
            // loadScenesFromFirestore will manage isLoading for its own operations.
            // If no sync was attempted, loadScenesFromFirestore is responsible for setting isLoading.
            loadScenesFromFirestore() // Load scenes for the current user. This will also handle isLoading.

        } else { // User is logged out
            Log.d(TAG, "Auth state: User is logged out. Resetting to default asset scenes.")
            _isLoading.value = true
            
            // Since SharedPreferences for scenes are cleared on logout now,
            // we always start by loading default assets for the logged-out state.
            val assetScenes = loadScenesFromAssets(appContext)
            // KEY_DELETED_LOGGED_OUT_SCENE_IDS might still exist if user deleted defaults in a previous logged-out session
            // and didn't log in. This is fine to respect for the current logged-out session.
            // This preference is cleared when a user *logs in*.
            val deletedDefaultIds = getDeletedLoggedOutSceneIds(appContext) 
            Log.d(TAG, "Found ${deletedDefaultIds.size} locally deleted default asset scene IDs to filter: $deletedDefaultIds")

            val scenesToDisplay = assetScenes.filter { assetScene ->
                val persistentId = "default_${assetScene.id}"
                !deletedDefaultIds.contains(persistentId)
            }.map {
                it.copy(userId = "", isCustom = false, firestoreId = "") // Ensure pristine state
            }
            
            // localScenesWhenLoggedOut should have been cleared by the AuthStateListener on logout.
            // If not, clear it again to be safe, before populating with defaults.
            if (localScenesWhenLoggedOut.isNotEmpty()) {
                Log.w(TAG, "handleAuthState(null): localScenesWhenLoggedOut was not empty. Clearing it before loading defaults.")
                localScenesWhenLoggedOut.clear()
            }
            localScenesWhenLoggedOut.addAll(scenesToDisplay)
            _scenes.value = ArrayList(localScenesWhenLoggedOut) // Update UI with defaults

            // Save this initial default state to SharedPreferences.
            // Any new custom scenes added while logged out will be added to this list in Prefs.
            saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut)
            Log.d(TAG, "Loaded ${scenesToDisplay.size} default scenes from assets (after filtering). Saved this initial logged-out state to Prefs.")
            _isLoading.value = false
            Log.d(TAG, "Final scenes for logged-out state (count: ${(_scenes.value).size}): ${(_scenes.value).map { it.title }}")
        }
    }

    private suspend fun syncLocalScenesToFirestore(userId: String, scenesToUpload: List<Scene>) {
        if (scenesToUpload.isEmpty()) {
            Log.d(TAG, "No local custom scenes to upload for user $userId.")
            return
        }
        Log.d(TAG, "Uploading ${scenesToUpload.size} new/modified local custom scenes for user $userId.")
        // scenesToUpload are local custom scenes, or defaults edited offline.
        // They need to be added to Firestore under the current user.
        // The new repository.addOrUpdateScenesBatch will handle assigning new Firestore IDs.
        val scenesForBatch = scenesToUpload.map { localScene ->
            localScene.copy(
                userId = userId,
                isCustom = true, // Ensure they are marked as custom for the user
                // firestoreId will be handled by addOrUpdateScenesBatch (generated if blank/local_, used if existing)
                // If localScene.firestoreId was like "local_...", the repo batch method will treat it as new.
                // If localScene.firestoreId was a valid Firestore ID (e.g. an edited default that was previously synced),
                // the repo batch method would update it. However, syncLocalScenesToFirestore is for scenes
                // that are effectively "new" to this user's cloud store from an offline session.
                // So, we should ensure firestoreId is blank to force new document creation in the cloud for these.
                firestoreId = "" 
            )
        }

        if (scenesForBatch.isNotEmpty()) {
            Log.d(TAG, "Calling addOrUpdateScenesBatch for ${scenesForBatch.size} scenes for user $userId.")
            val batchResult = repository.addOrUpdateScenesBatch(scenesForBatch)
            if (batchResult.isSuccess) {
                Log.i(TAG, "Successfully batched ${scenesForBatch.size} local scenes to Firestore for user $userId.")
            } else {
                _error.value = "${scenesForBatch.size} local scenes failed to upload in batch."
                Log.e(TAG, "Error batch uploading local scenes for user $userId", batchResult.exceptionOrNull())
            }
        } else {
            Log.d(TAG, "No scenes in scenesForBatch to upload for user $userId.")
        }
        // isLoading is managed by the caller (handleAuthState) which calls loadScenesFromFirestore next.
    }

    private fun loadScenesFromFirestore() {
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
        } else { // User is logged out - update local list
            viewModelScope.launch {
                _isLoading.value = true
                var sceneUpdatedSuccessfully = false
                val sceneFromDialog = scene // scene passed to updateScene

                // Find the scene in localScenesWhenLoggedOut.
                // It could be a default scene (id != 0, firestoreId is blank initially from assets)
                // or an already locally customized/created scene (firestoreId starts with "local_").
                val index = localScenesWhenLoggedOut.indexOfFirst { s ->
                    (s.firestoreId.isNotBlank() && s.firestoreId == sceneFromDialog.firestoreId) || // Match by existing local_ ID
                    (s.id != 0 && s.id == sceneFromDialog.id && !s.firestoreId.startsWith("local_"))  // Match default asset scene by its original id
                }

                if (index != -1) {
                    val originalSceneInLocalList = localScenesWhenLoggedOut[index]
                    
                    // Determine the firestoreId for the updated local scene.
                    // If it was a default asset scene (id!=0, firestoreId was blank), it gets a new "local_..._origID".
                    // If it already had a "local_" ID, it keeps it.
                    val newOrExistingLocalFirestoreId = if (originalSceneInLocalList.id != 0 && !originalSceneInLocalList.firestoreId.startsWith("local_")) {
                        "local_${System.currentTimeMillis()}_orig${originalSceneInLocalList.id}"
                    } else {
                        originalSceneInLocalList.firestoreId // Should be a "local_" ID already
                    }.ifEmpty { "local_${System.currentTimeMillis()}" } // Fallback if it was a new custom scene with blank ID (addScene should handle this)

                    val updatedLocalScene = sceneFromDialog.copy(
                        userId = "", // Logged out, no user ID
                        isCustom = true, // Any edit/update to a scene in logged-out mode makes it custom for this session
                        firestoreId = newOrExistingLocalFirestoreId,
                        id = originalSceneInLocalList.id // Preserve original asset ID if it was a default scene
                    )
                    
                    localScenesWhenLoggedOut[index] = updatedLocalScene
                    _scenes.value = ArrayList(localScenesWhenLoggedOut)
                    saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist changes
                    Log.d(TAG, "Updated scene '${updatedLocalScene.title}' locally (logged out). Local FirestoreID: ${updatedLocalScene.firestoreId}, Original Asset ID: ${updatedLocalScene.id}")
                    sceneUpdatedSuccessfully = true
                } else {
                    _error.value = "Local scene not found for update (Passed ID: ${sceneFromDialog.firestoreId}, OriginalIntID: ${sceneFromDialog.id})."
                    Log.w(TAG, "Local scene (PassedID: ${sceneFromDialog.firestoreId}, OriginalIntID: ${sceneFromDialog.id}) not found for update while logged out.")
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
                    // When deleting, we match based on the determined persistentId.
                    // The localScenesWhenLoggedOut list contains Scene objects.
                    // We need to find the scene in that list whose persistent identifier matches.
                    val sceneToRemove = localScenesWhenLoggedOut.find { getPersistentIdentifierForLocalScene(it) == persistentId }

                    if (sceneToRemove != null) {
                        removed = localScenesWhenLoggedOut.remove(sceneToRemove)
                        if (removed) {
                            Log.d(TAG, "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) removed from list.")
                            // If the removed scene was originally a default scene (its persistentId starts with "default_"),
                            // then we add this persistentId to the set of *locally deleted default scenes*.
                            // This prevents it from being re-added from assets if the user stays logged out.
                            if (persistentId.startsWith("default_")) {
                                val deletedDefaultIds = getDeletedLoggedOutSceneIds(appContext)
                                deletedDefaultIds.add(persistentId)
                                saveDeletedLoggedOutSceneIds(appContext, deletedDefaultIds)
                                Log.d(TAG, "Added $persistentId to SharedPreferences *default* deleted list. New list: $deletedDefaultIds")
                            }
                            _scenes.value = ArrayList(localScenesWhenLoggedOut)
                            // Persist the entire modified list of scenes (which now excludes the deleted one)
                            // to the main SharedPreferences key for logged-out scenes.
                            saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut)
                            Log.d(TAG, "Saved updated localScenesWhenLoggedOut to Prefs after deletion.")
                        } else {
                             Log.w(TAG, "Scene '${sceneToDelete.title}' (PersistentID: $persistentId) found by persistentId but .remove() failed. This is unexpected.")
                            _error.value = "Failed to remove scene '${sceneToDelete.title}' from memory though it was found."
                        }
                    } else {
                        _error.value = "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) not found for deletion using its persistent ID."
                        Log.w(TAG, "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) not found for deletion using its persistent ID.")
                    }
                } else {
                    _error.value = "Could not determine persistent ID for scene '${sceneToDelete.title}' to delete."
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

            val scenesToProcessInBatch = mutableListOf<Scene>()

            // 3. Compare and prepare for batch
            for (assetScene in assetScenes) {
                val firestoreMatch = firestoreScenesMapByOriginalId[assetScene.id]

                if (firestoreMatch == null) {
                    // Default scene from assets is missing in Firestore, add it back.
                    val sceneToAdd = assetScene.copy(
                        userId = userId,
                        isCustom = false, // Ensure it's marked as not custom
                        firestoreId = "" // Let Firestore generate ID via addOrUpdateScenesBatch
                    )
                    scenesToProcessInBatch.add(sceneToAdd)
                    Log.d(TAG, "Reset: Queued for batch add: missing default scene '${sceneToAdd.title}' (Original ID: ${sceneToAdd.id})")
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
                        scenesToProcessInBatch.add(sceneToUpdate)
                        Log.d(TAG, "Reset: Queued for batch update: modified default scene '${sceneToUpdate.title}' (Original ID: ${sceneToUpdate.id})")
                    }
                }
            }

            var batchSuccess = true
            if (scenesToProcessInBatch.isNotEmpty()) {
                Log.d(TAG, "Reset: Calling addOrUpdateScenesBatch for ${scenesToProcessInBatch.size} scenes.")
                val batchResult = repository.addOrUpdateScenesBatch(scenesToProcessInBatch)
                if (batchResult.isFailure) {
                    batchSuccess = false
                    _error.value = "Failed to batch reset default scenes: ${batchResult.exceptionOrNull()?.message}"
                    Log.e(TAG, "Error batch resetting default scenes", batchResult.exceptionOrNull())
                }
            } else {
                Log.d(TAG, "Reset: No scenes needed to be added or updated.")
            }
            
            _isLoading.value = false
            if (batchSuccess) {
                Log.i(TAG, "Reset default scenes for user $userId complete. Processed ${scenesToProcessInBatch.size} scenes in batch (if any).")
                // Firestore listener in loadScenesFromFirestore should pick up changes.
            }
            // Error already set if batchSuccess is false.
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
