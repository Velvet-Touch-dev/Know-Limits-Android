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
            if (user != null && !user.isAnonymous) { // Check if user is not null and not anonymous
                // User logged in
                Log.d("PositionsViewModel", "AuthListener: User logged in (${user.uid}). Triggering sync.")
                syncLocalPositions()
            } else {
                Log.d("PositionsViewModel", "AuthListener: User signed out or anonymous. Not syncing.")
            }
            // Always reload positions on auth state change to reflect correct data source
            Log.d("PositionsViewModel", "AuthListener: Triggering loadPositions() due to auth state change.")
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
                .collect { firestoreFetchedPositions -> // Renamed for clarity
                    _userPositions.value = firestoreFetchedPositions
                    updateAllPositions()
                    _isLoading.value = false

                    // If user is logged in, these positions are from Firestore.
                    // Persist them to SharedPreferences so they are available after sign-out.
                    val currentUser = firebaseAuth.currentUser
                    if (currentUser != null && !currentUser.isAnonymous) {
                        Log.d("PositionsViewModel", "loadPositions: User is logged in. Persisting ${firestoreFetchedPositions.size} Firestore positions to SharedPreferences.")
                        repository.mergeAndSaveFirestorePositionsToLocal(firestoreFetchedPositions)
                    }
                }
        }
    }

    private fun syncLocalPositions() {
        viewModelScope.launch {
            if (_isSyncing.value) {
                Log.d("PositionsViewModel", "syncLocalPositions: Already syncing (_isSyncing is true), skipping new launch.")
                return@launch
            }
            _isSyncing.value = true
            _errorMessage.value = null // Clear previous errors
            Log.d("PositionsViewModel", "syncLocalPositions: Sync initiated. _isSyncing=true. Attempting to acquire syncMutex.")

            try {
                syncMutex.withLock { // SERIALIZE execution of this block
                    Log.d("PositionsViewModel", "syncLocalPositions: Mutex acquired by withLock.")
                    val idsAddedToItemIdsBeingSyncedThisRun = mutableSetOf<String>()
                    try {
                        Log.d("PositionsViewModel", "syncLocalPositions (withLock): Calling repository.getAllLocalPositionsForSync().")
                        val rawLocalPositionsFromPrefs = repository.getAllLocalPositionsForSync()
                        Log.i("PositionsViewModel", "syncLocalPositions (withLock): RAW positions from Prefs for sync: ${rawLocalPositionsFromPrefs.joinToString { it.name + "(ID:" + it.id + ", Img:" + it.imageName + ")" }}")

                        val allLocalPositions = rawLocalPositionsFromPrefs.distinctBy { it.id }
                        Log.d("PositionsViewModel", "syncLocalPositions (withLock): Raw from prefs: ${rawLocalPositionsFromPrefs.size}, After distinctById: ${allLocalPositions.size}")

                        val itemsWithLocalId = allLocalPositions.filter { it.id.startsWith("local_") }
                        val otherItems = allLocalPositions.filterNot { it.id.startsWith("local_") }

                        // Normalize names for comparison: lowercase and remove all whitespace
                        fun String.normalizeForComparison() = this.lowercase().replace("\\s".toRegex(), "")
                        val nonLocalItemNames = otherItems.map { it.name.normalizeForComparison() }.toSet()

                        val localItemsToConsider = itemsWithLocalId.filterNot { localItem ->
                            localItem.name.normalizeForComparison() in nonLocalItemNames
                        }
                        val distinctLocalItemsByName = localItemsToConsider.distinctBy { it.name.normalizeForComparison() }
                        val candidatePositionsForSync = (distinctLocalItemsByName + otherItems).distinctBy { it.id }
                        Log.d("PositionsViewModel", "syncLocalPositions (withLock): Total candidate positions for sync: ${candidatePositionsForSync.joinToString { it.id + " ('" + it.name + "')" }}), count: ${candidatePositionsForSync.size}")

                        val positionsToActuallyProcess = mutableListOf<PositionItem>()
                        candidatePositionsForSync.forEach { candidate ->
                            if (!itemIdsBeingSynced.contains(candidate.id)) {
                                positionsToActuallyProcess.add(candidate)
                                itemIdsBeingSynced.add(candidate.id)
                                idsAddedToItemIdsBeingSyncedThisRun.add(candidate.id)
                            } else {
                                Log.d("PositionsViewModel", "syncLocalPositions (withLock): Item ID '${candidate.id}' (${candidate.name}) already in itemIdsBeingSynced. Skipping.")
                            }
                        }

                        if (positionsToActuallyProcess.isEmpty()) {
                            Log.d("PositionsViewModel", "syncLocalPositions (withLock): No new items to process in this run.")
                        } else {
                            Log.d("PositionsViewModel", "syncLocalPositions (withLock): Processing ${positionsToActuallyProcess.size} items this run: ${positionsToActuallyProcess.joinToString { it.name + " (ID: " + it.id + ")" }}.")
                            val processedDbIds = repository.syncListOfLocalPositionsToFirestore(positionsToActuallyProcess)
                            itemIdsBeingSynced.removeAll(processedDbIds.toSet()) // Use toSet() for safety if processedDbIds is List
                            Log.d("PositionsViewModel", "syncLocalPositions (withLock): Repository processed IDs: $processedDbIds. Updated itemIdsBeingSynced: $itemIdsBeingSynced")
                        }
                        Log.d("PositionsViewModel", "syncLocalPositions (withLock): Sync logic within lock finished successfully.")
                    } catch (e: Exception) { // Catch errors from *inside* the withLock critical section
                        Log.e("PositionsViewModel", "syncLocalPositions (withLock): Error during sync logic", e)
                        _errorMessage.value = "Error syncing positions: ${e.message}"
                        // If an error occurred in this specific run *after* adding to itemIdsBeingSynced,
                        // remove them to allow retrying them cleanly in the next sync attempt.
                        itemIdsBeingSynced.removeAll(idsAddedToItemIdsBeingSyncedThisRun.toSet())
                        Log.d("PositionsViewModel", "syncLocalPositions (withLock): Cleared items added in this failed run from itemIdsBeingSynced: $idsAddedToItemIdsBeingSyncedThisRun")
                    }
                } // Mutex released here automatically by withLock
                Log.d("PositionsViewModel", "syncLocalPositions: Mutex released by withLock.")
            } catch (e: Exception) { // Catch errors from withLock itself (e.g. cancellation if scope is cancelled) or other unexpected issues
                Log.e("PositionsViewModel", "syncLocalPositions: Error with Mutex operation or outer scope", e)
                _errorMessage.value = "Syncing system error: ${e.message}"
            } finally {
                _isSyncing.value = false
                Log.d("PositionsViewModel", "syncLocalPositions: Sync process fully ended. _isSyncing set to false. Final itemIdsBeingSynced: $itemIdsBeingSynced")
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