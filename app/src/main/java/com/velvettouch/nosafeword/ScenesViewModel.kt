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
        loadScenes()
    }

    private fun loadScenes() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            repository.getUserScenesFlow()
                .catch { e ->
                    _error.value = "Failed to load scenes: ${e.localizedMessage}"
                    Log.e(TAG, "Error loading scenes from flow", e)
                    _isLoading.value = false
                }
                .collect { sceneList ->
                    _scenes.value = sceneList
                    _isLoading.value = false
                    Log.d(TAG, "Collected scenes. Count: ${sceneList.size}. User: ${auth.currentUser?.uid}")

                    // Check if default scenes need to be seeded for this user
                    if (sceneList.isEmpty() && auth.currentUser != null) {
                        checkAndSeedDefaultScenes()
                    }
                }
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
            Log.d(TAG, "Default scenes already seeded for user $userId.")
            return
        }

        Log.d(TAG, "No scenes found in Firestore for user $userId. Attempting to seed default scenes.")
        _isLoading.value = true
        val defaultScenes = loadScenesFromAssets(appContext)
        if (defaultScenes.isNotEmpty()) {
            val result = repository.addDefaultScenesBatch(defaultScenes)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Successfully seeded ${defaultScenes.size} default scenes for user $userId.")
                    setDefaultScenesSeeded(userId, true)
                    // The flow should automatically pick up the new scenes.
                    // No need to manually set _isLoading to false here if flow re-triggers.
                },
                onFailure = { e ->
                    _error.value = "Failed to seed default scenes: ${e.localizedMessage}"
                    Log.e(TAG, "Failed to seed default scenes for user $userId", e)
                    _isLoading.value = false // Set to false on failure
                }
            )
        } else {
            Log.w(TAG, "No default scenes found in assets to seed.")
            _isLoading.value = false // No scenes to seed
        }
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
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = repository.addScene(scene)
            result.fold(
                onSuccess = {
                    // Data will be reloaded by the flow, or you can manually refresh if needed
                    _isLoading.value = false
                },
                onFailure = { e ->
                    _error.value = "Failed to add scene: ${e.localizedMessage}"
                    _isLoading.value = false
                }
            )
        }
    }

    fun updateScene(scene: Scene) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = repository.updateScene(scene)
            result.fold(
                onSuccess = {
                    _isLoading.value = false
                },
                onFailure = { e ->
                    _error.value = "Failed to update scene: ${e.localizedMessage}"
                    _isLoading.value = false
                }
            )
        }
    }

    fun deleteScene(sceneId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = repository.deleteScene(sceneId)
            result.fold(
                onSuccess = {
                    _isLoading.value = false
                },
                onFailure = { e ->
                    _error.value = "Failed to delete scene: ${e.localizedMessage}"
                    _isLoading.value = false
                }
            )
        }
    }

    fun clearError() {
        _error.value = null
    }
}