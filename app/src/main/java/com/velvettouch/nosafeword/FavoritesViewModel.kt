package com.velvettouch.nosafeword

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.toObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val cloudRepository = FavoritesRepository()
    private val localFavoritesRepository = LocalFavoritesRepository(application.applicationContext)
    private val scenesRepository = ScenesRepository(application.applicationContext)

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
    private val mergeMutex = Mutex()
    private var mergeJob: Job? = null
    private val sessionAttemptedUploadItemIds = mutableSetOf<String>() // Track items attempted to upload this session


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
                        Log.d("FavoritesViewModel", "User changed or logged in (${user.uid}). Resetting session state.")
                        hasMergedThisSession = false
                        currentUserIdForMerge = user.uid
                        sessionAttemptedUploadItemIds.clear() // Clear session upload tracking
                        mergeJob?.cancel() 
                    }

                    if (!hasMergedThisSession && (mergeJob == null || mergeJob!!.isCompleted)) {
                        Log.d("FavoritesViewModel", "User logged in. Conditions met for merge. Launching merge job.")
                        mergeJob = viewModelScope.launch {
                            mergeLocalFavoritesWithCloud()
                        }
                    } else if (!hasMergedThisSession && mergeJob?.isActive == true) {
                        Log.d("FavoritesViewModel", "User logged in. Merge needed but a merge job is already active.")
                    } else if (hasMergedThisSession) {
                        Log.d("FavoritesViewModel", "User logged in. Merge already completed this session.")
                    }
                    loadAndListenToCloudFavorites()
                } else {
                    // User logged out
                    Log.d("FavoritesViewModel", "User logged out. Clearing session state.")
                    _favorites.value = emptyList()
                    _isLoading.value = false
                    mergeJob?.cancel() 
                    if (currentUserIdForMerge != null) { 
                        hasMergedThisSession = false
                        currentUserIdForMerge = null
                        sessionAttemptedUploadItemIds.clear() // Clear session upload tracking
                    }
                }
            }
        }
    }

    private fun loadAndListenToCloudFavorites() {
        val currentUser = _firebaseUser.value ?: return 

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            Log.d("FavoritesViewModel", "loadAndListenToCloudFavorites: User ${currentUser.uid}. Collecting from cloud repository.")

            cloudRepository.getFavorites()
                .catch { e ->
                    Log.e("FavoritesViewModel", "Error in getFavorites flow for user ${currentUser.uid}", e)
                    _error.value = "Failed to load favorites: ${e.message}"
                    _favorites.value = emptyList() 
                    _isLoading.value = false
                }
                .collectLatest { favoriteList -> 
                    Log.d("FavoritesViewModel", "Collected favorites for user ${currentUser.uid}. Count: ${favoriteList.size}")
                    _favorites.value = favoriteList
                    _isLoading.value = false 
                }
        }
    }
    
    fun refreshCloudFavorites(performMergeIfNeeded: Boolean = true) {
        val currentUser = _firebaseUser.value
        if (currentUser == null) {
            Log.d("FavoritesViewModel", "refreshCloudFavorites: User not logged in. No action.")
            _favorites.value = emptyList()
            return
        }

        if (performMergeIfNeeded && !hasMergedThisSession && (mergeJob == null || mergeJob!!.isCompleted)) {
            Log.d("FavoritesViewModel", "refreshCloudFavorites: Merge needed. Launching merge job.")
            mergeJob = viewModelScope.launch {
                mergeLocalFavoritesWithCloud()
            }
        } else {
            Log.d("FavoritesViewModel", "refreshCloudFavorites: Merge not needed or already in progress/completed for user ${currentUser.uid}.")
        }
    }

    private suspend fun mergeLocalFavoritesWithCloud() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        if (hasMergedThisSession) { 
            Log.d("FavoritesViewModel", "mergeLocalFavoritesWithCloud: Merge already completed this session for user $userId. Skipping.")
            return
        }

        if (!mergeMutex.tryLock()) {
            Log.d("FavoritesViewModel", "mergeLocalFavoritesWithCloud: Another merge operation is already in progress. Skipping.")
            return
        }

        _isMerging.value = true 
        Log.d("FavoritesViewModel", "Starting actual merge of local favorites to cloud for user $userId.")

        try {
            if (hasMergedThisSession) {
                Log.d("FavoritesViewModel", "mergeLocalFavoritesWithCloud: Merge completed by another call while waiting for lock. User $userId. Skipping.")
                return 
            }

            val localSceneFavoriteIds = localFavoritesRepository.getLocalFavoriteSceneIds()
            val localPositionFavoriteIds = localFavoritesRepository.getLocalFavoritePositionIds()

            if (localSceneFavoriteIds.isEmpty() && localPositionFavoriteIds.isEmpty()) {
                Log.d("FavoritesViewModel", "No local scene or position favorites to merge.")
                hasMergedThisSession = true 
                Log.d("FavoritesViewModel", "hasMergedThisSession set to true (no local items).")
                return
            }

            Log.d("FavoritesViewModel", "Local favorite scene IDs to merge: $localSceneFavoriteIds")
            Log.d("FavoritesViewModel", "Local favorite position IDs to merge: $localPositionFavoriteIds")

            val cloudFavoritesResult = cloudRepository.getCloudFavoritesListSnapshot()

            if (cloudFavoritesResult.isFailure) {
                _error.value = "Failed to fetch cloud favorites for merge: ${cloudFavoritesResult.exceptionOrNull()?.message}"
                Log.e("FavoritesViewModel", "Error fetching cloud favorites for merge", cloudFavoritesResult.exceptionOrNull())
                return
            }

            val currentCloudFavorites = cloudFavoritesResult.getOrNull() ?: emptyList()
            val currentCloudItemIds = currentCloudFavorites.map { it.itemId }.toSet()
            Log.d("FavoritesViewModel", "Current cloud favorite item IDs: $currentCloudItemIds. Session attempted uploads: $sessionAttemptedUploadItemIds")

            val favoritesToUpload = mutableListOf<Favorite>()
            val sceneIdPattern = "asset_(\\d+)".toRegex() 

            for (localSceneIdString in localSceneFavoriteIds) {
                var finalSceneItemIdForFavorite = localSceneIdString
                var sceneToEnsureExists: Scene? = null

                val matchResult = sceneIdPattern.matchEntire(localSceneIdString)
                if (matchResult != null) {
                    val originalAssetId = matchResult.groupValues[1].toIntOrNull()
                    if (originalAssetId != null) {
                        val existingSceneResult = scenesRepository.getSceneByOriginalId(originalAssetId, userId)
                        if (existingSceneResult.isSuccess) {
                            val existingFirestoreScene = existingSceneResult.getOrNull()
                            if (existingFirestoreScene?.firestoreId?.isNotBlank() == true) {
                                finalSceneItemIdForFavorite = existingFirestoreScene.firestoreId
                            } else {
                                val originalAssetSceneDetails = scenesRepository.getAssetSceneDetailsByOriginalId(originalAssetId)
                                if (originalAssetSceneDetails != null) {
                                    sceneToEnsureExists = originalAssetSceneDetails.copy(userId = userId, isCustom = false, firestoreId = "")
                                } else { continue }
                            }
                        } else { continue }
                    }
                }

                if (sceneToEnsureExists != null) {
                    val addSceneResult = scenesRepository.addScene(sceneToEnsureExists)
                    if (addSceneResult.isSuccess) {
                        val newlyAddedSceneResult = scenesRepository.getSceneByOriginalId(sceneToEnsureExists.id, userId) 
                        if (newlyAddedSceneResult.isSuccess && newlyAddedSceneResult.getOrNull()?.firestoreId?.isNotBlank() == true) {
                            finalSceneItemIdForFavorite = newlyAddedSceneResult.getOrNull()!!.firestoreId
                        } else { continue }
                    } else { continue }
                }

                if (!currentCloudItemIds.contains(finalSceneItemIdForFavorite) && !sessionAttemptedUploadItemIds.contains(finalSceneItemIdForFavorite)) {
                    favoritesToUpload.add(Favorite(itemId = finalSceneItemIdForFavorite, itemType = "scene", userId = userId))
                    Log.d("FavoritesViewModel", "Queued scene favorite for upload: itemId=$finalSceneItemIdForFavorite")
                } else {
                    Log.d("FavoritesViewModel", "Scene favorite for itemId=$finalSceneItemIdForFavorite already in cloud or session cache. Skipping.")
                }
            }

            for (localPositionIdString in localPositionFavoriteIds) {
                if (!currentCloudItemIds.contains(localPositionIdString) && !sessionAttemptedUploadItemIds.contains(localPositionIdString)) {
                    favoritesToUpload.add(Favorite(itemId = localPositionIdString, itemType = "position", userId = userId))
                    Log.d("FavoritesViewModel", "Queued position favorite for upload: itemId=$localPositionIdString")
                } else {
                    Log.d("FavoritesViewModel", "Position favorite for itemId=$localPositionIdString already in cloud or session cache. Skipping.")
                }
            }

            if (favoritesToUpload.isNotEmpty()) {
                Log.d("FavoritesViewModel", "Uploading ${favoritesToUpload.size} new favorites to cloud: $favoritesToUpload")
                val batchResult = cloudRepository.addFavoritesBatch(favoritesToUpload)
                batchResult.onSuccess {
                    Log.d("FavoritesViewModel", "Successfully merged ${favoritesToUpload.size} local favorites to cloud.")
                    favoritesToUpload.forEach { uploadedFav -> sessionAttemptedUploadItemIds.add(uploadedFav.itemId) } // Add to session cache
                    localFavoritesRepository.clearAllLocalFavorites() 
                    Log.d("FavoritesViewModel", "Cleared local favorites after successful merge.")
                    hasMergedThisSession = true 
                    Log.d("FavoritesViewModel", "hasMergedThisSession set to true.")
                }.onFailure { e ->
                    _error.value = "Failed to merge some local favorites to cloud: ${e.message}"
                    Log.e("FavoritesViewModel", "Error merging local favorites to cloud", e)
                }
            } else {
                Log.d("FavoritesViewModel", "All local favorites already exist in the cloud or session cache. Clearing local if any (though should be empty if all in session cache).")
                localFavoritesRepository.clearAllLocalFavorites() 
                hasMergedThisSession = true 
                Log.d("FavoritesViewModel", "hasMergedThisSession set to true (no new items to upload).")
            }
        } catch (e: Exception) {
            _error.value = "Error during merge process: ${e.message}"
            Log.e("FavoritesViewModel", "Exception during mergeLocalFavoritesWithCloud", e)
        } finally {
            _isMerging.value = false 
            mergeMutex.unlock() 
            Log.d("FavoritesViewModel", "Merge process finished. Mutex unlocked. hasMergedThisSession = $hasMergedThisSession, isMerging=${_isMerging.value}")
        }
    }

    fun addCloudFavorite(itemId: String, itemType: String) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                _error.value = "Cannot add cloud favorite: User not logged in."
                return@launch
            }
            _isLoading.value = true 
            val result = cloudRepository.addFavorite(itemId, itemType)
            result.onSuccess { // If adding directly to cloud, also add to session cache
                sessionAttemptedUploadItemIds.add(itemId)
            }
            result.onFailure { e ->
                _error.value = "Failed to add cloud favorite: ${e.message}"
            }
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
            result.onSuccess { // If removing from cloud, also remove from session cache
                sessionAttemptedUploadItemIds.remove(itemId)
            }
            result.onFailure { e ->
                _error.value = "Failed to remove cloud favorite: ${e.message}"
            }
            _isLoading.value = false
        }
    }

    fun isCloudItemFavorited(itemId: String, itemType: String, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                callback(false)
                return@launch
            }
            // Check session cache first for a quick affirmative
            if (sessionAttemptedUploadItemIds.contains(itemId)) {
                callback(true)
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
        mergeJob?.cancel() 
        Log.d("FavoritesViewModel", "FavoritesViewModel onCleared, auth listener removed, merge job cancelled.")
    }
}
