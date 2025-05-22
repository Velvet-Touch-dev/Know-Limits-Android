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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

class ScenesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScenesRepository()
    private val auth = FirebaseAuth.getInstance()
    private val appContext = application.applicationContext
    private var localScenesWhenLoggedOut: MutableList<Scene> = mutableListOf() // For logged-out state

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

    init {
        // Listen to auth state changes more reactively
        viewModelScope.launch { // Launch a coroutine for the initial auth state check
            auth.addAuthStateListener(authListener)
            handleAuthState(auth.currentUser) // Initial check
        }
    }

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        Log.d(TAG, "Auth state changed. User: ${user?.uid}")
        viewModelScope.launch {
            handleAuthState(user)
        }
    }

    private suspend fun handleAuthState(currentUser: com.google.firebase.auth.FirebaseUser?) {
        if (currentUser != null) {
            Log.d(TAG, "User is logged in: ${currentUser.uid}.")
            if (localScenesWhenLoggedOut.isNotEmpty()) {
                Log.d(TAG, "Attempting to sync ${localScenesWhenLoggedOut.size} locally added scenes.")
                syncLocalScenesToFirestore(currentUser.uid)
            }
            // Always load from Firestore after attempting sync or if no local scenes
            loadScenesFromFirestore()
        } else {
            Log.d(TAG, "User is logged out. Loading default scenes from assets.")
            loadDefaultScenesForLoggedOutUser()
        }
    }

    private suspend fun syncLocalScenesToFirestore(userId: String) {
        _isLoading.value = true
        val scenesToSync = ArrayList(localScenesWhenLoggedOut)
        localScenesWhenLoggedOut.clear()

        var allSynced = true
        Log.d(TAG, "Starting sync of ${scenesToSync.size} local scenes.")
        for (localScene in scenesToSync) {
            val sceneForFirestore = localScene.copy(
                userId = userId,
                isCustom = true, // Locally added scenes are custom
                firestoreId = "" // Let Firestore generate new ID
            )
            Log.d(TAG, "Syncing scene: ${sceneForFirestore.title}")
            val result = repository.addScene(sceneForFirestore)
            if (result.isFailure) {
                Log.e(TAG, "Failed to sync scene '${localScene.title}' to Firestore: ${result.exceptionOrNull()?.message}")
                allSynced = false
                // Optionally, re-add to localScenesWhenLoggedOut or a separate failed-sync list
            } else {
                Log.d(TAG, "Successfully synced scene '${localScene.title}' to Firestore.")
            }
        }
        if (allSynced && scenesToSync.isNotEmpty()) {
            Log.i(TAG, "All ${scenesToSync.size} local scenes synced to Firestore.")
        } else if (scenesToSync.isNotEmpty()){
            _error.value = "Some local scenes failed to sync."
        }
        // isLoading will be set to false by loadScenesFromFirestore which is called after this
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
                    _scenes.value = sceneList
                    _isLoading.value = false
                    Log.d(TAG, "Collected scenes from Firestore. Count: ${sceneList.size}. User: ${auth.currentUser?.uid}")
                    if (sceneList.isEmpty() && auth.currentUser != null) { // Check auth.currentUser again
                        checkAndSeedDefaultScenes()
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
        // _isLoading.value is likely already true from loadScenesFromFirestore
        val assetScenes = loadScenesFromAssets(appContext)
        if (assetScenes.isNotEmpty()) {
            val scenesToSeed = assetScenes.map {
                it.copy(userId = userId, isCustom = false, firestoreId = "") // Ensure firestoreId is empty for new docs
            }
            val result = repository.addDefaultScenesBatch(scenesToSeed)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Successfully seeded ${scenesToSeed.size} default scenes to Firestore for user $userId.")
                    setDefaultScenesSeeded(userId, true)
                },
                onFailure = { e ->
                    _error.value = "Failed to seed default scenes to Firestore: ${e.localizedMessage}"
                    Log.e(TAG, "Failed to seed default scenes to Firestore for user $userId", e)
                }
            )
        } else {
            Log.w(TAG, "No default scenes found in assets to seed into Firestore.")
        }
        // isLoading will be handled by the collecting flow in loadScenesFromFirestore
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
                val sceneWithUser = scene.copy(userId = currentUser.uid, firestoreId = if(scene.firestoreId.startsWith("local_")) "" else scene.firestoreId)
                val result = repository.addScene(sceneWithUser) // addScene in repo handles new ID generation if firestoreId is empty
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene added to Firestore."); _isLoading.value = false },
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
                    localScenesWhenLoggedOut[index] = scene.copy(userId = "") // Ensure no userId
                    _scenes.value = ArrayList(localScenesWhenLoggedOut)
                    Log.d(TAG, "Local scene '${scene.title}' updated (logged out).")
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
        auth.removeAuthStateListener(authListener) // Clean up listener
        Log.d(TAG, "ScenesViewModel onCleared, auth listener removed.")
    }
}