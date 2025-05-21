package com.velvettouch.nosafeword

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.google.firebase.auth.FirebaseAuth

class PositionsViewModel : ViewModel() {

    private val repository = PositionsRepository()
    private val firebaseAuth = FirebaseAuth.getInstance()

    // For all positions (user-specific custom positions + potentially default/asset ones)
    private val _allPositions = MutableStateFlow<List<PositionItem>>(emptyList())
    val allPositions: StateFlow<List<PositionItem>> = _allPositions.asStateFlow()

    // For user's custom positions only (from Firestore)
    private val _userPositions = MutableStateFlow<List<PositionItem>>(emptyList())
    val userPositions: StateFlow<List<PositionItem>> = _userPositions.asStateFlow()

    // For locally loaded asset positions (if still needed separately)
    private val _assetPositions = MutableStateFlow<List<PositionItem>>(emptyList())
    val assetPositions: StateFlow<List<PositionItem>> = _assetPositions.asStateFlow()

    // State for loading indicators or error messages
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadPositions()
    }

    fun loadPositions() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                // Load user-specific positions from Firestore
                repository.getUserPositions().collect { positions ->
                    _userPositions.value = positions
                    // Combine with asset positions if necessary, or handle assets separately
                    // For now, _allPositions might just reflect _userPositions or a combination
                    // This logic will depend on how you manage default/asset positions
                    updateAllPositions()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error loading positions: ${e.message}"
                // Potentially load only local/asset positions as a fallback
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Call this if you load asset positions separately and need to combine them
    private fun updateAllPositions() {
        // Example: _allPositions.value = _userPositions.value + _assetPositions.value
        // This needs to be adjusted based on how default/asset positions are managed.
        // If default positions are also in Firestore (e.g. marked with isAsset = true),
        // the repository query might handle this directly.
        // Combine asset positions and user positions, ensuring items are unique by ID.
        // Asset positions are typically loaded first or separately.
        _allPositions.value = (_assetPositions.value + _userPositions.value).distinctBy { it.id }
    }


    // Function to load default/asset positions (e.g., from local JSON or assets folder)
    // This is a placeholder - actual implementation will depend on where these are stored.
    fun loadAssetPositions(assetList: List<PositionItem>) {
        _assetPositions.value = assetList
        updateAllPositions()
    }

    fun addOrUpdatePosition(position: PositionItem) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val userId = firebaseAuth.currentUser?.uid
                if (userId == null && !position.isAsset) {
                    _errorMessage.value = "User not logged in. Cannot save custom position."
                    _isLoading.value = false
                    return@launch
                }
                // Ensure userId is set for non-asset positions
                val positionToSave = if (!position.isAsset) position.copy(userId = userId ?: "") else position
                repository.addOrUpdatePosition(positionToSave)
                loadPositions() // Refresh the list
            } catch (e: Exception) {
                _errorMessage.value = "Error saving position: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deletePosition(positionId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                repository.deletePosition(positionId)
                loadPositions() // Refresh the list
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting position: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // If you need to get a single position by ID
    fun getPositionById(positionId: String, callback: (PositionItem?) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val position = repository.getPositionById(positionId)
                callback(position)
            } catch (e: Exception) {
                _errorMessage.value = "Error fetching position: ${e.message}"
                callback(null)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun toggleFavoriteStatus(positionItem: PositionItem) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                if (positionItem.id.isBlank()) {
                    _errorMessage.value = "Position ID is missing. Cannot update favorite status."
                    _isLoading.value = false
                    return@launch
                }
                // For asset positions, favorite status might be handled differently (e.g., locally)
                // or not at all if they can't be favorited.
                // For now, assuming only non-asset (user-created/Firestore) positions can be favorited.
                if (positionItem.isAsset) {
                     _errorMessage.value = "Asset positions cannot be marked as favorite directly through this method."
                    // Potentially handle local favorite marking for assets if needed elsewhere
                    _isLoading.value = false
                    return@launch
                }

                val newFavoriteStatus = !positionItem.isFavorite
                repository.updateFavoriteStatus(positionItem.id, newFavoriteStatus)
                // Refresh the specific item or the whole list
                // To refresh just one item efficiently, you might need to update it in the _userPositions list
                // For simplicity, reloading all user positions for now:
                loadPositions()
            } catch (e: Exception) {
                _errorMessage.value = "Error updating favorite status: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}