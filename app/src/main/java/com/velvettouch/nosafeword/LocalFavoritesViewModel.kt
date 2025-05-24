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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalFavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val localRepository = LocalFavoritesRepository(application.applicationContext)
    private val positionsRepository = PositionsRepository(application.applicationContext) // Added

    private val _localFavoriteScenes = MutableStateFlow<List<Scene>>(emptyList())
    val localFavoriteScenes: StateFlow<List<Scene>> = _localFavoriteScenes.asStateFlow()

    private val _localFavoritePositions = MutableStateFlow<List<PositionItem>>(emptyList()) // Added
    val localFavoritePositions: StateFlow<List<PositionItem>> = _localFavoritePositions.asStateFlow() // Added

    private val _isLoading = MutableStateFlow(false) // For potential future async ops
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val TAG = "LocalFavoritesVM"

    // scenesFlow would be provided by the Activity/Fragment that also has ScenesViewModel
    // allPositionsList would be provided similarly, or fetched via positionsRepository if needed
    fun loadLocalFavorites(scenesFlow: StateFlow<List<Scene>>) { // Removed allPositionsList parameter
        viewModelScope.launch {
            _isLoading.value = true
            // Launch separate jobs for scenes and positions to avoid collect blocking
            launch {
                try {
                    scenesFlow.collect { allScenes ->
                        val localSceneIds = localRepository.getLocalFavoriteSceneIds()
                        val favoriteSceneObjects = allScenes.filter { scene ->
                            localSceneIds.contains(getSceneIdentifier(scene))
                        }
                        _localFavoriteScenes.value = favoriteSceneObjects
                        Log.d(TAG, "Loaded local favorite scenes. Count: ${favoriteSceneObjects.size}. IDs: $localSceneIds")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading local scene favorites", e)
                    _error.value = "Failed to load local scene favorites: ${e.message}"
                    _localFavoriteScenes.value = emptyList()
                }
            }

            launch {
                try {
                    refreshLocalFavoritePositions() // Use the existing suspend function
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading local position favorites during initial load", e)
                     // refreshLocalFavoritePositions handles its own error state for _localFavoritePositions
                }
            }
            // isLoading will be set to false once both jobs are implicitly complete or by refreshLocalFavoritePositions
            // For simplicity, let refreshLocalFavoritePositions handle the final isLoading state if it's the longer one.
            // If scenesFlow is a long-lived flow, this isLoading logic might need refinement.
            // For now, assume scenesFlow provides an initial list then might update.
            // The primary isLoading concern is for the initial fetch of positions.
        }
    }
    
    fun refreshLocalFavoriteScenes(allScenes: List<Scene>) {
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
                Log.e(TAG, "Error refreshing local favorite scenes", e)
                _error.value = "Failed to refresh local favorite scenes: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun refreshLocalFavoritePositions() { // Made suspend as getPositionById is suspend
        withContext(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val localPositionIds = localRepository.getLocalFavoritePositionIds()
                val favoritePositionObjects = mutableListOf<PositionItem>()
                localPositionIds.forEach { posId ->
                    val positionItem = positionsRepository.getPositionById(posId)
                    positionItem?.let { favoritePositionObjects.add(it) }
                }
                _localFavoritePositions.value = favoritePositionObjects
                Log.d(TAG, "Refreshed local favorite positions. Count: ${favoritePositionObjects.size}. IDs: $localPositionIds")
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing local favorite positions", e)
                _error.value = "Failed to refresh local favorite positions: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addLocalFavoriteScene(sceneId: String, currentAllScenes: List<Scene>) {
        viewModelScope.launch {
            localRepository.addLocalFavoriteScene(sceneId)
            refreshLocalFavoriteScenes(currentAllScenes)
        }
    }

    fun removeLocalFavoriteScene(sceneId: String, currentAllScenes: List<Scene>) {
        viewModelScope.launch {
            localRepository.removeLocalFavoriteScene(sceneId)
            refreshLocalFavoriteScenes(currentAllScenes)
        }
    }

    fun isLocalFavoriteScene(sceneId: String): Boolean {
        return localRepository.isLocalFavoriteScene(sceneId)
    }

    // Position specific methods
    fun addLocalFavoritePosition(positionId: String) {
        viewModelScope.launch {
            localRepository.addLocalFavoritePosition(positionId)
            refreshLocalFavoritePositions()
        }
    }

    fun removeLocalFavoritePosition(positionId: String) {
        viewModelScope.launch {
            localRepository.removeLocalFavoritePosition(positionId)
            refreshLocalFavoritePositions()
        }
    }

    fun isLocalFavoritePosition(positionId: String): Boolean {
        return localRepository.isLocalFavoritePosition(positionId)
    }

    fun clearAllLocalFavorites(currentAllScenes: List<Scene>) {
        viewModelScope.launch {
            localRepository.clearAllLocalFavorites()
            refreshLocalFavoriteScenes(currentAllScenes)
            refreshLocalFavoritePositions() // Also refresh positions
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