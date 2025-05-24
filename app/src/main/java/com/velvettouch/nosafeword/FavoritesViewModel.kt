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
import kotlinx.coroutines.flow.collectLatest // Added
import kotlinx.coroutines.flow.catch // Added
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await // Added import

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val cloudRepository = FavoritesRepository()
    private val localFavoritesRepository = LocalFavoritesRepository(application.applicationContext)
    private val scenesRepository = ScenesRepository(application.applicationContext) // Pass context
    // private val applicationContext = application.applicationContext // No longer needed directly here

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isMerging = MutableStateFlow(false) // For merge operation
    val isMerging: StateFlow<Boolean> = _isMerging.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var hasMergedThisSession = false
    private var currentUserIdForMerge: String? = null
    private val auth = FirebaseAuth.getInstance()
    private var firebaseAuthListener: FirebaseAuth.AuthStateListener? = null
    private val _firebaseUser = MutableStateFlow(auth.currentUser)


    init {
        firebaseAuthListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            _firebaseUser.value = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(firebaseAuthListener!!)

        viewModelScope.launch {
            _firebaseUser.collectLatest { user ->
                Log.d("FavoritesViewModel", "Auth state changed. User: ${user?.uid}")
                if (user != null) {
                    // User is logged in
                    if (currentUserIdForMerge != user.uid) {
                        Log.d("FavoritesViewModel", "User changed or logged in (${user.uid}). Resetting merge flag.")
                        hasMergedThisSession = false
                        currentUserIdForMerge = user.uid
                    }
                    // Automatically perform merge if not done this session
                    if (!hasMergedThisSession && !_isMerging.value) {
                         Log.d("FavoritesViewModel", "User logged in, attempting merge then loading favorites.")
                        mergeLocalFavoritesWithCloud() // This is suspend
                    }
                    // Always load/listen to favorites when user is present
                    loadAndListenToCloudFavorites()
                } else {
                    // User logged out
                    Log.d("FavoritesViewModel", "User logged out. Clearing favorites list and resetting merge flag.")
                    _favorites.value = emptyList()
                    _isLoading.value = false
                    if (currentUserIdForMerge != null) {
                        hasMergedThisSession = false
                        currentUserIdForMerge = null
                    }
                }
            }
        }
    }

    // Renamed from loadCloudFavorites and adapted for continuous listening
    private fun loadAndListenToCloudFavorites() {
        val currentUser = _firebaseUser.value ?: return // Should be logged in if this is called

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            Log.d("FavoritesViewModel", "loadAndListenToCloudFavorites: User ${currentUser.uid}. Collecting from cloud repository.")

            cloudRepository.getFavorites()
                .catch { e ->
                    Log.e("FavoritesViewModel", "Error in getFavorites flow for user ${currentUser.uid}", e)
                    _error.value = "Failed to load favorites: ${e.message}"
                    _favorites.value = emptyList() // Clear favorites on error
                    _isLoading.value = false
                }
                .collectLatest { favoriteList -> // Use collectLatest here as well if getFavorites itself can be re-triggered by params
                    Log.d("FavoritesViewModel", "Collected favorites for user ${currentUser.uid}. Count: ${favoriteList.size}")
                    _favorites.value = favoriteList
                    _isLoading.value = false // Set to false after each emission
                }
            // Note: If the flow completes or is cancelled (e.g., user logs out), this coroutine will end.
            // If it ends and isLoading was true, ensure it's set to false.
            // However, collectLatest handles cancellation well. If user logs out, _firebaseUser changes,
            // the outer collectLatest cancels this launch, and the "else" branch for logged-out user handles UI state.
        }
    }
    
    // Public function to trigger initial load or refresh, respecting merge logic
    fun refreshCloudFavorites(performMergeIfNeeded: Boolean = true) {
        val currentUser = _firebaseUser.value
        if (currentUser == null) {
            Log.d("FavoritesViewModel", "refreshCloudFavorites: User not logged in. No action.")
            _favorites.value = emptyList() // Ensure UI reflects logged-out state
            return
        }

        viewModelScope.launch {
            if (performMergeIfNeeded && !hasMergedThisSession && !_isMerging.value) {
                Log.d("FavoritesViewModel", "refreshCloudFavorites: Merge needed. Calling mergeLocalFavoritesWithCloud.")
                mergeLocalFavoritesWithCloud() // Suspend call
            }
            // loadAndListenToCloudFavorites will be called by the _firebaseUser collector if user is still logged in
            // or if it wasn't called yet. If already listening, this call might be redundant
            // unless it's meant to force a re-fetch separate from the auth state trigger.
            // For now, relying on the auth state collector to call loadAndListenToCloudFavorites.
            // If a manual refresh button calls this, it might need to directly call loadAndListenToCloudFavorites
            // if the auth state hasn't changed but a refresh is desired.
            // However, the current structure with _firebaseUser.collectLatest should handle re-subscription.
            Log.d("FavoritesViewModel", "refreshCloudFavorites: Triggered. Merge status: hasMerged=$hasMergedThisSession, isMerging=${_isMerging.value}. Relying on auth state collector.")
        }
    }


    private suspend fun mergeLocalFavoritesWithCloud() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return // Should not be null due to check in loadCloudFavorites

        // This check is now done in loadCloudFavorites before calling mergeLocalFavoritesWithCloud
        // if (hasMergedThisSession) {
        //     Log.d("FavoritesViewModel", "mergeLocalFavoritesWithCloud called, but hasMergedThisSession is true. Skipping actual merge logic.")
        //     return // Already merged in this session for this user
        // }

        // Double check _isMerging in case of rapid calls, though the primary check is in loadCloudFavorites
        if (_isMerging.value && !hasMergedThisSession) { // If a merge started but hasn't set hasMergedThisSession yet
            Log.w("FavoritesViewModel", "mergeLocalFavoritesWithCloud called while another merge might be in progress and not yet completed. Bailing to avoid race condition on _isMerging.")
            // This scenario should be rare if loadCloudFavorites's check is effective.
            return
        }

        _isMerging.value = true
        Log.d("FavoritesViewModel", "Starting actual merge of local favorites to cloud for user $userId. hasMergedThisSession = $hasMergedThisSession")

        try {
            val localSceneFavoriteIds = localFavoritesRepository.getLocalFavoriteSceneIds()
            val localPositionFavoriteIds = localFavoritesRepository.getLocalFavoritePositionIds()

            if (localSceneFavoriteIds.isEmpty() && localPositionFavoriteIds.isEmpty()) {
                Log.d("FavoritesViewModel", "No local scene or position favorites to merge.")
                _isMerging.value = false
                hasMergedThisSession = true // Nothing to merge, consider it done for this session
                Log.d("FavoritesViewModel", "hasMergedThisSession set to true (no local items).")
                return
            }

            Log.d("FavoritesViewModel", "Local favorite scene IDs to merge: $localSceneFavoriteIds")
            Log.d("FavoritesViewModel", "Local favorite position IDs to merge: $localPositionFavoriteIds")

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
            val sceneIdPattern = "asset_(\\d+)".toRegex() // Regex for asset scene IDs like "asset_123"
            // Removed redundant declaration of favoritesToUpload

            // Process Scene Favorites
            for (localSceneIdString in localSceneFavoriteIds) {
                var finalSceneItemIdForFavorite = localSceneIdString
                var sceneToEnsureExists: Scene? = null

                val matchResult = sceneIdPattern.matchEntire(localSceneIdString)
                if (matchResult != null) {
                    val originalAssetId = matchResult.groupValues[1].toIntOrNull()
                    if (originalAssetId != null) {
                        Log.d("FavoritesViewModel", "Local scene favorite '$localSceneIdString' is an asset (original ID: $originalAssetId). Checking Firestore status.")
                        val existingSceneResult = scenesRepository.getSceneByOriginalId(originalAssetId, userId)
                        if (existingSceneResult.isSuccess) {
                            val existingFirestoreScene = existingSceneResult.getOrNull()
                            if (existingFirestoreScene?.firestoreId?.isNotBlank() == true) {
                                finalSceneItemIdForFavorite = existingFirestoreScene.firestoreId
                                Log.d("FavoritesViewModel", "Asset scene $originalAssetId already in Firestore for user $userId with firestoreId $finalSceneItemIdForFavorite.")
                            } else {
                                val originalAssetSceneDetails = scenesRepository.getAssetSceneDetailsByOriginalId(originalAssetId)
                                if (originalAssetSceneDetails != null) {
                                    sceneToEnsureExists = originalAssetSceneDetails.copy(userId = userId, isCustom = false, firestoreId = "")
                                } else {
                                    Log.e("FavoritesViewModel", "Cannot find details for asset scene original ID $originalAssetId. Skipping.")
                                    continue
                                }
                            }
                        } else {
                            Log.e("FavoritesViewModel", "Failed to check Firestore for asset scene $originalAssetId. Error: ${existingSceneResult.exceptionOrNull()?.message}. Skipping.")
                            continue
                        }
                    }
                }

                if (sceneToEnsureExists != null) {
                    Log.d("FavoritesViewModel", "Ensuring asset scene copy in Firestore: ${sceneToEnsureExists.title}")
                    val addSceneResult = scenesRepository.addScene(sceneToEnsureExists)
                    if (addSceneResult.isSuccess) {
                        val newlyAddedSceneResult = scenesRepository.getSceneByOriginalId(sceneToEnsureExists.id, userId)
                        if (newlyAddedSceneResult.isSuccess && newlyAddedSceneResult.getOrNull()?.firestoreId?.isNotBlank() == true) {
                            finalSceneItemIdForFavorite = newlyAddedSceneResult.getOrNull()!!.firestoreId
                            Log.d("FavoritesViewModel", "Asset scene ensured. FirestoreId for favorite: $finalSceneItemIdForFavorite")
                        } else {
                            Log.e("FavoritesViewModel", "Failed to get firestoreId after ensuring asset scene. Skipping favorite for ${sceneToEnsureExists.title}")
                            continue
                        }
                    } else {
                        Log.e("FavoritesViewModel", "Failed to ensure asset scene ${sceneToEnsureExists.title}. Error: ${addSceneResult.exceptionOrNull()?.message}. Skipping favorite.")
                        continue
                    }
                }

                if (!currentCloudItemIds.contains(finalSceneItemIdForFavorite)) {
                    favoritesToUpload.add(Favorite(itemId = finalSceneItemIdForFavorite, itemType = "scene", userId = userId))
                    Log.d("FavoritesViewModel", "Queued scene favorite for upload: itemId=$finalSceneItemIdForFavorite")
                } else {
                    Log.d("FavoritesViewModel", "Scene favorite for itemId=$finalSceneItemIdForFavorite already in cloud. Skipping.")
                }
            }

            // Process Position Favorites
            for (localPositionIdString in localPositionFavoriteIds) {
                // For positions, asset IDs (e.g., "asset_ImageName.jpg") are used directly as itemId.
                // Non-asset positions will have their Firestore-generated ID.
                // No special handling like scenes to create user-specific copies of assets.
                if (!currentCloudItemIds.contains(localPositionIdString)) {
                    favoritesToUpload.add(Favorite(itemId = localPositionIdString, itemType = "position", userId = userId))
                    Log.d("FavoritesViewModel", "Queued position favorite for upload: itemId=$localPositionIdString")
                } else {
                    Log.d("FavoritesViewModel", "Position favorite for itemId=$localPositionIdString already in cloud. Skipping.")
                }
            }

            if (favoritesToUpload.isNotEmpty()) {
                Log.d("FavoritesViewModel", "Uploading ${favoritesToUpload.size} new favorites to cloud: $favoritesToUpload")
                val batchResult = cloudRepository.addFavoritesBatch(favoritesToUpload)
                batchResult.onSuccess {
                    Log.d("FavoritesViewModel", "Successfully merged ${favoritesToUpload.size} local favorites to cloud.")
                    localFavoritesRepository.clearAllLocalFavorites() // Corrected method name
                    Log.d("FavoritesViewModel", "Cleared local favorites after successful merge.")
                    hasMergedThisSession = true // Set flag after successful merge and clear
                    Log.d("FavoritesViewModel", "hasMergedThisSession set to true.")
                }.onFailure { e ->
                    _error.value = "Failed to merge some local favorites to cloud: ${e.message}"
                    Log.e("FavoritesViewModel", "Error merging local favorites to cloud", e)
                    // Do not set hasMergedThisSession to true if merge fails, to allow retry.
                }
            } else {
                Log.d("FavoritesViewModel", "All local favorites already exist in the cloud. Clearing local.")
                localFavoritesRepository.clearAllLocalFavorites() // Corrected method name
                hasMergedThisSession = true // Set flag as effectively merged (or nothing to merge)
                Log.d("FavoritesViewModel", "hasMergedThisSession set to true (no new items to upload).")
            }
        } catch (e: Exception) {
            _error.value = "Error during merge process: ${e.message}"
            Log.e("FavoritesViewModel", "Exception during mergeLocalFavoritesWithCloud", e)
            // Do not set hasMergedThisSession to true on general exception
        } finally {
            _isMerging.value = false // Ensure merging flag is reset
            Log.d("FavoritesViewModel", "Merge process finished. hasMergedThisSession = $hasMergedThisSession, isMerging=${_isMerging.value}")
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

    override fun onCleared() {
        super.onCleared()
        firebaseAuthListener?.let { auth.removeAuthStateListener(it) }
        Log.d("FavoritesViewModel", "FavoritesViewModel onCleared, auth listener removed.")
    }
}
