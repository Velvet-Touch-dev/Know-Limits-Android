package com.velvettouch.nosafeword

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LocalFavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val localRepository = LocalFavoritesRepository(application.applicationContext)
    // We need access to all scenes to resolve IDs to Scene objects.
    // This assumes ScenesViewModel is available or its source can be accessed.
    // For simplicity, we'll pass ScenesViewModel as a dependency or its scenes Flow.
    // Let's assume for now we'll get the scenesFlow from where this VM is instantiated.

    private val _localFavoriteScenes = MutableStateFlow<List<Scene>>(emptyList())
    val localFavoriteScenes: StateFlow<List<Scene>> = _localFavoriteScenes.asStateFlow()

    private val _isLoading = MutableStateFlow(false) // For potential future async ops
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val TAG = "LocalFavoritesVM"

    // scenesFlow would be provided by the Activity/Fragment that also has ScenesViewModel
    fun loadLocalFavorites(scenesFlow: StateFlow<List<Scene>>) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Combine the flow of all scenes with the current set of local favorite IDs
                scenesFlow.collect { allScenes ->
                    val localIds = localRepository.getLocalFavoriteSceneIds()
                    val favoriteSceneObjects = allScenes.filter { scene ->
                        localIds.contains(getSceneIdentifier(scene)) // Use consistent ID generation
                    }
                    _localFavoriteScenes.value = favoriteSceneObjects
                    Log.d(TAG, "Loaded local favorite scenes. Count: ${favoriteSceneObjects.size}. IDs: $localIds")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading local favorites", e)
                _error.value = "Failed to load local favorites: ${e.message}"
                _localFavoriteScenes.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    // Alternative load function if you want to refresh IDs without needing the full scenesFlow again
    // This is useful if scenesFlow is already being collected elsewhere and we just need to update based on new IDs.
    fun refreshLocalFavorites(allScenes: List<Scene>) {
        viewModelScope.launch {
             _isLoading.value = true
            try {
                val localIds = localRepository.getLocalFavoriteSceneIds()
                val favoriteSceneObjects = allScenes.filter { scene ->
                    localIds.contains(getSceneIdentifier(scene))
                }
                _localFavoriteScenes.value = favoriteSceneObjects
                Log.d(TAG, "Refreshed local favorite scenes. Count: ${favoriteSceneObjects.size}. IDs: $localIds")
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing local favorites", e)
                _error.value = "Failed to refresh local favorites: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }


    fun addLocalFavorite(sceneId: String, currentAllScenes: List<Scene>) {
        viewModelScope.launch {
            localRepository.addLocalFavoriteScene(sceneId)
            refreshLocalFavorites(currentAllScenes) // Update the exposed list
        }
    }

    fun removeLocalFavorite(sceneId: String, currentAllScenes: List<Scene>) {
        viewModelScope.launch {
            localRepository.removeLocalFavoriteScene(sceneId)
            refreshLocalFavorites(currentAllScenes) // Update the exposed list
        }
    }

    fun isLocalFavorite(sceneId: String): Boolean {
        return localRepository.isLocalFavoriteScene(sceneId)
    }

    fun clearAllLocalFavorites(currentAllScenes: List<Scene>) {
        viewModelScope.launch {
            localRepository.clearLocalFavoriteScenes()
            refreshLocalFavorites(currentAllScenes)
        }
    }
    
    // Helper to get consistent scene identifier (asset_ or firestoreId)
    // This should ideally be in a shared utility or on the Scene model itself.
    private fun getSceneIdentifier(scene: Scene): String {
        return if (scene.firestoreId.isNotBlank()) {
            scene.firestoreId
        } else {
            // Assuming 'id' is the asset-based ID from scenes.json
            "asset_${scene.id}"
        }
    }

    fun clearError() {
        _error.value = null
    }
}