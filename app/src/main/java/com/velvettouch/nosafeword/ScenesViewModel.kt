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
import kotlinx.coroutines.withContext 
import kotlinx.coroutines.Dispatchers 
import java.io.IOException

class ScenesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScenesRepository(application.applicationContext)
    private val localFavoritesRepository = LocalFavoritesRepository(application.applicationContext) 
    private val auth = FirebaseAuth.getInstance()
    private val appContext = application.applicationContext
    private var localScenesWhenLoggedOut: MutableList<Scene> = mutableListOf() 
    @Volatile private var isSeedingInProgress = false
    @Volatile private var isSyncingLocalScenes = false 

    private companion object {
        private const val TAG = "ScenesViewModel"
        private const val SCENES_ASSET_FILENAME = "scenes.json"
        private const val PREF_DEFAULT_SCENES_SEEDED = "default_scenes_seeded_"
        private const val PREF_DEFAULT_SCENES_TAGS_MIGRATED = "default_scenes_tags_migrated_" 
        private const val PREFS_APP_NAME = "NoSafeWordAppPrefs" 
        private const val KEY_DELETED_LOGGED_OUT_SCENE_IDS = "deleted_logged_out_scene_ids" 
        private const val KEY_LOGGED_OUT_LOCAL_SCENES_JSON = "logged_out_local_scenes_json" 
    }

    private val gson = Gson() 

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

    private fun getPersistentIdentifierForLocalScene(scene: Scene): String? {
        return if (scene.firestoreId.startsWith("local_")) {
            scene.firestoreId
        } else if (!scene.firestoreId.isBlank()) {
            scene.firestoreId
        } else if (scene.id != 0 && scene.firestoreId.isBlank()) {
            "default_${scene.id}"
        } else {
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

    private val _firebaseUser = MutableStateFlow(auth.currentUser)
    private var firebaseAuthListener: FirebaseAuth.AuthStateListener? = null 

    init {
        firebaseAuthListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val previousUser = _firebaseUser.value 
            val newUser = firebaseAuth.currentUser
            _firebaseUser.value = newUser 

            if (previousUser != null && newUser == null) { 
                Log.d(TAG, "User ${previousUser.uid} logged out. AuthStateListener detected logout.")
                localScenesWhenLoggedOut.clear()
                Log.d(TAG, "Cleared ViewModel's in-memory localScenesWhenLoggedOut cache due to logout.")
            } else if (previousUser == null && newUser != null) { 
                Log.d(TAG, "User ${newUser.uid} logged in. AuthStateListener detected login.")
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
            _isLoading.value = true 

            val appPrefs = appContext.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
            val deviceOfflineScenes = loadLocalScenesFromPrefs(appContext)

            if (deviceOfflineScenes != null && deviceOfflineScenes.isNotEmpty()) {
                Log.d(TAG, "Found ${deviceOfflineScenes.size} offline scenes. Clearing them from SharedPreferences now to prevent re-sync.")
                appPrefs.edit().remove(KEY_LOGGED_OUT_LOCAL_SCENES_JSON).apply()

                Log.d(TAG, "Processing the ${deviceOfflineScenes.size} loaded offline scenes for potential upload to user ${currentUser.uid}.")
                val scenesToUpload = deviceOfflineScenes.filter {
                    it.isCustom && (it.firestoreId.isBlank() || it.firestoreId.startsWith("local_"))
                }

                if (scenesToUpload.isNotEmpty()) {
                    if (!isSyncingLocalScenes) { 
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

            appPrefs.edit()
                .remove(KEY_LOGGED_OUT_LOCAL_SCENES_JSON) 
                .remove(KEY_DELETED_LOGGED_OUT_SCENE_IDS)
                .apply()
            Log.d(TAG, "Cleared SharedPreferences for logged-out session data (scenes, deleted default IDs).")
            
            if (localScenesWhenLoggedOut.isNotEmpty()) {
                Log.w(TAG, "handleAuthState(loggedIn): localScenesWhenLoggedOut was not empty (unexpected). Clearing it.")
                localScenesWhenLoggedOut.clear()
            }
            loadScenesFromFirestore() 

        } else { 
            Log.d(TAG, "Auth state: User is logged out.")
            _isLoading.value = true
            val existingLocalScenes = loadLocalScenesFromPrefs(appContext)

            if (existingLocalScenes != null && existingLocalScenes.isNotEmpty()) {
                Log.d(TAG, "Found ${existingLocalScenes.size} existing local scenes in SharedPreferences. Using them.")
                localScenesWhenLoggedOut.clear()
                localScenesWhenLoggedOut.addAll(existingLocalScenes)
                _scenes.value = ArrayList(localScenesWhenLoggedOut)
            } else {
                Log.d(TAG, "No existing local scenes in SharedPreferences, or list is empty. Initializing with defaults.")
                val assetScenes = loadScenesFromAssets(appContext)
                val deletedDefaultIds = getDeletedLoggedOutSceneIds(appContext)
                Log.d(TAG, "Found ${deletedDefaultIds.size} locally deleted default asset scene IDs to filter: $deletedDefaultIds")

                val scenesToDisplay = assetScenes.filter { assetScene ->
                    val persistentId = "default_${assetScene.id}"
                    !deletedDefaultIds.contains(persistentId)
                }.map {
                    it.copy(userId = "", isCustom = false, firestoreId = "") 
                }

                localScenesWhenLoggedOut.clear()
                localScenesWhenLoggedOut.addAll(scenesToDisplay)
                _scenes.value = ArrayList(localScenesWhenLoggedOut)
                saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut)
                Log.d(TAG, "Initialized with ${scenesToDisplay.size} default scenes from assets (after filtering). Saved this initial logged-out state to Prefs.")
            }
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
        val scenesForBatch = scenesToUpload.map { localScene ->
            localScene.copy(
                userId = userId,
                isCustom = true, 
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
                    val currentUser = auth.currentUser 
                    Log.d(TAG, "Collected scenes from Firestore. Count: ${sceneList.size}. User: ${currentUser?.uid}")

                    if (currentUser != null && !areDefaultScenesSeeded(currentUser.uid)) {
                        Log.d(TAG, "Default scenes not yet seeded for user ${currentUser.uid}. Triggering seed check.")
                        checkAndSeedDefaultScenes() 
                        _scenes.value = sceneList 
                    }
                    _scenes.value = sceneList

                    if (currentUser != null && !areDefaultSceneTagsMigrated(currentUser.uid)) {
                        Log.d(TAG, "Default scene tags not yet migrated for user ${currentUser.uid}. Triggering migration.")
                        viewModelScope.launch { 
                            val migrationResult = repository.migrateMissingTagsForUserDefaultScenes(currentUser.uid)
                            migrationResult.fold(
                                onSuccess = { updatedCount ->
                                    Log.i(TAG, "Successfully migrated tags for $updatedCount scenes for user ${currentUser.uid}.")
                                    setDefaultSceneTagsMigrated(currentUser.uid, true)
                                },
                                onFailure = { e ->
                                    Log.e(TAG, "Failed to migrate tags for user ${currentUser.uid}", e)
                                }
                            )
                        }
                    }
                    _isLoading.value = false 

                    if (currentUser != null && sceneList.isEmpty() && areDefaultScenesSeeded(currentUser.uid)) { // Corrected typo here
                        Log.d(TAG, "Firestore scene list is empty for user ${currentUser.uid}, but defaults were already seeded (this is unusual).")
                    }
                }
        }
    }
    
    private fun loadDefaultScenesForLoggedOutUser() {
        viewModelScope.launch {
            _isLoading.value = true
            if (localScenesWhenLoggedOut.isNotEmpty()){
                 Log.d(TAG, "loadDefaultScenesForLoggedOutUser: localScenesWhenLoggedOut not empty, assuming it's managed by handleAuthState. Size: ${localScenesWhenLoggedOut.size}")
                 _scenes.value = ArrayList(localScenesWhenLoggedOut) 
                 _isLoading.value = false
                 return@launch
            }

            Log.d(TAG, "loadDefaultScenesForLoggedOutUser: localScenesWhenLoggedOut is empty, proceeding to load from assets.")
            val assetScenes = loadScenesFromAssets(appContext)
            val deletedIds = getDeletedLoggedOutSceneIds(appContext)
            Log.d(TAG, "loadDefaultScenesForLoggedOutUser: Found ${deletedIds.size} locally deleted IDs: $deletedIds")

            val scenesToDisplay = assetScenes.filter { assetScene ->
                val persistentId = "default_${assetScene.id}" 
                val isDeleted = deletedIds.contains(persistentId)
                if(isDeleted) Log.d(TAG, "loadDefaultScenesForLoggedOutUser: Filtering out deleted asset scene '${assetScene.title}' (ID: $persistentId)")
                !isDeleted
            }.map {
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

    private fun areDefaultSceneTagsMigrated(userId: String): Boolean {
        val prefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DEFAULT_SCENES_TAGS_MIGRATED + userId, false)
    }

    private fun setDefaultSceneTagsMigrated(userId: String, migrated: Boolean) {
        val prefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_DEFAULT_SCENES_TAGS_MIGRATED + userId, migrated).apply()
    }

    private suspend fun checkAndSeedDefaultScenes() {
        val userId = auth.currentUser?.uid ?: return
        if (areDefaultScenesSeeded(userId)) {
            Log.d(TAG, "Default scenes already seeded in Firestore for user $userId.")
            return
        }

        Log.d(TAG, "Firestore empty for user $userId. Attempting to seed default scenes from assets into Firestore.")
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
        isSeedingInProgress = false 
    }

    private fun loadScenesFromAssets(context: Context): List<Scene> {
        return try {
            val jsonString = context.assets.open(SCENES_ASSET_FILENAME).bufferedReader().use { it.readText() }
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
        if (currentUser != null) { 
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                val sceneWithUser = scene.copy(
                    userId = currentUser.uid,
                    isCustom = true, 
                    firestoreId = if(scene.firestoreId.startsWith("local_")) "" else scene.firestoreId
                )
                Log.d(TAG, "Adding scene to Firestore: ${sceneWithUser.title}, isCustom: ${sceneWithUser.isCustom}, originalId: ${sceneWithUser.id}")
                val result = repository.addScene(sceneWithUser) 
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene '${sceneWithUser.title}' added to Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to add scene: ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error adding scene",e) }
                )
            }
        } else { 
            viewModelScope.launch {
                _isLoading.value = true
                val localId = "local_${System.currentTimeMillis()}"
                val localScene = scene.copy(userId = "", isCustom = true, firestoreId = localId)
                localScenesWhenLoggedOut.add(localScene)
                _scenes.value = ArrayList(localScenesWhenLoggedOut) 
                saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) 
                _isLoading.value = false
                Log.d(TAG, "Scene '${scene.title}' added to local list (logged out) and saved to Prefs. Local ID: $localId")
            }
        }
    }

    fun updateScene(scene: Scene) {
        val currentUser = auth.currentUser
        if (currentUser != null) { 
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                if (scene.firestoreId.isBlank() || scene.firestoreId.startsWith("local_")) {
                    _error.value = "Cannot update scene without valid Firestore ID."; _isLoading.value = false; return@launch
                }
                if (scene.userId.isNotBlank() && scene.userId != currentUser.uid) {
                     _error.value = "Cannot update scene of another user."; _isLoading.value = false; return@launch
                }
                val sceneToUpdate = scene.copy(userId = currentUser.uid) 
                val result = repository.updateScene(sceneToUpdate)
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene updated in Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to update scene: ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error updating scene",e) }
                )
            }
        } else { 
            viewModelScope.launch {
                _isLoading.value = true
                var sceneUpdatedSuccessfully = false
                val sceneFromDialog = scene 

                val index = localScenesWhenLoggedOut.indexOfFirst { s ->
                    (s.firestoreId.isNotBlank() && s.firestoreId == sceneFromDialog.firestoreId) || 
                    (s.id != 0 && s.id == sceneFromDialog.id && !s.firestoreId.startsWith("local_"))  
                }

                if (index != -1) {
                    val originalSceneInLocalList = localScenesWhenLoggedOut[index]
                    
                    val newOrExistingLocalFirestoreId = if (originalSceneInLocalList.id != 0 && !originalSceneInLocalList.firestoreId.startsWith("local_")) {
                        "local_${System.currentTimeMillis()}_orig${originalSceneInLocalList.id}"
                    } else {
                        originalSceneInLocalList.firestoreId 
                    }.ifEmpty { "local_${System.currentTimeMillis()}" } 

                    val updatedLocalScene = sceneFromDialog.copy(
                        userId = "", 
                        isCustom = true, 
                        firestoreId = newOrExistingLocalFirestoreId,
                        id = originalSceneInLocalList.id 
                    )
                    
                    localScenesWhenLoggedOut[index] = updatedLocalScene
                    _scenes.value = ArrayList(localScenesWhenLoggedOut)
                    saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) 
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
        if (currentUser != null) { 
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
        } else { 
             viewModelScope.launch {
                _isLoading.value = true
                var removed = false
                val persistentId = getPersistentIdentifierForLocalScene(sceneToDelete)

                if (persistentId != null) {
                    val sceneToRemove = localScenesWhenLoggedOut.find { getPersistentIdentifierForLocalScene(it) == persistentId }

                    if (sceneToRemove != null) {
                        removed = localScenesWhenLoggedOut.remove(sceneToRemove)
                        if (removed) {
                            Log.d(TAG, "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) removed from list.")
                            if (persistentId.startsWith("default_")) {
                                val deletedDefaultIds = getDeletedLoggedOutSceneIds(appContext)
                                deletedDefaultIds.add(persistentId)
                                saveDeletedLoggedOutSceneIds(appContext, deletedDefaultIds)
                                Log.d(TAG, "Added $persistentId to SharedPreferences *default* deleted list. New list: $deletedDefaultIds")
                            }
                            _scenes.value = ArrayList(localScenesWhenLoggedOut)
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

            val assetScenes = loadScenesFromAssets(appContext)
            if (assetScenes.isEmpty()) {
                _error.value = "No default scenes found in assets to reset to."
                _isLoading.value = false
                Log.w(TAG, "resetDefaultScenesForCurrentUser: No scenes in assets file.")
                return@launch
            }

            val firestoreScenesResult = repository.getAllUserScenesOnce(userId)
            if (firestoreScenesResult.isFailure) {
                _error.value = "Failed to fetch current scenes: ${firestoreScenesResult.exceptionOrNull()?.message}"
                _isLoading.value = false
                Log.e(TAG, "resetDefaultScenesForCurrentUser: Failed to get all user scenes.", firestoreScenesResult.exceptionOrNull())
                return@launch
            }
            val firestoreScenes = firestoreScenesResult.getOrNull() ?: emptyList()
            val firestoreScenesMapByOriginalId = firestoreScenes
                .filter { !it.isCustom && it.id != 0 } 
                .associateBy { it.id }

            val scenesToProcessInBatch = mutableListOf<Scene>()

            for (assetScene in assetScenes) {
                val firestoreMatch = firestoreScenesMapByOriginalId[assetScene.id]

                if (firestoreMatch == null) {
                    val sceneToAdd = assetScene.copy(
                        userId = userId,
                        isCustom = false, 
                        firestoreId = "" 
                    )
                    scenesToProcessInBatch.add(sceneToAdd)
                    Log.d(TAG, "Reset: Queued for batch add: missing default scene '${sceneToAdd.title}' (Original ID: ${sceneToAdd.id})")
                } else {
                    val needsUpdate = firestoreMatch.title != assetScene.title ||
                                      firestoreMatch.content != assetScene.content ||
                                      firestoreMatch.isCustom 

                    if (needsUpdate) {
                        val sceneToUpdate = assetScene.copy(
                            firestoreId = firestoreMatch.firestoreId, 
                            userId = userId,
                            isCustom = false 
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
            }
        }
    }

    fun resetLocalScenesToDefault() {
        viewModelScope.launch {
            if (auth.currentUser != null) {
                Log.w(TAG, "resetLocalScenesToDefault called while user is logged in. This function is for logged-out state.")
                _error.value = "Reset for logged out state called inappropriately."
                _isLoading.value = false 
                return@launch
            }
            _isLoading.value = true
            _error.value = null
            Log.d(TAG, "Resetting local scenes to default (logged out), preserving custom scenes.")

            var currentLocalScenesInMemory = ArrayList(localScenesWhenLoggedOut) 
            val loadedFromPrefs = loadLocalScenesFromPrefs(appContext)

            val effectiveCurrentLocalScenes: MutableList<Scene> = if (loadedFromPrefs != null) {
                Log.d(TAG, "ResetLocal: Using scenes loaded from SharedPreferences. Count: ${loadedFromPrefs.size}")
                loadedFromPrefs
            } else {
                Log.d(TAG, "ResetLocal: No scenes in SharedPreferences, using current in-memory list. Count: ${currentLocalScenesInMemory.size}")
                currentLocalScenesInMemory
            }
            Log.d(TAG, "ResetLocal: Effective current local scenes count before filtering: ${effectiveCurrentLocalScenes.size}")

            val customScenesToKeep = effectiveCurrentLocalScenes.filter {
                (it.isCustom && it.firestoreId.startsWith("local_")) || (it.id == 0 && it.firestoreId.startsWith("local_"))
            }.distinctBy { it.firestoreId.ifEmpty { "new_${it.title}_${it.content.hashCode()}" } } 
            Log.d(TAG, "ResetLocal: Found ${customScenesToKeep.size} custom scenes to preserve.")

            saveDeletedLoggedOutSceneIds(appContext, mutableSetOf())
            Log.d(TAG, "ResetLocal: Cleared SharedPreferences for deleted *default* scene IDs.")

            val assetDefaultScenes = loadScenesFromAssets(appContext).map {
                it.copy(userId = "", isCustom = false, firestoreId = "") 
            }
            Log.d(TAG, "ResetLocal: Loaded ${assetDefaultScenes.size} scenes from assets.")

            localScenesWhenLoggedOut.clear()
            localScenesWhenLoggedOut.addAll(assetDefaultScenes)
            customScenesToKeep.forEach { customScene ->
                if (!assetDefaultScenes.any { ds -> ds.title == customScene.title && ds.content == customScene.content }) {
                    localScenesWhenLoggedOut.add(customScene)
                } else {
                     Log.d(TAG, "ResetLocal: Custom scene '${customScene.title}' seems to be a duplicate of an asset scene, not re-adding.")
                }
            }
             val distinctLocalScenes = localScenesWhenLoggedOut.distinctBy {
                if (it.firestoreId.startsWith("local_")) it.firestoreId
                else if (it.id != 0 && it.firestoreId.isBlank()) "default_${it.id}" 
                else "${it.title}_${it.content.hashCode()}" 
            }
            localScenesWhenLoggedOut.clear()
            localScenesWhenLoggedOut.addAll(distinctLocalScenes)

            saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut)
            _scenes.value = ArrayList(localScenesWhenLoggedOut)
            _isLoading.value = false
            Log.i(TAG, "Local scenes reset: Default scenes restored, ${customScenesToKeep.size} custom scenes preserved. Total: ${localScenesWhenLoggedOut.size}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        firebaseAuthListener?.let { auth.removeAuthStateListener(it) } 
        Log.d(TAG, "ScenesViewModel onCleared, auth listener removed.")
    }

    fun getCurrentScenesForExport(): List<Scene> {
        return if (auth.currentUser != null) {
            _scenes.value 
        } else {
            ArrayList(localScenesWhenLoggedOut)
        }
    }
}
