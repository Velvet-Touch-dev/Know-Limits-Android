package com.velvettouch.nosafeword

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            _error.value = null
            try {
                repository.getFavorites().collect { favoriteList ->
                    Log.d("FavoritesViewModel", "loadFavorites collected: favoriteList.size = ${favoriteList.size}")
                    _favorites.value = favoriteList
                    Log.d("FavoritesViewModel", "_favorites.value updated. New size: ${_favorites.value.size}")
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                _error.value = "Failed to load favorites: ${e.message}"
                _isLoading.value = false
            }
        }
    }

    fun addFavorite(itemId: String, itemType: String) {
        viewModelScope.launch {
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