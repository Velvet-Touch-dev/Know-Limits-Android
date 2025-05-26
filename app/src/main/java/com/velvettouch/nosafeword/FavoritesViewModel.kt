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
import kotlinx.coroutines.sync.withLock // For cleaner mutex usage if preferred, but tryLock is used here.

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val GLOBAL_MERGE_MUTEX = Mutex()
        private const val TAG = "FavoritesViewModel" // ViewModel specific TAG
    }

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
    // private val mergeMutex = Mutex() // Instance mutex removed, use GLOBAL_MERGE_MUTEX
    private var mergeJob: Job? = null // Job to manage the merge coroutine for this instance
    private val sessionAttemptedUploadItemIds = mutableSetOf<String>()


    init {
        firebaseAuthListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            _firebaseUser.value = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(firebaseAuthListener!!)

        viewModelScope.launch {
            _firebaseUser.collectLatest { user ->
                Log.d(TAG, "Auth state changed via _firebaseUser. User: ${user?.uid}")
                if (user != null) {
                    var needsMergeInitialCheck = false
                    if (currentUserIdForMerge != user.uid) {
                        Log.d(TAG, "User changed or new login (${user.uid}). Current session merge user was: $currentUserIdForMerge. Resetting VM instance session state.")
                        mergeJob?.cancel() // Cancel job for the OLD user if any, or current if user changed rapidly.
                        hasMergedThisSession = false // Reset flag for this VM instance for the new user.
                        currentUserIdForMerge = user.uid // Track current user for this VM instance.
                        sessionAttemptedUploadItemIds.clear() // Clear instance-specific session cache.
                        needsMergeInitialCheck = true // New user for this VM instance, definitely needs a merge consideration.
                    }

                    // If not merged for the current user in this VM instance's state
                    if (!hasMergedThisSession) {
                        needsMergeInitialCheck = true
                    }

                    if (needsMergeInitialCheck && (mergeJob == null || !mergeJob!!.isActive)) {
                        Log.d(TAG, "Init: Conditions met for merge for user ${user.uid}. Launching merge job. HasMergedThisSession=$hasMergedThisSession for this VM instance.")
                        mergeJob = viewModelScope.launch {
                            mergeLocalFavoritesWithCloud(user.uid) // Pass user.uid
                        }
                    } else if (mergeJob?.isActive == true) {
                        Log.d(TAG, "Init: Merge attempt for ${user.uid}, but a merge job from this VM instance is already active.")
                    } else if (hasMergedThisSession) {
                        Log.d(TAG, "Init: Merge already completed this session for ${user.uid} by this VM instance.")
                    }
                    loadAndListenToCloudFavorites(user.uid) // Pass UID
                } else {
                    // User logged out
                    mergeJob?.cancel() // Cancel any ongoing job
                    if (currentUserIdForMerge != null) {
                        Log.d(TAG, "User logged out. Clearing VM instance session state for $currentUserIdForMerge.")
                        hasMergedThisSession = false
                        currentUserIdForMerge = null
                        sessionAttemptedUploadItemIds.clear()
                    }
                    _favorites.value = emptyList()
                    _isLoading.value = false
                }
            }
        }
    }

    private fun loadAndListenToCloudFavorites(userId: String) { // Accept userId
        // val currentUser = _firebaseUser.value ?: return // Use passed userId

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            Log.d(TAG, "loadAndListenToCloudFavorites: User $userId. Collecting from cloud repository.")

            cloudRepository.getFavorites() // Assumes getFavorites internally uses the correct current user from FirebaseAuth
                .catch { e ->
                    Log.e(TAG, "Error in getFavorites flow for user $userId", e)
                    _error.value = "Failed to load favorites: ${e.message}"
                    _favorites.value = emptyList()
                    _isLoading.value = false
                }
                .collectLatest { favoriteList ->
                    Log.d(TAG, "Collected favorites for user $userId. Count: ${favoriteList.size}")
                    _favorites.value = favoriteList
                    _isLoading.value = false
                }
        }
    }

    fun refreshCloudFavorites(performMergeIfNeeded: Boolean = true) {
        val currentUser = _firebaseUser.value
        if (currentUser == null) {
            Log.d(TAG, "refreshCloudFavorites: User not logged in. No action.")
            _favorites.value = emptyList()
            return
        }

        // Check hasMergedThisSession for the *specific current user* of this VM instance
        // And ensure the current user of the VM instance matches the actual current auth user.
        val needsMergeRefresh = performMergeIfNeeded &&
                                currentUserIdForMerge == currentUser.uid &&
                                !hasMergedThisSession &&
                                (mergeJob == null || !mergeJob!!.isActive)

        if (needsMergeRefresh) {
            Log.d(TAG, "refreshCloudFavorites: Merge needed for ${currentUser.uid}. Launching merge job.")
            mergeJob = viewModelScope.launch {
                mergeLocalFavoritesWithCloud(currentUser.uid) // Pass uid
            }
        } else {
            var reason = ""
            if (!performMergeIfNeeded) reason = "performMergeIfNeeded is false."
            else if (currentUserIdForMerge != currentUser.uid) reason = "VM instance user ($currentUserIdForMerge) doesn't match current auth user (${currentUser.uid}). Auth listener should handle."
            else if (hasMergedThisSession) reason = "Merge already done this session for ${currentUser.uid} by this VM instance."
            else if (mergeJob?.isActive == true) reason = "Merge job already active for ${currentUser.uid} from this VM instance."
            Log.d(TAG, "refreshCloudFavorites: Merge not launched for user ${currentUser.uid}. Reason: $reason")
        }
    }

    private suspend fun mergeLocalFavoritesWithCloud(userId: String) { // Accept userId
        // val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return // Use passed userId from caller

        if (currentUserIdForMerge != userId || hasMergedThisSession) {
            Log.d(TAG, "mergeLocalFavoritesWithCloud for $userId: Merge already completed this session by this VM instance (hasMergedThisSession=$hasMergedThisSession) or user mismatch (currentUserIdForMerge=$currentUserIdForMerge). Skipping actual merge logic.")
            return
        }

        if (!GLOBAL_MERGE_MUTEX.tryLock()) {
            Log.d(TAG, "mergeLocalFavoritesWithCloud for $userId: GLOBAL_MERGE_MUTEX is already locked by another operation. Skipping.")
            return
        }

        _isMerging.value = true
        Log.d(TAG, "Starting actual merge of local favorites to cloud for user $userId. GLOBAL_MERGE_MUTEX acquired.")

        try {
            // Re-check instance specific flag after acquiring global mutex, in case state changed.
            if (currentUserIdForMerge != userId || hasMergedThisSession) {
                Log.d(TAG, "mergeLocalFavoritesWithCloud for $userId: Re-check after lock: Merge already completed or user changed. Skipping.")
                return
            }

            val localSceneFavoriteIds = localFavoritesRepository.getLocalFavoriteSceneIds() // This should be a singleton repository
            val localPositionFavoriteIds = localFavoritesRepository.getLocalFavoritePositionIds() // Same singleton

            if (localSceneFavoriteIds.isEmpty() && localPositionFavoriteIds.isEmpty()) {
                Log.d(TAG, "No local scene or position favorites to merge for user $userId.")
                hasMergedThisSession = true // Mark as merged for this instance since no local items.
                Log.d(TAG, "hasMergedThisSession set to true for $userId (no local items) in this VM instance.")
                return
            }

            Log.d(TAG, "Local favorite scene IDs to merge for $userId: $localSceneFavoriteIds")
            Log.d(TAG, "Local favorite position IDs to merge for $userId: $localPositionFavoriteIds")

            val cloudFavoritesResult = cloudRepository.getCloudFavoritesListSnapshot() // Reads from server

            if (cloudFavoritesResult.isFailure) {
                _error.value = "Failed to fetch cloud favorites for merge for $userId: ${cloudFavoritesResult.exceptionOrNull()?.message}"
                Log.e(TAG, "Error fetching cloud favorites for merge for $userId", cloudFavoritesResult.exceptionOrNull())
                return
            }

            val currentCloudFavorites = cloudFavoritesResult.getOrNull() ?: emptyList()
            val currentCloudItemIds = currentCloudFavorites.map { it.itemId }.toSet()
            // sessionAttemptedUploadItemIds is instance-specific, reset if user changes for this instance.
            Log.d(TAG, "For user $userId: Current cloud item IDs: $currentCloudItemIds. This VM instance's sessionAttemptedUploadItemIds: $sessionAttemptedUploadItemIds")

            val favoritesToUpload = mutableListOf<Favorite>() // Items this specific merge call will attempt
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
                                } else { Log.w(TAG, "Could not find asset scene details for originalId $originalAssetId"); continue }
                            }
                        } else { Log.w(TAG, "Failed to get existing scene by originalId $originalAssetId for user $userId"); continue }
                    }
                }

                if (sceneToEnsureExists != null) {
                    val addSceneResult = scenesRepository.addScene(sceneToEnsureExists)
                    if (addSceneResult.isSuccess) {
                        val newlyAddedSceneResult = scenesRepository.getSceneByOriginalId(sceneToEnsureExists.id, userId)
                        if (newlyAddedSceneResult.isSuccess && newlyAddedSceneResult.getOrNull()?.firestoreId?.isNotBlank() == true) {
                            finalSceneItemIdForFavorite = newlyAddedSceneResult.getOrNull()!!.firestoreId
                        } else { Log.w(TAG, "Failed to get newly added scene or its Firestore ID for originalId ${sceneToEnsureExists.id}, user $userId"); continue }
                    } else { Log.w(TAG, "Failed to add scene for originalId ${sceneToEnsureExists.id}, user $userId"); continue }
                }

                if (!currentCloudItemIds.contains(finalSceneItemIdForFavorite) && !sessionAttemptedUploadItemIds.contains(finalSceneItemIdForFavorite)) {
                    favoritesToUpload.add(Favorite(itemId = finalSceneItemIdForFavorite, itemType = "scene", userId = userId))
                    Log.d(TAG, "User $userId: Queued scene favorite for upload: itemId=$finalSceneItemIdForFavorite")
                } else {
                    Log.d(TAG, "User $userId: Scene favorite for itemId=$finalSceneItemIdForFavorite already in cloud or this VM's session cache. Skipping.")
                }
            }

            for (localPositionIdString in localPositionFavoriteIds) {
                if (!currentCloudItemIds.contains(localPositionIdString) && !sessionAttemptedUploadItemIds.contains(localPositionIdString)) {
                    favoritesToUpload.add(Favorite(itemId = localPositionIdString, itemType = "position", userId = userId))
                    Log.d(TAG, "User $userId: Queued position favorite for upload: itemId=$localPositionIdString")
                } else {
                    Log.d(TAG, "User $userId: Position favorite for itemId=$localPositionIdString already in cloud or this VM's session cache. Skipping.")
                }
            }

            if (favoritesToUpload.isNotEmpty()) {
                favoritesToUpload.forEach { favToUpload -> sessionAttemptedUploadItemIds.add(favToUpload.itemId) }
                Log.d(TAG, "User $userId: Uploading ${favoritesToUpload.size} new favorites. Items added to this VM's sessionAttemptedUploadItemIds: $sessionAttemptedUploadItemIds. Batch: $favoritesToUpload")

                val batchResult = cloudRepository.addFavoritesBatch(favoritesToUpload)
                batchResult.onSuccess {
                    Log.d(TAG, "User $userId: Successfully merged ${favoritesToUpload.size} local favorites to cloud.")
                    localFavoritesRepository.clearAllLocalFavorites()
                    Log.d(TAG, "User $userId: Cleared local favorites after successful merge.")
                    hasMergedThisSession = true // Mark as merged for this VM instance.
                    Log.d(TAG, "hasMergedThisSession set to true for $userId in this VM instance.")
                }.onFailure { e ->
                    _error.value = "Failed to merge some local favorites to cloud for $userId: ${e.message}"
                    Log.e(TAG, "Error merging local favorites to cloud for $userId", e)
                    // Items remain in sessionAttemptedUploadItemIds for this VM instance, preventing re-attempt by this instance in this session.
                }
            } else {
                Log.d(TAG, "User $userId: All local favorites already exist in the cloud or this VM's session cache. Clearing local (if any).")
                localFavoritesRepository.clearAllLocalFavorites()
                hasMergedThisSession = true // Mark as merged for this VM instance.
                Log.d(TAG, "hasMergedThisSession set to true for $userId (no new items to upload) in this VM instance.")
            }
        } catch (e: Exception) {
            _error.value = "Error during merge process for $userId: ${e.message}"
            Log.e(TAG, "Exception during mergeLocalFavoritesWithCloud for $userId", e)
        } finally {
            _isMerging.value = false
            GLOBAL_MERGE_MUTEX.unlock()
            Log.d(TAG, "Merge process for $userId finished. GLOBAL_MERGE_MUTEX unlocked. For this VM instance: hasMergedThisSession = $hasMergedThisSession, isMerging=${_isMerging.value}")
        }
    }

    fun addCloudFavorite(itemId: String, itemType: String) {
        viewModelScope.launch {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                _error.value = "Cannot add cloud favorite: User not logged in."
                return@launch
            }
            // Ensure this operation is for the user this VM instance is currently tracking
            if (currentUserIdForMerge != currentUser.uid) {
                _error.value = "User mismatch. Cannot add favorite. Expected ${currentUserIdForMerge}, got ${currentUser.uid}."
                Log.w(TAG, "addCloudFavorite: User mismatch. VM for $currentUserIdForMerge, auth is ${currentUser.uid}.")
                return@launch
            }
            _isLoading.value = true
            val result = cloudRepository.addFavorite(itemId, itemType) // This has its own server-side check
            result.onSuccess {
                sessionAttemptedUploadItemIds.add(itemId) // Add to this VM instance's session cache
                Log.d(TAG, "addCloudFavorite for ${currentUser.uid}: Added $itemId to session cache.")
            }
            result.onFailure { e ->
                _error.value = "Failed to add cloud favorite for ${currentUser.uid}: ${e.message}"
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
            if (currentUserIdForMerge != currentUser.uid) {
                 Log.w(TAG, "removeCloudFavorite: User mismatch. VM for $currentUserIdForMerge, auth is ${currentUser.uid}.")
                _error.value = "User mismatch. Cannot remove favorite."
                return@launch
            }
            _isLoading.value = true
            val result = cloudRepository.removeFavorite(itemId, itemType)
            result.onSuccess {
                sessionAttemptedUploadItemIds.remove(itemId) // Remove from this VM instance's session cache
                 Log.d(TAG, "removeCloudFavorite for ${currentUser.uid}: Removed $itemId from session cache.")
            }
            result.onFailure { e ->
                _error.value = "Failed to remove cloud favorite for ${currentUser.uid}: ${e.message}"
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
            if (currentUserIdForMerge != currentUser.uid) {
                Log.w(TAG, "isCloudItemFavorited: User mismatch. VM for $currentUserIdForMerge, auth is ${currentUser.uid}. Reporting false.")
                callback(false) // Cannot give reliable answer for a different user
                return@launch
            }
            // Check this VM instance's session cache first
            if (sessionAttemptedUploadItemIds.contains(itemId)) {
                callback(true)
                return@launch
            }
            val result = cloudRepository.isItemFavorited(itemId, itemType) // Server check
            result.onSuccess { isFavorited ->
                callback(isFavorited)
            }.onFailure { e ->
                _error.value = "Failed to check cloud favorite status for ${currentUser.uid}: ${e.message}"
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
        mergeJob?.cancel() // Cancel job if VM is cleared
        Log.d(TAG, "FavoritesViewModel instance for user $currentUserIdForMerge onCleared. Auth listener removed, merge job cancelled.")
    }
}
