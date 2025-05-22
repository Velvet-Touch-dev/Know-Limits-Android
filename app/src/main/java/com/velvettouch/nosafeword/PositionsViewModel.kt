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

                    val rawLocalPositionsFromPrefs = repository.getAllLocalPositionsForSync()
                    // Step 1: Absolutely ensure no duplicate IDs from the start.
                    val allLocalPositions = rawLocalPositionsFromPrefs.distinctBy { it.id }
                    Log.d("PositionsViewModel", "syncLocalPositions: Raw from prefs: ${rawLocalPositionsFromPrefs.size}, After distinctById: ${allLocalPositions.size}")

                    // Separate local items (those needing potential name de-duplication before sync)
                    val itemsWithLocalId = allLocalPositions.filter { it.id.startsWith("local_") }
                    val otherItems = allLocalPositions.filterNot { it.id.startsWith("local_") } // These are Firestore or asset items

                    // Get names of items that are already synced (non-local)
                    val nonLocalItemNames = otherItems.map { it.name }.toSet()

                    // For items with local_ IDs:
                    // 1. Filter out any local item whose name already exists in nonLocalItemNames.
                    // 2. Then, from the remainder, ensure only one item per name is considered for this sync run.
                    // Normalize names for comparison: lowercase and remove all whitespace
                    fun String.normalizeForComparison() = this.toLowerCase().replace("\\s".toRegex(), "")

                    val normalizedNonLocalItemNames = nonLocalItemNames.map { it.normalizeForComparison() }.toSet()

                    val localItemsToConsider = itemsWithLocalId.filterNot { localItem ->
                        localItem.name.normalizeForComparison() in normalizedNonLocalItemNames
                    }
                    Log.d("PositionsViewModel", "syncLocalPositions: Local items to consider (IDs: ${localItemsToConsider.joinToString { it.id + " ('" + it.name + "')" }}), count: ${localItemsToConsider.size}")

                    // De-duplicate by normalized name
                    val distinctLocalItemsByName = localItemsToConsider.distinctBy { it.name.normalizeForComparison() }
                    
                    Log.d("PositionsViewModel", "syncLocalPositions: Items with local_ prefix: ${itemsWithLocalId.size}. Non-local items: ${otherItems.size}. Normalized non-local names: $normalizedNonLocalItemNames")
                    Log.d("PositionsViewModel", "syncLocalPositions: Distinct local items by normalized name (IDs: ${distinctLocalItemsByName.joinToString { it.id + " ('" + it.name + "')" }}), count: ${distinctLocalItemsByName.size}")

                    // Combine the de-duplicated local items with the other (non-local) items.
                    // Ensure final list is distinct by ID.
                    val candidatePositionsForSync = (distinctLocalItemsByName + otherItems).distinctBy { it.id }
                    Log.d("PositionsViewModel", "syncLocalPositions: Total candidate positions for sync (IDs: ${candidatePositionsForSync.joinToString { it.id + " ('" + it.name + "')" }}), count: ${candidatePositionsForSync.size}")


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

            // Case 1: New position with an image for a logged-in user (Cloud Function flow)
            if (position.id.isBlank() && imageUriToUpload != null && isLoggedInAndNotAnonymous && currentUser?.uid != null) {
                Log.d("PositionsViewModel", "addOrUpdatePosition: New item with image for logged-in user '${currentUser.uid}'. Using Cloud Function flow for '${position.name}'.")
                _isLoading.value = true
                _isSyncing.value = true // Indicate background activity
                _errorMessage.value = null
                try {
                    val success = repository.uploadPositionImageWithMetadata(
                        imageUriToUpload = imageUriToUpload,
                        positionName = position.name,
                        isFavorite = position.isFavorite,
                        // Pass other relevant PositionItem fields here for metadata
                        forUserId = currentUser.uid!! // Safe due to isLoggedInAndNotAnonymous and currentUser.uid check
                    )
                    if (success) {
                        Log.d("PositionsViewModel", "Image for '${position.name}' uploaded with metadata. Cloud Function will handle Firestore write.")
                        // UI will update via Firestore listener. May show a temp "Processing..." state.
                        // No direct refreshPositionsState() here as Firestore listener will trigger updates.
                    } else {
                        _errorMessage.value = "Failed to upload image for new position."
                        Log.e("PositionsViewModel", "Failed to upload image with metadata for '${position.name}'.")
                    }
                } catch (e: Exception) {
                    Log.e("PositionsViewModel", "Error uploading image with metadata for '${position.name}'", e)
                    _errorMessage.value = "Error uploading image: ${e.message}"
                } finally {
                    _isLoading.value = false
                    _isSyncing.value = false
                }
            }
            // Case 2: Existing item update, or new item without image, or anonymous user (Existing direct save/sync logic)
            else {
                val isOnlinePromotionOfLocalItem = (position.id.isBlank() || position.id.startsWith("local_")) && isLoggedInAndNotAnonymous
                if (isOnlinePromotionOfLocalItem) {
                    Log.d("PositionsViewModel", "addOrUpdatePosition: Attempting synchronized save for local item '${position.name}' (ID: ${position.id}) by logged-in user.")
                    if (syncMutex.tryLock()) {
                        val idToPotentiallyCheck = if (position.id.startsWith("local_")) position.id else null
                        if (idToPotentiallyCheck != null && itemIdsBeingSynced.contains(idToPotentiallyCheck)) {
                            Log.w("PositionsViewModel", "addOrUpdatePosition: Item ID '$idToPotentiallyCheck' (${position.name}) is already in itemIdsBeingSynced. Aborting direct save.")
                            _errorMessage.value = "Item is currently being synced. Please try again."
                            _isLoading.value = false
                            syncMutex.unlock()
                            return@launch
                        }
                        idToPotentiallyCheck?.let { itemIdsBeingSynced.add(it) }
                        _isSyncing.value = true
                        try {
                            _isLoading.value = true
                            _errorMessage.value = null
                            Log.d("PositionsViewModel", "addOrUpdatePosition (sync path): Calling repository.addOrUpdatePosition for '${position.name}'.")
                            repository.addOrUpdatePosition(position, imageUriToUpload)
                            refreshPositionsState()
                            Log.d("PositionsViewModel", "addOrUpdatePosition (sync path): Repository call done for '${position.name}'.")
                        } catch (e: Exception) {
                            Log.e("PositionsViewModel", "addOrUpdatePosition (sync path): Error saving '${position.name}'", e)
                            _errorMessage.value = "Error saving position: ${e.message}"
                        } finally {
                            idToPotentiallyCheck?.let { itemIdsBeingSynced.remove(it) }
                            _isSyncing.value = false
                            _isLoading.value = false
                            syncMutex.unlock()
                            Log.d("PositionsViewModel", "addOrUpdatePosition (sync path): Mutex unlocked for '${position.name}'.")
                        }
                    } else {
                        Log.w("PositionsViewModel", "addOrUpdatePosition: Sync mutex locked. Save for '${position.name}' deferred.")
                        _errorMessage.value = "Data sync in progress. Try saving again."
                        _isLoading.value = false
                    }
                } else {
                    // Standard path: Existing Firestore item, or anonymous user, or new item without image for logged-in user
                    Log.d("PositionsViewModel", "addOrUpdatePosition (direct path): Saving '${position.name}' (ID: ${position.id}). LoggedIn: $isLoggedInAndNotAnonymous. ImageURI: $imageUriToUpload")
                    _isLoading.value = true
                    _errorMessage.value = null
                    try {
                        repository.addOrUpdatePosition(position, imageUriToUpload)
                        if (isLoggedInAndNotAnonymous) {
                            refreshPositionsState()
                        } else {
                            loadPositions()
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