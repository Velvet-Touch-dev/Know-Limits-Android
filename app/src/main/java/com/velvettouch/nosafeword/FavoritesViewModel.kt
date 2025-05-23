package com.velvettouch.nosafeword

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FavoritesViewModel : ViewModel() {

    private val repository = FavoritesRepository()

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadFavorites()
    }

    fun loadFavorites() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null // Clear previous errors
            try {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    Log.d("FavoritesViewModel", "loadFavorites: User not logged in. Emitting empty list.")
                    _favorites.value = emptyList()
                    // No error should be set here, as this is an expected state.
                } else {
                    Log.d("FavoritesViewModel", "loadFavorites: User logged in. Fetching from repository.")
                    repository.getFavorites().collect { favoriteList ->
                        Log.d("FavoritesViewModel", "loadFavorites collected: favoriteList.size = ${favoriteList.size}")
                        _favorites.value = favoriteList
                    }
                }
            } catch (e: Exception) {
                // This catch block will now primarily handle actual exceptions from the repository
                // if the user was logged in but something else went wrong (e.g., network issue).
                Log.e("FavoritesViewModel", "Error loading favorites from repository", e)
                _error.value = "Failed to load online favorites: ${e.message}"
                _favorites.value = emptyList() // Ensure list is empty on error
            } finally {
                _isLoading.value = false
                Log.d("FavoritesViewModel", "loadFavorites: finally block. isLoading set to false. Current favorites count: ${_favorites.value.size}")
            }
        }
    }

    fun addFavorite(itemId: String, itemType: String) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                _error.value = "Cannot add favorite: User not logged in."
                return@launch
            }
            _isLoading.value = true
            val result = repository.addFavorite(itemId, itemType)
            result.onFailure { e ->
                _error.value = "Failed to add favorite: ${e.message}"
            }
            // Favorites list will update automatically via the flow from repository
            _isLoading.value = false
        }
    }

    fun removeFavorite(itemId: String, itemType: String) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                _error.value = "Cannot remove favorite: User not logged in."
                return@launch
            }
            _isLoading.value = true
            val result = repository.removeFavorite(itemId, itemType)
            result.onFailure { e ->
                _error.value = "Failed to remove favorite: ${e.message}"
            }
            // Favorites list will update automatically
            _isLoading.value = false
        }
    }

    fun isItemFavorited(itemId: String, itemType: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                // If user is not logged in, they cannot have online favorites.
                // For local favorites, MainActivity handles SharedPreferences.
                // This ViewModel is primarily for Firestore-backed favorites.
                callback(false)
                return@launch
            }
            // This is a suspend function, so we handle it directly
            // Consider exposing a StateFlow for individual favorite status if needed reactively in UI
            val result = repository.isItemFavorited(itemId, itemType)
            result.onSuccess { isFavorited ->
                callback(isFavorited)
            }.onFailure { e ->
                _error.value = "Failed to check favorite status: ${e.message}"
                callback(false) // Assume not favorited on error
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}