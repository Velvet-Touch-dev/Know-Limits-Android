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


    init {
        // Listen to auth changes to reset merge flag if user logs out and back in
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val newUser = auth.currentUser
            if (newUser == null) {
                // User logged out, reset merge flag for next login
                if (currentUserIdForMerge != null) { // Only reset if there was a logged-in user
                    Log.d("FavoritesViewModel", "User logged out. Resetting merge flag.")
                    hasMergedThisSession = false
                    currentUserIdForMerge = null
                }
            } else {
                // User logged in or changed
                if (currentUserIdForMerge != newUser.uid) {
                    Log.d("FavoritesViewModel", "User changed or logged in (${newUser.uid}). Resetting merge flag.")
                    hasMergedThisSession = false
                    currentUserIdForMerge = newUser.uid
                }
            }
        }
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
                hasMergedThisSession = false // Reset merge flag if user becomes null
                currentUserIdForMerge = null
                return@launch
            }

            // Update currentUserIdForMerge if it's different (e.g., first load after login)
            if (currentUserIdForMerge != currentUser.uid) {
                Log.d("FavoritesViewModel", "User ID changed to ${currentUser.uid}. Resetting merge flag.")
                hasMergedThisSession = false
                currentUserIdForMerge = currentUser.uid
            }

            if (performMerge) {
                if (!hasMergedThisSession) {
                    if (!_isMerging.value) { // Check if another merge is already in progress
                        Log.d("FavoritesViewModel", "performMerge is true, not merged this session, and not currently merging. Calling mergeLocalFavoritesWithCloud.")
                        mergeLocalFavoritesWithCloud() // This is a suspend function
                    } else {
                        Log.d("FavoritesViewModel", "performMerge is true, not merged this session, but a merge is already in progress. Skipping new merge call.")
                    }
                } else {
                    Log.d("FavoritesViewModel", "performMerge is true but hasMergedThisSession is true. Skipping merge.")
                }
            }

            // Proceed to load cloud favorites regardless of merge status (merge might be ongoing or skipped)
            // The collection of getFavorites() will pick up changes once the merge (if any) is done and data propagates.
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
            val sceneIdPattern = "asset_(\\d+)".toRegex()

            for (localIdString in localFavoriteIds) {
                var finalItemIdForFavorite = localIdString
                var sceneToEnsureExists: Scene? = null

                // Check if it's an asset scene that needs its Firestore ID resolved or to be created
                val matchResult = sceneIdPattern.matchEntire(localIdString)
                if (matchResult != null) {
                    val originalAssetId = matchResult.groupValues[1].toIntOrNull()
                    if (originalAssetId != null) {
                        Log.d("FavoritesViewModel", "Local favorite '$localIdString' is an asset scene (original ID: $originalAssetId). Checking its Firestore status.")
                        // Check if this asset scene already has a user-specific instance in Firestore
                        val existingSceneResult = scenesRepository.getSceneByOriginalId(originalAssetId, userId)

                        if (existingSceneResult.isSuccess) {
                            val existingFirestoreScene = existingSceneResult.getOrNull()
                            if (existingFirestoreScene?.firestoreId?.isNotBlank() == true) {
                                finalItemIdForFavorite = existingFirestoreScene.firestoreId
                                Log.d("FavoritesViewModel", "Asset scene $originalAssetId already exists in Firestore for user $userId with firestoreId ${finalItemIdForFavorite}. Using this ID for favorite.")
                            } else {
                                // Asset scene does not exist for this user in Firestore.
                                // We need to create it first.
                                Log.d("FavoritesViewModel", "Asset scene $originalAssetId does not exist in Firestore for user $userId. Will create it.")
                                // Load the original asset scene details using the new method in ScenesRepository
                                val originalAssetSceneDetails = scenesRepository.getAssetSceneDetailsByOriginalId(originalAssetId)

                                if (originalAssetSceneDetails != null) {
                                    Log.d("FavoritesViewModel", "Found asset details for originalId $originalAssetId: ${originalAssetSceneDetails.title}")
                                    sceneToEnsureExists = originalAssetSceneDetails.copy(
                                        userId = userId,
                                        isCustom = false, // It's a user's instance of a default scene
                                        firestoreId = "" // Let addScene generate a new Firestore ID
                                    )
                                } else {
                                    Log.e("FavoritesViewModel", "Could not find details for asset scene original ID $originalAssetId in assets. Skipping merge for this item.")
                                    continue // Skip this local favorite
                                }
                            }
                        } else {
                            Log.e("FavoritesViewModel", "Failed to check Firestore for asset scene $originalAssetId. Error: ${existingSceneResult.exceptionOrNull()?.message}. Skipping merge for this item.")
                            continue // Skip this local favorite
                        }
                    }
                }

                // If sceneToEnsureExists is not null, it means we need to add/update it in the scenes collection first
                if (sceneToEnsureExists != null) {
                    Log.d("FavoritesViewModel", "Adding/ensuring asset scene copy in Firestore: ${sceneToEnsureExists.title}")
                    val addSceneResult = scenesRepository.addScene(sceneToEnsureExists) // addScene handles if firestoreId is blank (new) or present (update)
                    if (addSceneResult.isSuccess) {
                        // We need the new firestoreId if it was generated.
                        // Re-fetch the scene by original ID to get its new firestoreId.
                        val newlyAddedSceneResult = scenesRepository.getSceneByOriginalId(sceneToEnsureExists.id, userId)
                        if (newlyAddedSceneResult.isSuccess && newlyAddedSceneResult.getOrNull()?.firestoreId?.isNotBlank() == true) {
                            finalItemIdForFavorite = newlyAddedSceneResult.getOrNull()!!.firestoreId
                            Log.d("FavoritesViewModel", "Successfully added/ensured asset scene. New/confirmed firestoreId for favorite: $finalItemIdForFavorite")
                        } else {
                            Log.e("FavoritesViewModel", "Failed to retrieve scene after adding to get its firestoreId. Skipping favorite for ${sceneToEnsureExists.title}")
                            continue
                        }
                    } else {
                        Log.e("FavoritesViewModel", "Failed to add asset scene ${sceneToEnsureExists.title} to scenes collection. Error: ${addSceneResult.exceptionOrNull()?.message}. Skipping favorite.")
                        continue
                    }
                }

                // Now, check if this finalItemIdForFavorite is already in cloud favorites
                if (!currentCloudItemIds.contains(finalItemIdForFavorite)) {
                    favoritesToUpload.add(Favorite(itemId = finalItemIdForFavorite, itemType = "scene", userId = userId))
                    Log.d("FavoritesViewModel", "Queued favorite for upload: itemId=$finalItemIdForFavorite")
                } else {
                    Log.d("FavoritesViewModel", "Favorite for itemId=$finalItemIdForFavorite already exists in cloud. Skipping add to batch.")
                }
            }

            if (favoritesToUpload.isNotEmpty()) {
                Log.d("FavoritesViewModel", "Uploading ${favoritesToUpload.size} new favorites to cloud: $favoritesToUpload")
                val batchResult = cloudRepository.addFavoritesBatch(favoritesToUpload)
                batchResult.onSuccess {
                    Log.d("FavoritesViewModel", "Successfully merged ${favoritesToUpload.size} local favorites to cloud.")
                    localFavoritesRepository.clearLocalFavoriteScenes()
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
                localFavoritesRepository.clearLocalFavoriteScenes()
                hasMergedThisSession = true // Set flag as effectively merged (or nothing to merge)
                Log.d("FavoritesViewModel", "hasMergedThisSession set to true (no new items to upload).")
            }
        } catch (e: Exception) {
            _error.value = "Error during merge process: ${e.message}"
            Log.e("FavoritesViewModel", "Exception during mergeLocalFavoritesWithCloud", e)
            // Do not set hasMergedThisSession to true on general exception
        } finally {
            _isMerging.value = false
            Log.d("FavoritesViewModel", "Merge process finished. hasMergedThisSession = $hasMergedThisSession")
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