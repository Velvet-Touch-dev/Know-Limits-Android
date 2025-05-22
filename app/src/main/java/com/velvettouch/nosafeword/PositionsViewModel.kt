package com.velvettouch.nosafeword

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first // Added for one-time fetch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PositionsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PositionsRepository(application.applicationContext)
    private val firebaseAuth = FirebaseAuth.getInstance()
    private var authListener: FirebaseAuth.AuthStateListener? = null


    // For all positions (user-specific custom positions + potentially default/asset ones)
    private val _allPositions = MutableStateFlow<List<PositionItem>>(emptyList())
    val allPositions: StateFlow<List<PositionItem>> = _allPositions.asStateFlow()

    // For user's custom positions only (from Firestore or local)
    private val _userPositions = MutableStateFlow<List<PositionItem>>(emptyList())
    val userPositions: StateFlow<List<PositionItem>> = _userPositions.asStateFlow()

    // For locally loaded asset positions
    private val _assetPositions = MutableStateFlow<List<PositionItem>>(emptyList())
    val assetPositions: StateFlow<List<PositionItem>> = _assetPositions.asStateFlow()

    // State for loading indicators or error messages
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    private val syncMutex = Mutex() // Mutex to ensure sync is not re-entrant
    private val itemIdsBeingSynced = mutableSetOf<String>() // Tracks IDs of items currently in a sync process

    init {
        loadPositions() // Initial load

        // Listen for authentication changes to trigger sync and reload positions
        authListener = FirebaseAuth.AuthStateListener { auth ->
            val user = auth.currentUser
            if (user != null && user.uid != "anonymous") {
                // User logged in
                syncLocalPositions()
            }
            // Always reload positions on auth state change to reflect correct data source
            loadPositions()
        }
        firebaseAuth.addAuthStateListener(authListener!!)
    }

    override fun onCleared() {
        super.onCleared()
        authListener?.let { firebaseAuth.removeAuthStateListener(it) }
    }

    fun loadPositions() {
        viewModelScope.launch {
            repository.getUserPositions()
                .onStart { _isLoading.value = true }
                .catch { e ->
                    _errorMessage.value = "Error loading positions: ${e.message}"
                    _isLoading.value = false
                }
                .collect { positions ->
                    _userPositions.value = positions
                    updateAllPositions()
                    _isLoading.value = false
                }
        }
    }

    private fun syncLocalPositions() {
        viewModelScope.launch {
            if (syncMutex.tryLock()) { // Attempt to acquire the lock
                val idsProcessedThisRun = mutableListOf<String>()
                try {
                    // This redundant _isSyncing.value check can be removed if Mutex is trusted,
                    // but as a safeguard, it's okay.
                    if (_isSyncing.value && itemIdsBeingSynced.isEmpty()) {
                         // If _isSyncing is true but itemIdsBeingSynced is empty, it might be a stale _isSyncing flag.
                         // Or, if Mutex was acquired, _isSyncing should ideally be false.
                         Log.w("PositionsViewModel", "syncLocalPositions: _isSyncing was true but itemIdsBeingSynced was empty. Proceeding with caution.")
                    }

                    _isSyncing.value = true // Set main sync flag
                    _errorMessage.value = null
                    Log.d("PositionsViewModel", "syncLocalPositions: Starting sync (mutex acquired).")

                    val rawLocalPositions = repository.getAllLocalPositionsForSync()

                    // Separate local items (those needing potential name de-duplication before sync)
                    val itemsWithLocalId = rawLocalPositions.filter { it.id.startsWith("local_") }
                    val otherItems = rawLocalPositions.filterNot { it.id.startsWith("local_") }

                    // For items with local_ IDs, ensure only one item per name is considered for this sync run.
                    // If multiple local_ items have the same name, distinctBy { it.name } will pick one (typically the first encountered).
                    val distinctLocalItemsByName = itemsWithLocalId.distinctBy { it.name }
                    Log.d("PositionsViewModel", "syncLocalPositions: Raw local items with local_ prefix: ${itemsWithLocalId.size}. After distinctBy name: ${distinctLocalItemsByName.size}")


                    // Combine the de-duplicated (by name) local items with any other items.
                    // Then, ensure overall ID uniqueness as a final safeguard.
                    val candidatePositionsForSync = (distinctLocalItemsByName + otherItems).distinctBy { it.id }
                    Log.d("PositionsViewModel", "syncLocalPositions: Total candidate positions for sync after all deduplication: ${candidatePositionsForSync.size}")


                    val positionsToProcessThisRun = candidatePositionsForSync.filter { candidateItem ->
                        // Only process items whose original ID is not already in the itemIdsBeingSynced set.
                        val originalId = candidateItem.id
                        if (!itemIdsBeingSynced.contains(originalId)) {
                            idsProcessedThisRun.add(originalId) // Add to our tracking for this specific run
                            itemIdsBeingSynced.add(originalId) // Add to the global set
                            true
                        } else {
                            Log.d("PositionsViewModel", "syncLocalPositions: Item ID '$originalId' (${candidateItem.name}) already in itemIdsBeingSynced. Skipping for this run.")
                            false
                        }
                    }

                    if (positionsToProcessThisRun.isEmpty()) {
                        Log.d("PositionsViewModel", "syncLocalPositions: No new items to process in this run after all filtering.")
                        // No need to call repository if list is empty
                    } else {
                        Log.d("PositionsViewModel", "syncLocalPositions: Processing ${positionsToProcessThisRun.size} items this run: ${positionsToProcessThisRun.joinToString { it.name + " (ID: " + it.id + ")" }}.")
                        repository.syncListOfLocalPositionsToFirestore(positionsToProcessThisRun)
                    }
                    
                    // After sync, reload positions to get the latest from Firestore
                    Log.d("PositionsViewModel", "syncLocalPositions: Sync attempt for this batch finished, refreshing positions state.")
                    refreshPositionsState() // Await the state refresh

                } catch (e: Exception) {
                    Log.e("PositionsViewModel", "syncLocalPositions: Error syncing positions", e)
                    _errorMessage.value = "Error syncing positions: ${e.message}"
                } finally {
                    itemIdsBeingSynced.removeAll(idsProcessedThisRun) // Remove only the IDs processed in this specific run
                    _isSyncing.value = false // Clear main sync flag
                    syncMutex.unlock() // Release the lock
                    Log.d("PositionsViewModel", "syncLocalPositions: Sync process ended. IDs processed this run: $idsProcessedThisRun. Mutex unlocked. itemIdsBeingSynced size: ${itemIdsBeingSynced.size}")
                }
            } else {
                Log.d("PositionsViewModel", "syncLocalPositions: Mutex was locked. Another sync is likely in progress. Skipping this call.")
            }
        }
    }


    // Call this if you load asset positions separately and need to combine them
    private fun updateAllPositions() {
        // Asset positions are typically loaded first or separately.
        // User positions can be local (anonymous) or from Firestore (logged in).
        _allPositions.value = (_assetPositions.value + _userPositions.value).distinctBy { it.id }
    }


    // Function to load default/asset positions (e.g., from local JSON or assets folder)
    fun loadAssetPositions(assetList: List<PositionItem>) {
        _assetPositions.value = assetList
        updateAllPositions() // Combine with current user positions
    }

    fun addOrUpdatePosition(position: PositionItem, imageUriToUpload: Uri?) {
        viewModelScope.launch {
            val currentUser = firebaseAuth.currentUser
            val isLoggedInAndNotAnonymous = currentUser != null && !currentUser.isAnonymous
            
            // Determine if this operation might conflict with background sync
            // i.e., if it's a local item (new or existing local) being saved by a logged-in user for Firestore.
            val isOnlinePromotionOfLocalItem = (position.id.isBlank() || position.id.startsWith("local_")) && isLoggedInAndNotAnonymous

            if (isOnlinePromotionOfLocalItem) {
                Log.d("PositionsViewModel", "addOrUpdatePosition: Attempting synchronized save for local item '${position.name}' (ID: ${position.id}) by logged-in user.")
                if (syncMutex.tryLock()) {
                    // Use the item's actual ID if it's a local_ ID. If it's blank (new item),
                    // this specific addOrUpdatePosition call doesn't need a temporary tracking ID in itemIdsBeingSynced
                    // because the mutex itself serializes it against syncLocalPositions.
                    // The main concern is an existing local_ID being processed by both paths.
                    val idToPotentiallyCheck = if (position.id.startsWith("local_")) position.id else null

                    if (idToPotentiallyCheck != null && itemIdsBeingSynced.contains(idToPotentiallyCheck)) {
                        Log.w("PositionsViewModel", "addOrUpdatePosition: Item ID '$idToPotentiallyCheck' (${position.name}) is already in itemIdsBeingSynced (background sync). Aborting direct save.")
                        _errorMessage.value = "Item is currently being synced in the background. Please try again shortly."
                        _isLoading.value = false // Ensure isLoading is reset
                        syncMutex.unlock()
                        return@launch
                    }
                    
                    // If it's a new item (blank ID), it won't be in itemIdsBeingSynced.
                    // If it's an existing local_ item not yet in itemIdsBeingSynced, add it.
                    idToPotentiallyCheck?.let { itemIdsBeingSynced.add(it) }
                    _isSyncing.value = true // Show general syncing indicator

                    try {
                        _isLoading.value = true
                        _errorMessage.value = null
                        Log.d("PositionsViewModel", "addOrUpdatePosition (synchronized path): Calling repository for '${position.name}'.")
                        repository.addOrUpdatePosition(position, imageUriToUpload)
                        refreshPositionsState() // Await state refresh
                        Log.d("PositionsViewModel", "addOrUpdatePosition (synchronized path): Repository call done, state refreshed for '${position.name}'.")
                    } catch (e: Exception) {
                        Log.e("PositionsViewModel", "addOrUpdatePosition (synchronized path): Error saving '${position.name}'", e)
                        _errorMessage.value = "Error saving position: ${e.message}"
                    } finally {
                        idToPotentiallyCheck?.let { itemIdsBeingSynced.remove(it) }
                        _isSyncing.value = false
                        _isLoading.value = false
                        syncMutex.unlock()
                        Log.d("PositionsViewModel", "addOrUpdatePosition (synchronized path): Mutex unlocked for '${position.name}'. itemIdsBeingSynced size: ${itemIdsBeingSynced.size}")
                    }
                } else {
                    Log.w("PositionsViewModel", "addOrUpdatePosition: Sync mutex was locked. Save for '${position.name}' (ID: ${position.id}) deferred/failed.")
                    _errorMessage.value = "Data sync in progress. Please try saving again in a moment."
                    _isLoading.value = false // Ensure isLoading is reset if we don't proceed.
                }
            } else {
                // Standard path: Item is already a Firestore item, or user is anonymous, or not logged in.
                // No need for full syncMutex, but still manage isLoading.
                Log.d("PositionsViewModel", "addOrUpdatePosition (direct path): Saving '${position.name}' (ID: ${position.id}). LoggedIn: $isLoggedInAndNotAnonymous")
                _isLoading.value = true
                _errorMessage.value = null
                try {
                    repository.addOrUpdatePosition(position, imageUriToUpload)
                    if (isLoggedInAndNotAnonymous) {
                        refreshPositionsState() // Refresh if online
                    } else {
                        loadPositions() // Basic refresh if offline
                    }
                } catch (e: Exception) {
                    Log.e("PositionsViewModel", "addOrUpdatePosition (direct path): Error saving '${position.name}'", e)
                    _errorMessage.value = "Error saving position: ${e.message}"
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }

    fun deletePosition(positionId: String, positionName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                Log.d("PositionsViewModel", "deletePosition called. Position ID: $positionId, Position Name: $positionName")
                repository.deletePosition(positionId, positionName)
                loadPositions() // Refresh the list
            } catch (e: Exception) {
                Log.e("PositionsViewModel", "Error deleting position", e)
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
                // Asset positions' favorite status is handled locally by PositionsActivity.
                // This ViewModel method should only deal with user-created positions (local or Firestore).
                if (positionItem.isAsset) {
                    _errorMessage.value = "Asset favorite status is managed locally in the activity."
                    _isLoading.value = false
                    // It's possible we might want to allow favoriting assets even for anonymous users,
                    // storing that preference locally. But for now, this method is for non-assets.
                    return@launch
                }

                val newFavoriteStatus = !positionItem.isFavorite
                repository.updateFavoriteStatus(positionItem.id, newFavoriteStatus)
                // Refresh the list to show the updated favorite status
                loadPositions()
            } catch (e: Exception) {
                _errorMessage.value = "Error updating favorite status: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // New suspending function to perform a one-time refresh of the positions state
    private suspend fun refreshPositionsState() {
        Log.d("PositionsViewModel", "refreshPositionsState: Attempting to refresh positions state.")
        _isLoading.value = true
        try {
            // Take the first emission from the getUserPositions flow.
            // This gets the current state: local if anonymous, or one snapshot from Firestore if logged in.
            val currentPositions = repository.getUserPositions().first()
            _userPositions.value = currentPositions
            updateAllPositions() // Ensure _allPositions is also updated
            Log.d("PositionsViewModel", "refreshPositionsState: State refreshed successfully. User positions count: ${currentPositions.size}")
        } catch (e: Exception) {
            // Catch NoSuchElementException if flow is empty (should not happen with current repo logic), or other errors
            _errorMessage.value = "Error refreshing positions state: ${e.message}"
            Log.e("PositionsViewModel", "refreshPositionsState: Error refreshing state", e)
        } finally {
            _isLoading.value = false
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}