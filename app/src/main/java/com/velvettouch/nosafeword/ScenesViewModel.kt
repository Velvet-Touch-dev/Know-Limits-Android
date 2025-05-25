package com.velvettouch.nosafeword

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext // Added import
import kotlinx.coroutines.Dispatchers // Added import
// import kotlinx.coroutines.flow.first // Not used anymore
import java.io.IOException

class ScenesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScenesRepository(application.applicationContext)
    private val localFavoritesRepository = LocalFavoritesRepository(application.applicationContext) // Added
    private val auth = FirebaseAuth.getInstance()
    private val appContext = application.applicationContext
    private var localScenesWhenLoggedOut: MutableList<Scene> = mutableListOf() // This might be simplified or removed
    @Volatile private var isSeedingInProgress = false
    @Volatile private var isSyncingLocalScenes = false // Mark volatile

    private companion object {
        private const val TAG = "ScenesViewModel"
        private const val SCENES_ASSET_FILENAME = "scenes.json"
        private const val PREF_DEFAULT_SCENES_SEEDED = "default_scenes_seeded_"
        private const val PREFS_APP_NAME = "NoSafeWordAppPrefs" // General app prefs
        private const val KEY_DELETED_LOGGED_OUT_SCENE_IDS = "deleted_logged_out_scene_ids" // Tracks default scenes deleted locally
        private const val KEY_LOGGED_OUT_LOCAL_SCENES_JSON = "logged_out_local_scenes_json" // Stores the full list of local scenes
        private const val KEY_LAST_LOGGED_OUT_USER_ID = "last_logged_out_user_id" // Stores the UID of the user who last logged out
    }

    private val gson = Gson() // For JSON serialization

    private fun saveLocalScenesToPrefs(context: Context, scenes: List<Scene>) {
        try {
            val jsonString = gson.toJson(scenes)
            val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_LOGGED_OUT_LOCAL_SCENES_JSON, jsonString).apply()
            Log.d(TAG, "Saved ${scenes.size} local scenes to SharedPreferences.")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local scenes to SharedPreferences", e)
        }
    }

    private fun loadLocalScenesFromPrefs(context: Context): MutableList<Scene>? {
        val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_LOGGED_OUT_LOCAL_SCENES_JSON, null)
        return if (jsonString != null) {
            try {
                val typeToken = object : TypeToken<MutableList<Scene>>() {}.type
                val loadedScenes = gson.fromJson<MutableList<Scene>>(jsonString, typeToken)
                Log.d(TAG, "Loaded ${loadedScenes?.size ?: 0} local scenes from SharedPreferences.")
                loadedScenes
            } catch (e: Exception) {
                Log.e(TAG, "Error loading local scenes from SharedPreferences", e)
                null
            }
        } else {
            Log.d(TAG, "No local scenes found in SharedPreferences.")
            null
        }
    }

    private fun getDeletedLoggedOutSceneIds(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DELETED_LOGGED_OUT_SCENE_IDS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveDeletedLoggedOutSceneIds(context: Context, ids: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DELETED_LOGGED_OUT_SCENE_IDS, ids).apply()
    }

    // Helper to get a storable, unique ID for a scene when it's managed locally (logged out)
    // This ID is used for tracking deletions in SharedPreferences.
    private fun getPersistentIdentifierForLocalScene(scene: Scene): String? {
        return if (scene.firestoreId.startsWith("local_")) {
            // Locally created or an asset scene that was edited offline and got a local_ ID
            scene.firestoreId
        } else if (!scene.firestoreId.isBlank()) {
            // Scene has a non-local_ firestoreId (synced from Firestore, could be custom or a user's copy of a default)
            // This is the primary identifier for scenes that have been in Firestore.
            scene.firestoreId
        } else if (scene.id != 0 && scene.firestoreId.isBlank()) {
            // It's a pristine default scene from assets, not yet touched or synced for the user.
            // Its original int ID is used to form a key for local deletion tracking.
            "default_${scene.id}"
        } else {
            // Fallback or error case
            Log.e(TAG, "getPersistentIdentifierForLocalScene: Could not determine persistent ID for scene: title='${scene.title}', firestoreId='${scene.firestoreId}', originalIntId='${scene.id}'")
            null
        }
    }

    private val _scenes = MutableStateFlow<List<Scene>>(emptyList())
    val scenes: StateFlow<List<Scene>> = _scenes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // StateFlow to represent the current Firebase user
    private val _firebaseUser = MutableStateFlow(auth.currentUser)
    private var firebaseAuthListener: FirebaseAuth.AuthStateListener? = null // Store the listener

    init {
        firebaseAuthListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val previousUser = _firebaseUser.value // Capture before update
            val newUser = firebaseAuth.currentUser
            _firebaseUser.value = newUser

            if (previousUser != null && newUser == null) { // User just logged out
                val appPrefs = appContext.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
                appPrefs.edit().putString(KEY_LAST_LOGGED_OUT_USER_ID, previousUser.uid).apply()
                Log.d(TAG, "User ${previousUser.uid} logged out (detected in AuthStateListener). Stored as $KEY_LAST_LOGGED_OUT_USER_ID.")
            }
        }
        auth.addAuthStateListener(firebaseAuthListener!!)

        viewModelScope.launch {
            _firebaseUser.collectLatest { user ->
                Log.d(TAG, "Auth state collected via StateFlow. User: ${user?.uid}")
                handleAuthState(user)
            }
        }
    }

    private suspend fun handleAuthState(currentUser: com.google.firebase.auth.FirebaseUser?) {
        if (currentUser != null) {
            Log.d(TAG, "User is logged in: ${currentUser.uid}.")
            _isLoading.value = true // Set loading true at the beginning of logged-in handling

            val appPrefs = appContext.getSharedPreferences(PREFS_APP_NAME, Context.MODE_PRIVATE)
            val lastLoggedOutUserId = appPrefs.getString(KEY_LAST_LOGGED_OUT_USER_ID, null)
            val deviceOfflineScenes = loadLocalScenesFromPrefs(appContext) // From NoSafeWordAppPrefs (primary offline store)

            var didAttemptSync = false
            if (deviceOfflineScenes != null && deviceOfflineScenes.isNotEmpty()) {
                if (currentUser.uid == lastLoggedOutUserId) {
                    Log.d(TAG, "Current user ${currentUser.uid} matches last logged out user. Processing ${deviceOfflineScenes.size} device offline scenes.")
                    val customOrNewDeviceOfflineScenes = deviceOfflineScenes.filter { it.isCustom || it.id == 0 }
                    if (customOrNewDeviceOfflineScenes.isNotEmpty()) {
                        if (!isSyncingLocalScenes) {
                            isSyncingLocalScenes = true
                            didAttemptSync = true
                            try {
                                Log.d(TAG, "Attempting to sync ${customOrNewDeviceOfflineScenes.size} custom/new scenes from device offline storage for user ${currentUser.uid}.")
                                syncLocalScenesToFirestore(currentUser.uid, customOrNewDeviceOfflineScenes)
                            } finally {
                                isSyncingLocalScenes = false
                            }
                        } else {
                             Log.d(TAG, "Sync already in progress for custom/new device offline scenes. Skipping additional attempt.")
                        }
                    } else {
                        Log.d(TAG, "No custom/new scenes found in device offline storage (NoSafeWordAppPrefs) to sync for user ${currentUser.uid}.")
                    }
                } else {
                    Log.w(TAG, "Current user ${currentUser.uid} does not match last logged out user ($lastLoggedOutUserId). Discarding ${deviceOfflineScenes.size} device offline scenes from NoSafeWordAppPrefs.")
                }
                // Clear device-specific offline storage and last user ID after processing or discarding.
                saveLocalScenesToPrefs(appContext, emptyList()) // Clears NoSafeWordAppPrefs
                appPrefs.edit().remove(KEY_LAST_LOGGED_OUT_USER_ID).apply()
                Log.d(TAG, "Cleared device offline scene storage (NoSafeWordAppPrefs) and $KEY_LAST_LOGGED_OUT_USER_ID.")
            } else {
                Log.d(TAG, "No device offline scenes found in NoSafeWordAppPrefs to process for user ${currentUser.uid}.")
                // Also ensure last logged out user ID is cleared if there were no scenes to process,
                // as its relevance is tied to the presence of offline scenes.
                if (lastLoggedOutUserId != null) {
                    appPrefs.edit().remove(KEY_LAST_LOGGED_OUT_USER_ID).apply()
                    Log.d(TAG, "Cleared $KEY_LAST_LOGGED_OUT_USER_ID as there were no offline scenes.")
                }
            }

            // Ensure ViewModel's immediate cache `localScenesWhenLoggedOut` is clear before loading fresh for the current user.
            // This cache is primarily for edits made while the app is in a logged-out state.
            if (localScenesWhenLoggedOut.isNotEmpty()) {
                Log.d(TAG, "Clearing ViewModel's localScenesWhenLoggedOut cache (had ${localScenesWhenLoggedOut.size} scenes) before loading fresh for ${currentUser.uid}.")
                localScenesWhenLoggedOut.clear()
            }

            // If a sync was attempted, it might have already updated isLoading.
            // loadScenesFromFirestore will manage isLoading for its own operations.
            // If no sync was attempted, loadScenesFromFirestore is responsible for setting isLoading.
            loadScenesFromFirestore() // Load scenes for the current user. This will also handle isLoading.

        } else { // User is logged out
            Log.d(TAG, "Auth state: User is logged out.")
            _isLoading.value = true

            // Prioritize loading from NoSafeWordAppPrefs (primary offline storage)
            var scenesToDisplay = loadLocalScenesFromPrefs(appContext)?.toMutableList()

            if (scenesToDisplay == null || scenesToDisplay.isEmpty()) {
                Log.d(TAG, "No scenes in NoSafeWordAppPrefs or empty. Trying LocalFavoritesRepository for logged-out state.")
                // Fallback to LocalFavoritesRepository (which might contain backed-up cloud data from a previous user or assets)
                // Note: After sign-out, LocalFavoritesRepository should be empty due to clearing in SettingsActivity.
                scenesToDisplay = localFavoritesRepository.getLocalScenes().toMutableList()
                Log.d(TAG, "Loaded ${scenesToDisplay.size} scenes from LocalFavoritesRepository for logged-out state.")

                if (scenesToDisplay.isEmpty()) {
                    Log.d(TAG, "LocalFavoritesRepository is also empty. Loading from assets for logged-out user.")
                    val assetScenes = loadScenesFromAssets(appContext)
                    val deletedDefaultIds = getDeletedLoggedOutSceneIds(appContext) // From NoSafeWordAppPrefs
                    Log.d(TAG, "Found ${deletedDefaultIds.size} locally deleted default scene IDs: $deletedDefaultIds")

                    val filteredAssetScenes = assetScenes.filter { assetScene ->
                        val persistentId = "default_${assetScene.id}"
                        !deletedDefaultIds.contains(persistentId)
                    }.map {
                        it.copy(userId = "", isCustom = false, firestoreId = "") // Ensure pristine state
                    }
                    scenesToDisplay = filteredAssetScenes.toMutableList()
                    Log.d(TAG, "Loaded ${scenesToDisplay.size} scenes from assets after filtering deleted ones.")
                }
                // Save the determined scenes (from LocalFavoritesRepo or assets) to NoSafeWordAppPrefs
                // This makes NoSafeWordAppPrefs the source of truth for the current logged-out session.
                saveLocalScenesToPrefs(appContext, scenesToDisplay ?: emptyList())
                Log.d(TAG, "Saved initial/fallback scenes for logged-out state to NoSafeWordAppPrefs.")
            } else {
                 Log.d(TAG, "Loaded ${scenesToDisplay.size} scenes directly from NoSafeWordAppPrefs for logged-out state.")
            }

            localScenesWhenLoggedOut.clear()
            localScenesWhenLoggedOut.addAll(scenesToDisplay ?: emptyList())

            _scenes.value = ArrayList(localScenesWhenLoggedOut)
            _isLoading.value = false
            Log.d(TAG, "Final scenes for logged-out state (count: ${(_scenes.value).size}): ${(_scenes.value).map { it.title }}")
        }
    }

    private suspend fun syncLocalScenesToFirestore(userId: String, scenesToConsiderForSync: List<Scene>) {
        if (scenesToConsiderForSync.isEmpty()) {
            Log.d(TAG, "No scenes provided to syncLocalScenesToFirestore for user $userId.")
            return
        }

        // _isLoading.value = true; // isLoading is managed by the caller (handleAuthState)
        Log.d(TAG, "Starting sync of ${scenesToConsiderForSync.size} provided scenes for user $userId.")
        var scenesSyncedOrUpdatedCount = 0
        var syncFailedCount = 0

        // Fetch current user's scenes from Firestore to compare
        val cloudScenesResult = repository.getAllUserScenesOnce(userId)
        if (cloudScenesResult.isFailure) {
            Log.e(TAG, "Failed to fetch cloud scenes for sync comparison: ${cloudScenesResult.exceptionOrNull()?.message}")
            _error.value = "Sync failed: Could not get cloud scenes."
            // _isLoading.value = false; // Caller manages isLoading
            return
        }
        val cloudScenesMap = cloudScenesResult.getOrNull()?.associateBy { it.firestoreId.ifEmpty { "orig_${it.id}" } } ?: emptyMap()

        for (localScene in scenesToConsiderForSync) { // Use the passed parameter
            var sceneToUpload = localScene.copy(userId = userId) // Ensure correct userId

            // Determine if this localScene corresponds to an existing cloud scene
            // Key for matching: firestoreId if present, otherwise originalId if it's a default scene
            val matchKey = if (localScene.firestoreId.isNotEmpty() && !localScene.firestoreId.startsWith("local_")) {
                localScene.firestoreId
            } else if (localScene.id != 0) { // Potentially a default scene (edited or not)
                "orig_${localScene.id}"
            } else {
                null // New custom scene created offline (has local_ firestoreId or blank)
            }

            val existingCloudScene = if (matchKey != null) cloudScenesMap[matchKey] else null

            if (existingCloudScene != null) {
                // Scene exists in cloud.
                // A scene becomes custom if its title/content changes from the original asset,
                // or if it was already custom.
                var shouldBeCustom = existingCloudScene.isCustom // Start with cloud's custom status
                var needsUpdate = false

                if (localScene.id != 0 && !existingCloudScene.isCustom) { // It's a default scene in the cloud
                    // Compare local version to original asset to see if it was modified
                    val assetVersion = withContext(Dispatchers.IO) { repository.getAssetSceneDetailsByOriginalId(localScene.id) }
                    if (assetVersion != null) {
                        if (localScene.title != assetVersion.title || localScene.content != assetVersion.content) {
                            shouldBeCustom = true // Modified from original asset
                            needsUpdate = true
                            Log.d(TAG, "Sync: Default scene '${localScene.title}' (OriginalID: ${localScene.id}) was modified locally. Marking as custom.")
                        }
                    } else {
                        // Asset version not found, this is strange. Treat local as custom if it differs from cloud.
                        if (localScene.title != existingCloudScene.title || localScene.content != existingCloudScene.content) {
                           shouldBeCustom = true; needsUpdate = true;
                           Log.w(TAG, "Sync: Asset for default scene '${localScene.title}' (OriginalID: ${localScene.id}) not found. Treating diff as custom.")
                        }
                    }
                } else if (existingCloudScene.isCustom) { // It was already custom in the cloud
                     if (localScene.title != existingCloudScene.title || localScene.content != existingCloudScene.content) {
                        needsUpdate = true
                     }
                }
                // If not a default scene originally (localScene.id == 0), it's inherently custom if new or being updated.
                // This case is handled by the `else` block below if `existingCloudScene` is null.
                // If `existingCloudScene` is not null and `localScene.id == 0`, it means it's a custom scene that was already synced.
                // We just check if title/content changed.
                else if (localScene.id == 0 && existingCloudScene != null) { // existing custom scene
                    if (localScene.title != existingCloudScene.title || localScene.content != existingCloudScene.content) {
                        needsUpdate = true;
                        // shouldBeCustom = true; // It's already custom, this doesn't change
                    }
                }


                if (needsUpdate || (shouldBeCustom && !existingCloudScene.isCustom) || (!shouldBeCustom && existingCloudScene.isCustom) ) {
                    Log.d(TAG, "Sync: Updating scene '${localScene.title}' (ID: ${existingCloudScene.firestoreId}) in Firestore. New custom status: $shouldBeCustom")
                    sceneToUpload = sceneToUpload.copy(firestoreId = existingCloudScene.firestoreId, isCustom = shouldBeCustom)
                    val result = repository.updateScene(sceneToUpload)
                    if (result.isSuccess) scenesSyncedOrUpdatedCount++ else syncFailedCount++
                } else {
                    Log.d(TAG, "Sync: Scene '${localScene.title}' (ID: ${existingCloudScene.firestoreId}) is same as cloud or no change in custom status needed. No update.")
                }
            } else { // existingCloudScene is null - Scene does not exist in cloud.
                // This localScene is either a new custom scene, or a default scene not yet in user's cloud.
                var isNewSceneCustom = localScene.isCustom // Trust isCustom flag from localScene if set
                if (localScene.id != 0 && !localScene.isCustom) { // It's a default scene from assets (or was intended to be)
                    // Compare to asset version to see if it was modified while offline
                    val assetVersion = withContext(Dispatchers.IO) { repository.getAssetSceneDetailsByOriginalId(localScene.id) }
                    if (assetVersion != null) {
                        if (localScene.title == assetVersion.title && localScene.content == assetVersion.content) {
                            isNewSceneCustom = false // It's identical to asset, so it's a default scene
                            Log.d(TAG, "Sync: Adding default scene '${localScene.title}' (OriginalID: ${localScene.id}) to Firestore as isCustom=false.")
                        } else {
                            isNewSceneCustom = true // Modified from asset, so it's custom
                            Log.d(TAG, "Sync: Adding modified default scene '${localScene.title}' (OriginalID: ${localScene.id}) to Firestore as isCustom=true.")
                        }
                    } else {
                         Log.w(TAG, "Sync: Asset for default scene '${localScene.title}' (OriginalID: ${localScene.id}) not found. Adding as custom (isCustom was ${localScene.isCustom}).")
                         isNewSceneCustom = true; // Fallback to custom if asset not found
                    }
                } else { // localScene.id == 0 (new custom) or localScene.isCustom is true
                    Log.d(TAG, "Sync: Adding new custom scene '${localScene.title}' to Firestore (isCustom=${localScene.isCustom}).")
                    isNewSceneCustom = true; // Ensure it's marked custom if new or already was
                }
                
                sceneToUpload = sceneToUpload.copy(firestoreId = "", isCustom = isNewSceneCustom) // Ensure firestoreId is blank for add
                val result = repository.addScene(sceneToUpload)
                if (result.isSuccess) scenesSyncedOrUpdatedCount++ else syncFailedCount++
            }
        }
        // Do not clear localScenesWhenLoggedOut here, it's managed by the caller or other logic.
        Log.i(TAG, "Sync of provided scenes complete for user $userId. Synced/Updated: $scenesSyncedOrUpdatedCount, Failed: $syncFailedCount.")
        if (syncFailedCount > 0) {
            _error.value = "$syncFailedCount scenes failed to sync from local cache."
        }
        // isLoading is managed by the caller (handleAuthState)
    }

    private fun loadScenesFromFirestore() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            repository.getUserScenesFlow()
                .catch { e ->
                    _error.value = "Failed to load scenes from Firestore: ${e.localizedMessage}"
                    Log.e(TAG, "Error loading scenes from Firestore flow", e)
                    _isLoading.value = false
                }
                .collect { sceneList ->
                    val currentUser = auth.currentUser // Cache for consistent check
                    Log.d(TAG, "Collected scenes from Firestore. Count: ${sceneList.size}. User: ${currentUser?.uid}")

                    if (currentUser != null && !areDefaultScenesSeeded(currentUser.uid)) {
                        Log.d(TAG, "Default scenes not yet seeded for user ${currentUser.uid}. Triggering seed check.")
                        // isSeedingInProgress flag will be managed by checkAndSeedDefaultScenes
                        // _isLoading will be managed by checkAndSeedDefaultScenes and the flow collection
                        checkAndSeedDefaultScenes() // This will internally check and add only missing defaults
                        // The flow from getUserScenesFlow will eventually emit the updated list including seeded scenes.
                        // We should still update the current _scenes.value with what we have for now.
                        _scenes.value = sceneList
                        // If checkAndSeedDefaultScenes runs, it will manage isLoading. If not, we need to manage it.
                        // However, the flow will re-emit, so this isLoading=false might be premature if seeding happens.
                        // Let's rely on the flow to update isLoading after seeding.
                        // If seeding doesn't run (because areDefaultScenesSeeded was true, though we entered this block),
                        // then we need to set isLoading false.
                        // This logic path is now simpler: if not seeded, attempt seed. The flow will update.
                    }
                    // Always update scenes with the latest from Firestore, regardless of seeding attempt.
                    _scenes.value = sceneList
                    _isLoading.value = false // Set loading to false after processing current emission.
                                            // If seeding was triggered, the flow will emit again and update isLoading.

                    if (currentUser != null && sceneList.isEmpty() && areDefaultScenesSeeded(currentUser.uid)) {
                        Log.d(TAG, "Firestore scene list is empty for user ${currentUser.uid}, but defaults were already seeded (this is unusual).")
                    }
                }
        }
    }
    
    private fun loadDefaultScenesForLoggedOutUser() {
        viewModelScope.launch {
            _isLoading.value = true
            // localScenesWhenLoggedOut is cleared by the caller (handleAuthState) if it decides a full reload is needed.
            // Or, if handleAuthState finds it populated, it uses it as is.
            // This function's job is to populate it from assets, respecting deletions.
            if (localScenesWhenLoggedOut.isNotEmpty()){
                 Log.d(TAG, "loadDefaultScenesForLoggedOutUser: localScenesWhenLoggedOut not empty, assuming it's managed by handleAuthState. Size: ${localScenesWhenLoggedOut.size}")
                 _scenes.value = ArrayList(localScenesWhenLoggedOut) // Ensure UI is synced
                 _isLoading.value = false
                 return@launch
            }

            Log.d(TAG, "loadDefaultScenesForLoggedOutUser: localScenesWhenLoggedOut is empty, proceeding to load from assets.")
            val assetScenes = loadScenesFromAssets(appContext)
            val deletedIds = getDeletedLoggedOutSceneIds(appContext)
            Log.d(TAG, "loadDefaultScenesForLoggedOutUser: Found ${deletedIds.size} locally deleted IDs: $deletedIds")

            val scenesToDisplay = assetScenes.filter { assetScene ->
                val persistentId = "default_${assetScene.id}" // Default scenes from assets are identified by their original int id
                val isDeleted = deletedIds.contains(persistentId)
                if(isDeleted) Log.d(TAG, "loadDefaultScenesForLoggedOutUser: Filtering out deleted asset scene '${assetScene.title}' (ID: $persistentId)")
                !isDeleted
            }.map {
                // Ensure pristine state for scenes loaded from assets
                it.copy(userId = "", isCustom = false, firestoreId = "")
            }

            localScenesWhenLoggedOut.addAll(scenesToDisplay)
            _scenes.value = ArrayList(localScenesWhenLoggedOut)
            _isLoading.value = false
            Log.d(TAG, "Loaded ${localScenesWhenLoggedOut.size} default scenes for logged out user (after filtering).")
        }
    }

    private fun areDefaultScenesSeeded(userId: String): Boolean {
        val prefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DEFAULT_SCENES_SEEDED + userId, false)
    }

    private fun setDefaultScenesSeeded(userId: String, seeded: Boolean) {
        val prefs = appContext.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_DEFAULT_SCENES_SEEDED + userId, seeded).apply()
    }

    private suspend fun checkAndSeedDefaultScenes() {
        val userId = auth.currentUser?.uid ?: return
        if (areDefaultScenesSeeded(userId)) {
            Log.d(TAG, "Default scenes already seeded in Firestore for user $userId.")
            return
        }

        Log.d(TAG, "Firestore empty for user $userId. Attempting to seed default scenes from assets into Firestore.")
        // isSeedingInProgress is true here
        val assetScenes = loadScenesFromAssets(appContext)
        if (assetScenes.isNotEmpty()) {
            val scenesToSeed = assetScenes.map {
                it.copy(userId = userId, isCustom = false, firestoreId = "")
            }
            Log.d(TAG, "Preparing to seed ${scenesToSeed.size} scenes for user $userId.")
            val result = repository.addDefaultScenesBatch(scenesToSeed)
            result.fold(
                onSuccess = {
                    Log.i(TAG, "Successfully seeded ${scenesToSeed.size} default scenes to Firestore for user $userId.")
                    setDefaultScenesSeeded(userId, true)
                    // The flow will re-emit, and isLoading will be set to false by the collector.
                },
                onFailure = { e ->
                    _error.value = "Failed to seed default scenes to Firestore: ${e.localizedMessage}"
                    Log.e(TAG, "Failed to seed default scenes to Firestore for user $userId", e)
                    _isLoading.value = false
                }
            )
        } else {
            Log.w(TAG, "No default scenes found in assets to seed into Firestore for user $userId.")
            _isLoading.value = false
        }
        isSeedingInProgress = false // Reset flag after attempt
    }

    private fun loadScenesFromAssets(context: Context): List<Scene> {
        return try {
            val jsonString = context.assets.open(SCENES_ASSET_FILENAME).bufferedReader().use { it.readText() }
            // Assuming scenes.json is an array of Scene objects directly
            // The Scene data class should have default values for firestoreId and userId
            // as they will be set before saving to Firestore.
            val typeToken = object : TypeToken<List<Scene>>() {}.type
            Gson().fromJson<List<Scene>>(jsonString, typeToken) ?: emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Error reading scenes from assets: $SCENES_ASSET_FILENAME", e)
            _error.value = "Could not load default scenes from assets."
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading scenes from assets", e)
            _error.value = "Unexpected error loading default scenes."
            emptyList()
        }
    }

    fun addScene(scene: Scene) {
        val currentUser = auth.currentUser
        if (currentUser != null) { // User is logged in
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                val sceneWithUser = scene.copy(
                    userId = currentUser.uid,
                    isCustom = true, // Explicitly ensure isCustom is true for new scenes added when logged in
                    firestoreId = if(scene.firestoreId.startsWith("local_")) "" else scene.firestoreId
                )
                Log.d(TAG, "Adding scene to Firestore: ${sceneWithUser.title}, isCustom: ${sceneWithUser.isCustom}, originalId: ${sceneWithUser.id}")
                val result = repository.addScene(sceneWithUser) // addScene in repo handles new ID generation if firestoreId is empty
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene '${sceneWithUser.title}' added to Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to add scene: ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error adding scene",e) }
                )
            }
        } else { // User is logged out
            viewModelScope.launch {
                _isLoading.value = true
                val localId = "local_${System.currentTimeMillis()}"
                val localScene = scene.copy(userId = "", isCustom = true, firestoreId = localId)
                localScenesWhenLoggedOut.add(localScene)
                _scenes.value = ArrayList(localScenesWhenLoggedOut) // Emit updated list
                saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist changes
                _isLoading.value = false
                Log.d(TAG, "Scene '${scene.title}' added to local list (logged out) and saved to Prefs. Local ID: $localId")
            }
        }
    }

    fun updateScene(scene: Scene) {
        val currentUser = auth.currentUser
        if (currentUser != null) { // Logged in
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                if (scene.firestoreId.isBlank() || scene.firestoreId.startsWith("local_")) {
                    _error.value = "Cannot update scene without valid Firestore ID."; _isLoading.value = false; return@launch
                }
                if (scene.userId.isNotBlank() && scene.userId != currentUser.uid) {
                     _error.value = "Cannot update scene of another user."; _isLoading.value = false; return@launch
                }
                val sceneToUpdate = scene.copy(userId = currentUser.uid) // Ensure userId is correct
                val result = repository.updateScene(sceneToUpdate)
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene updated in Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to update scene: ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error updating scene",e) }
                )
            }
        } else { // Logged out - update local list
            viewModelScope.launch {
                _isLoading.value = true
                var sceneUpdatedSuccessfully = false
                val sceneFromDialog = scene // scene passed to updateScene

                // Attempt to find and update if it's an existing local scene (ID starts with "local_")
                if (sceneFromDialog.firestoreId.startsWith("local_")) {
                    val index = localScenesWhenLoggedOut.indexOfFirst { it.firestoreId == sceneFromDialog.firestoreId }
                    if (index != -1) {
                        localScenesWhenLoggedOut[index] = sceneFromDialog.copy(
                            userId = "", // Ensure no userId
                            isCustom = true // Ensure it's marked custom
                        )
                        _scenes.value = ArrayList(localScenesWhenLoggedOut)
                        saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist changes
                        Log.d(TAG, "Updated existing local scene '${sceneFromDialog.title}' (ID: ${sceneFromDialog.firestoreId}) and saved to Prefs.")
                        sceneUpdatedSuccessfully = true
                    }
                } else {
                    // Attempt to find and update if it was a default scene (identified by original Int id)
                    // This scene is now being customized locally.
                    val originalIntId = sceneFromDialog.id
                    // Find by original 'id' and ensure it's not already a 'local_' prefixed scene we missed.
                    val index = localScenesWhenLoggedOut.indexOfFirst { it.id == originalIntId && !it.firestoreId.startsWith("local_") }
                    if (index != -1) {
                        val newLocalFirestoreId = "local_${System.currentTimeMillis()}"
                        localScenesWhenLoggedOut[index] = sceneFromDialog.copy(
                            firestoreId = newLocalFirestoreId, // Assign a new local-specific firestoreId
                            userId = "",
                            isCustom = true // Mark as custom
                        )
                        _scenes.value = ArrayList(localScenesWhenLoggedOut)
                        saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut) // Persist changes
                        Log.d(TAG, "Updated default scene '${sceneFromDialog.title}' (OriginalIntID: ${originalIntId}) as new local scene (NewLocalFirestoreID: ${newLocalFirestoreId}) and saved to Prefs.")
                        sceneUpdatedSuccessfully = true
                    }
                }

                if (!sceneUpdatedSuccessfully) {
                    _error.value = "Local scene not found for update (Passed ID: ${sceneFromDialog.firestoreId}, OriginalIntID: ${sceneFromDialog.id})."
                    Log.w(TAG, "Local scene (PassedID: ${sceneFromDialog.firestoreId}, OriginalIntID: ${sceneFromDialog.id}) not found for update.")
                    // Do not save to prefs if update failed to find the scene
                }
                _isLoading.value = false
            }
        }
    }

    fun deleteScene(sceneToDelete: Scene) {
        val currentUser = auth.currentUser
        if (currentUser != null) { // Logged in
            viewModelScope.launch {
                _isLoading.value = true; _error.value = null
                if (sceneToDelete.firestoreId.isBlank()) {
                     _error.value = "Cannot delete scene from Firestore without a valid Firestore ID."; _isLoading.value = false; return@launch
                }
                if (sceneToDelete.firestoreId.startsWith("local_")) {
                     _error.value = "Cannot delete a 'local_' prefixed scene directly from Firestore this way."; _isLoading.value = false; return@launch
                }
                val result = repository.deleteScene(sceneToDelete.firestoreId)
                result.fold(
                    onSuccess = { Log.d(TAG, "Scene '${sceneToDelete.title}' deleted from Firestore."); _isLoading.value = false },
                    onFailure = { e -> _error.value = "Failed to delete scene '${sceneToDelete.title}': ${e.localizedMessage}"; _isLoading.value = false; Log.e(TAG, "Error deleting scene",e) }
                )
            }
        } else { // Logged out - delete from local list
             viewModelScope.launch {
                _isLoading.value = true
                var removed = false
                val persistentId = getPersistentIdentifierForLocalScene(sceneToDelete)

                if (persistentId != null) {
                    // When deleting, we match based on the determined persistentId.
                    // The localScenesWhenLoggedOut list contains Scene objects.
                    // We need to find the scene in that list whose persistent identifier matches.
                    val sceneToRemove = localScenesWhenLoggedOut.find { getPersistentIdentifierForLocalScene(it) == persistentId }

                    if (sceneToRemove != null) {
                        removed = localScenesWhenLoggedOut.remove(sceneToRemove)
                        if (removed) {
                            Log.d(TAG, "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) removed from list.")
                            // If the removed scene was originally a default scene (its persistentId starts with "default_"),
                            // then we add this persistentId to the set of *locally deleted default scenes*.
                            // This prevents it from being re-added from assets if the user stays logged out.
                            if (persistentId.startsWith("default_")) {
                                val deletedDefaultIds = getDeletedLoggedOutSceneIds(appContext)
                                deletedDefaultIds.add(persistentId)
                                saveDeletedLoggedOutSceneIds(appContext, deletedDefaultIds)
                                Log.d(TAG, "Added $persistentId to SharedPreferences *default* deleted list. New list: $deletedDefaultIds")
                            }
                            _scenes.value = ArrayList(localScenesWhenLoggedOut)
                            // Persist the entire modified list of scenes (which now excludes the deleted one)
                            // to the main SharedPreferences key for logged-out scenes.
                            saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut)
                            Log.d(TAG, "Saved updated localScenesWhenLoggedOut to Prefs after deletion.")
                        } else {
                             Log.w(TAG, "Scene '${sceneToDelete.title}' (PersistentID: $persistentId) found by persistentId but .remove() failed. This is unexpected.")
                            _error.value = "Failed to remove scene '${sceneToDelete.title}' from memory though it was found."
                        }
                    } else {
                        _error.value = "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) not found for deletion using its persistent ID."
                        Log.w(TAG, "Local scene '${sceneToDelete.title}' (PersistentID: $persistentId) not found for deletion using its persistent ID.")
                    }
                } else {
                    _error.value = "Could not determine persistent ID for scene '${sceneToDelete.title}' to delete."
                    Log.e(TAG, "deleteScene (logged out): Failed to get persistent ID for '${sceneToDelete.title}'")
                }
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun resetDefaultScenesForCurrentUser() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val currentUser = auth.currentUser
            if (currentUser == null) {
                _error.value = "User not logged in. Cannot reset scenes."
                _isLoading.value = false
                Log.w(TAG, "resetDefaultScenesForCurrentUser: User not logged in.")
                return@launch
            }
            val userId = currentUser.uid

            // 1. Load original default scenes from assets
            val assetScenes = loadScenesFromAssets(appContext)
            if (assetScenes.isEmpty()) {
                _error.value = "No default scenes found in assets to reset to."
                _isLoading.value = false
                Log.w(TAG, "resetDefaultScenesForCurrentUser: No scenes in assets file.")
                return@launch
            }

            // 2. Get all current user scenes from Firestore
            val firestoreScenesResult = repository.getAllUserScenesOnce(userId)
            if (firestoreScenesResult.isFailure) {
                _error.value = "Failed to fetch current scenes: ${firestoreScenesResult.exceptionOrNull()?.message}"
                _isLoading.value = false
                Log.e(TAG, "resetDefaultScenesForCurrentUser: Failed to get all user scenes.", firestoreScenesResult.exceptionOrNull())
                return@launch
            }
            val firestoreScenes = firestoreScenesResult.getOrNull() ?: emptyList()
            val firestoreScenesMapByOriginalId = firestoreScenes
                .filter { !it.isCustom && it.id != 0 } // Consider only scenes that were originally default
                .associateBy { it.id }

            var scenesAdded = 0
            var scenesUpdated = 0
            var scenesFailed = 0

            // 3. Compare and update/add
            for (assetScene in assetScenes) {
                val firestoreMatch = firestoreScenesMapByOriginalId[assetScene.id]

                if (firestoreMatch == null) {
                    // Default scene from assets is missing in Firestore, add it back.
                    val sceneToAdd = assetScene.copy(
                        userId = userId,
                        isCustom = false, // Ensure it's marked as not custom
                        firestoreId = "" // Let Firestore generate ID
                    )
                    Log.d(TAG, "Reset: Adding missing default scene '${sceneToAdd.title}' (Original ID: ${sceneToAdd.id})")
                    val addResult = repository.addScene(sceneToAdd)
                    if (addResult.isSuccess) scenesAdded++ else scenesFailed++
                } else {
                    // Default scene exists in Firestore. Check if it was modified.
                    val needsUpdate = firestoreMatch.title != assetScene.title ||
                                      firestoreMatch.content != assetScene.content ||
                                      firestoreMatch.isCustom // If it was marked as custom, it's a modification

                    if (needsUpdate) {
                        val sceneToUpdate = assetScene.copy(
                            firestoreId = firestoreMatch.firestoreId, // Use existing Firestore ID
                            userId = userId,
                            isCustom = false // Ensure it's marked as not custom
                        )
                        Log.d(TAG, "Reset: Updating modified default scene '${sceneToUpdate.title}' (Original ID: ${sceneToUpdate.id})")
                        val updateResult = repository.updateScene(sceneToUpdate)
                        if (updateResult.isSuccess) scenesUpdated++ else scenesFailed++
                    }
                }
            }

            // 4. Handle scenes in Firestore that were originally default but are not in assets (should not happen if assets are source of truth)
            // This part is more about cleanup if Firestore has orphaned "default" scenes.
            // For now, the request is to restore asset defaults.

            _isLoading.value = false
            val summary = "Reset complete. Added: $scenesAdded, Updated: $scenesUpdated, Failed: $scenesFailed."
            Log.i(TAG, summary)
            // Optionally, set a success message or rely on the flow to update the UI.
            // Forcing a refresh of the scenes list:
            if (scenesAdded > 0 || scenesUpdated > 0 || scenesFailed > 0) {
                 // The existing flow collection in loadScenesFromFirestore should pick up changes.
                 // If not, a manual trigger might be needed, but Firestore listeners usually handle this.
                 // Forcing a re-evaluation by the flow if it's not immediate:
                 // loadScenesFromFirestore() // This might be redundant if listeners are quick.
            }
            if (scenesFailed > 0) {
                _error.value = "$scenesFailed operations failed during reset."
            }
            // The ViewModel's `scenes` StateFlow should automatically update due to Firestore listener in `loadScenesFromFirestore`.
        }
    }

    fun resetLocalScenesToDefault() {
        viewModelScope.launch {
            if (auth.currentUser != null) {
                Log.w(TAG, "resetLocalScenesToDefault called while user is logged in. This function is for logged-out state.")
                _error.value = "Reset for logged out state called inappropriately."
                _isLoading.value = false // Ensure loading is reset if we exit early
                return@launch
            }
            _isLoading.value = true
            _error.value = null
            Log.d(TAG, "Resetting local scenes to default (logged out), preserving custom scenes.")

            // 1. Get current local scenes (could be from memory or try loading from prefs if memory is empty)
            var currentLocalScenesInMemory = ArrayList(localScenesWhenLoggedOut) // Work with a copy of in-memory list
            val loadedFromPrefs = loadLocalScenesFromPrefs(appContext)

            val effectiveCurrentLocalScenes: MutableList<Scene> = if (loadedFromPrefs != null) {
                Log.d(TAG, "ResetLocal: Using scenes loaded from SharedPreferences. Count: ${loadedFromPrefs.size}")
                loadedFromPrefs
            } else {
                Log.d(TAG, "ResetLocal: No scenes in SharedPreferences, using current in-memory list. Count: ${currentLocalScenesInMemory.size}")
                currentLocalScenesInMemory
            }
            Log.d(TAG, "ResetLocal: Effective current local scenes count before filtering: ${effectiveCurrentLocalScenes.size}")

            // 2. Identify and preserve custom scenes created while logged out
            val customScenesToKeep = effectiveCurrentLocalScenes.filter {
                // A scene is custom if it was explicitly marked as such,
                // or if it has a 'local_' firestoreId and isn't just an edited default scene
                // (edited defaults are reset, new local ones are kept).
                // A simple check: if it has a 'local_' prefix and its original 'id' is 0 (or not a known default pattern)
                // OR if isCustom is true.
                // For simplicity here, we assume scenes with local_ prefix that were NOT originally defaults are custom.
                // Default scenes have non-zero 'id' and initially blank 'firestoreId'.
                // When a default scene is edited locally, it gets a 'local_' firestoreId AND isCustom=true.
                // When a new scene is added locally, it gets 'local_' firestoreId, id=0 (usually), and isCustom=true.
                (it.isCustom && it.firestoreId.startsWith("local_")) || (it.id == 0 && it.firestoreId.startsWith("local_"))
            }.distinctBy { it.firestoreId.ifEmpty { "new_${it.title}_${it.content.hashCode()}" } } // Ensure uniqueness for new scenes not yet saved
            Log.d(TAG, "ResetLocal: Found ${customScenesToKeep.size} custom scenes to preserve.")


            // 3. Clear the tracking of deleted *default* scenes, so all defaults are reloaded from assets
            saveDeletedLoggedOutSceneIds(appContext, mutableSetOf())
            Log.d(TAG, "ResetLocal: Cleared SharedPreferences for deleted *default* scene IDs.")

            // 4. Load original default scenes from assets
            val assetDefaultScenes = loadScenesFromAssets(appContext).map {
                it.copy(userId = "", isCustom = false, firestoreId = "") // Ensure pristine state
            }
            Log.d(TAG, "ResetLocal: Loaded ${assetDefaultScenes.size} scenes from assets.")

            // 5. Construct the new list: original defaults + preserved custom scenes
            localScenesWhenLoggedOut.clear()
            localScenesWhenLoggedOut.addAll(assetDefaultScenes)
            // Add custom scenes, ensuring no duplicates if a custom scene somehow shares an identifier with a default (unlikely)
            // A more robust way would be to ensure custom scenes have unique IDs not overlapping with default `id`s.
            // For now, simple add, assuming custom scenes (especially new ones) won't clash with default asset scenes by content.
            customScenesToKeep.forEach { customScene ->
                // Avoid adding a custom scene if an identical default scene (by content/title) was just added.
                // This is a basic check. A true "originalId" for custom scenes would be better.
                if (!assetDefaultScenes.any { ds -> ds.title == customScene.title && ds.content == customScene.content }) {
                    localScenesWhenLoggedOut.add(customScene)
                } else {
                     Log.d(TAG, "ResetLocal: Custom scene '${customScene.title}' seems to be a duplicate of an asset scene, not re-adding.")
                }
            }
            // Ensure no duplicates in the final list based on a reliable unique property if possible
            // For local scenes, firestoreId (if local_) or a combination of title/content for new ones.
             val distinctLocalScenes = localScenesWhenLoggedOut.distinctBy {
                if (it.firestoreId.startsWith("local_")) it.firestoreId
                else if (it.id != 0 && it.firestoreId.isBlank()) "default_${it.id}" // Pristine default
                else "${it.title}_${it.content.hashCode()}" // Fallback for other cases
            }
            localScenesWhenLoggedOut.clear()
            localScenesWhenLoggedOut.addAll(distinctLocalScenes)


            // 6. Save the new combined list and update UI
            saveLocalScenesToPrefs(appContext, localScenesWhenLoggedOut)
            _scenes.value = ArrayList(localScenesWhenLoggedOut)
            _isLoading.value = false
            Log.i(TAG, "Local scenes reset: Default scenes restored, ${customScenesToKeep.size} custom scenes preserved. Total: ${localScenesWhenLoggedOut.size}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        firebaseAuthListener?.let { auth.removeAuthStateListener(it) } // Clean up the stored listener
        Log.d(TAG, "ScenesViewModel onCleared, auth listener removed.")
    }
}
