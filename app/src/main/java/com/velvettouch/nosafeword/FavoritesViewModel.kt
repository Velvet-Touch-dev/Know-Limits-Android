package com.velvettouch.nosafeword

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source // Added import
import com.google.firebase.firestore.ktx.toObject // Added import, though might not be directly used here but good for consistency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await // Added import

class FavoritesViewModel(application: Application) : AndroidViewModel(application) { // Changed to AndroidViewModel for context

    private val cloudRepository = FavoritesRepository() // Renamed for clarity
    private val localFavoritesRepository = LocalFavoritesRepository(application.applicationContext) // For merge

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isMerging = MutableStateFlow(false) // For merge operation
    val isMerging: StateFlow<Boolean> = _isMerging.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // loadFavorites() // Let Activity trigger this after potential merge
    }

    fun loadCloudFavorites(performMerge: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.d("FavoritesViewModel", "loadCloudFavorites: User not logged in. Emitting empty list.")
                _favorites.value = emptyList()
                _isLoading.value = false
                return@launch
            }

            if (performMerge) {
                mergeLocalFavoritesWithCloud() // Attempt merge before loading cloud
            }

            // Proceed to load cloud favorites
            _favorites.value = emptyList() // Clear before collecting from cloud
            Log.d("FavoritesViewModel", "loadCloudFavorites: User ${currentUser.uid} logged in. Cleared _favorites, fetching from cloud repository.")
            try {
                cloudRepository.getFavorites().collect { favoriteList ->
                    Log.d("FavoritesViewModel", "loadCloudFavorites collected: favoriteList.size = ${favoriteList.size}")
                    _favorites.value = favoriteList
                }
            } catch (e: Exception) {
                // This catch block will now primarily handle actual exceptions from the repository
                // if the user was logged in but something else went wrong (e.g., network issue).
                Log.e("FavoritesViewModel", "Error loading cloud favorites from repository", e)
                _error.value = "Failed to load cloud favorites: ${e.message}"
                _favorites.value = emptyList()
            } finally {
                _isLoading.value = false
                Log.d("FavoritesViewModel", "loadCloudFavorites: finally block. isLoading set to false. Current cloud favorites count: ${_favorites.value.size}")
            }
        }
    }

    private suspend fun mergeLocalFavoritesWithCloud() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        _isMerging.value = true
        Log.d("FavoritesViewModel", "Starting merge of local favorites to cloud for user $userId.")

        try {
            val localFavoriteIds = localFavoritesRepository.getLocalFavoriteSceneIds()
            if (localFavoriteIds.isEmpty()) {
                Log.d("FavoritesViewModel", "No local favorites to merge.")
                _isMerging.value = false
                return
            }

            Log.d("FavoritesViewModel", "Local favorite scene IDs to merge: $localFavoriteIds")

            // Fetch current cloud favorites to avoid duplicates
            // Note: This is a one-shot fetch, not using the flow here to avoid complexity during merge.
            // The main flow will update after the merge.
            val cloudFavoritesResult = cloudRepository.getCloudFavoritesListSnapshot()

            if (cloudFavoritesResult.isFailure) {
                _error.value = "Failed to fetch cloud favorites for merge: ${cloudFavoritesResult.exceptionOrNull()?.message}"
                Log.e("FavoritesViewModel", "Error fetching cloud favorites for merge", cloudFavoritesResult.exceptionOrNull())
                _isMerging.value = false
                return
            }

            val currentCloudFavorites = cloudFavoritesResult.getOrNull() ?: emptyList()
            val currentCloudItemIds = currentCloudFavorites.map { it.itemId }.toSet()
            Log.d("FavoritesViewModel", "Current cloud favorite item IDs: $currentCloudItemIds")

            val favoritesToUpload = mutableListOf<Favorite>()
            for (localId in localFavoriteIds) {
                if (!currentCloudItemIds.contains(localId)) {
                    // Assuming all local favorites are "scene" type for now.
                    // If you have other types, this logic needs to be more robust.
                    favoritesToUpload.add(Favorite(itemId = localId, itemType = "scene", userId = userId))
                }
            }

            if (favoritesToUpload.isNotEmpty()) {
                Log.d("FavoritesViewModel", "Uploading ${favoritesToUpload.size} new favorites to cloud: $favoritesToUpload")
                val batchResult = cloudRepository.addFavoritesBatch(favoritesToUpload)
                batchResult.onSuccess {
                    Log.d("FavoritesViewModel", "Successfully merged ${favoritesToUpload.size} local favorites to cloud.")
                    localFavoritesRepository.clearLocalFavoriteScenes() // Clear local after successful merge
                    Log.d("FavoritesViewModel", "Cleared local favorites after successful merge.")
                }.onFailure { e ->
                    _error.value = "Failed to merge some local favorites to cloud: ${e.message}"
                    Log.e("FavoritesViewModel", "Error merging local favorites to cloud", e)
                    // Decide on retry strategy or how to handle partial failure.
                    // For now, local won't be cleared if batch fails.
                }
            } else {
                Log.d("FavoritesViewModel", "All local favorites already exist in the cloud. Clearing local.")
                localFavoritesRepository.clearLocalFavoriteScenes() // Clear if all were already present
            }
        } catch (e: Exception) {
            _error.value = "Error during merge process: ${e.message}"
            Log.e("FavoritesViewModel", "Exception during mergeLocalFavoritesWithCloud", e)
        } finally {
            _isMerging.value = false
            Log.d("FavoritesViewModel", "Merge process finished.")
        }
    }


    fun addCloudFavorite(itemId: String, itemType: String) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                _error.value = "Cannot add cloud favorite: User not logged in."
                return@launch
            }
            _isLoading.value = true // Use general isLoading or a specific one for add/remove
            val result = cloudRepository.addFavorite(itemId, itemType)
            result.onFailure { e ->
                _error.value = "Failed to add cloud favorite: ${e.message}"
            }
            // Cloud favorites list will update automatically via the flow from repository
            _isLoading.value = false
        }
    }

    fun removeCloudFavorite(itemId: String, itemType: String) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                _error.value = "Cannot remove cloud favorite: User not logged in."
                return@launch
            }
            _isLoading.value = true
            val result = cloudRepository.removeFavorite(itemId, itemType)
            result.onFailure { e ->
                _error.value = "Failed to remove cloud favorite: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    // This check is against cloud favorites
    fun isCloudItemFavorited(itemId: String, itemType: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                callback(false)
                return@launch
            }
            val result = cloudRepository.isItemFavorited(itemId, itemType)
            result.onSuccess { isFavorited ->
                callback(isFavorited)
            }.onFailure { e ->
                _error.value = "Failed to check cloud favorite status: ${e.message}"
                callback(false)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}