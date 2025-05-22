package com.velvettouch.nosafeword

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import android.util.Log // Added import for Log
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FirebaseFirestoreException // Added for transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine // Add this for combining flows
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class PositionsRepository(private val context: Context) {

    private val db = Firebase.firestore
    private val storage = Firebase.storage
    private val gson = Gson()

    // Observe auth state to update userId dynamically
    private var userId: String? = FirebaseAuth.getInstance().currentUser?.uid

    init {
        FirebaseAuth.getInstance().addAuthStateListener { firebaseAuth ->
            userId = firebaseAuth.currentUser?.uid
        }
    }

    companion object {
        private const val POSITIONS_COLLECTION = "positions"
        private const val POSITION_IMAGES_STORAGE_PATH = "position_images"
        private const val LOCAL_POSITIONS_PREF = "local_positions_pref"
        private const val LOCAL_POSITIONS_KEY = "local_positions_list"
        private const val INTERNAL_IMAGE_DIR = "position_images_local" // For locally copied images
    }

    private fun copyImageToInternalStorage(contentUri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(contentUri) ?: return null
            
            // Create a directory for local images if it doesn't exist
            val localImagesDir = File(context.filesDir, INTERNAL_IMAGE_DIR)
            if (!localImagesDir.exists()) {
                localImagesDir.mkdirs()
            }

            // Determine file extension
            val fileExtension = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(context.contentResolver.getType(contentUri)) ?: "jpg"
            
            val fileName = "${UUID.randomUUID()}.$fileExtension"
            val outputFile = File(localImagesDir, fileName)

            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            inputStream.close()
            outputFile.absolutePath // Return the absolute path of the copied file
        } catch (e: IOException) {
            android.util.Log.e("PositionsRepository", "Error copying image to internal storage", e)
            null
        }
    }

    private fun getLocalPositionsPrefs(): SharedPreferences {
        return context.getSharedPreferences(LOCAL_POSITIONS_PREF, Context.MODE_PRIVATE)
    }

    private fun saveLocalPositions(positions: List<PositionItem>) {
        // Step 1: Group by ID first to handle exact duplicates in the input list
        val distinctById = positions.distinctBy { it.id }

        // Step 2: Separate local_ items from non-local_ (Firestore/asset) items
        val localItems = distinctById.filter { it.id.startsWith("local_") }
        val nonLocalItems = distinctById.filterNot { it.id.startsWith("local_") }

        // Step 3: Create a map of non-local items by name for quick lookup
        // If multiple non-local items have the same name, this map will keep one (e.g. first encountered).
        // This is generally okay as non-local items should ideally have unique IDs from Firestore.
        val nonLocalItemsByName = nonLocalItems.associateBy { it.name }

        val finalPositionsToSave = mutableListOf<PositionItem>()
        val processedLocalNames = mutableSetOf<String>()

        // Add all non-local items first. These are prioritized.
        finalPositionsToSave.addAll(nonLocalItems)
        nonLocalItems.forEach { processedLocalNames.add(it.name) } // Mark their names as processed

        // Now process local_ items. Only add a local_ item if its name hasn't been covered by a non-local_ item.
        localItems.forEach { localItem ->
            if (localItem.name !in processedLocalNames) {
                finalPositionsToSave.add(localItem)
                processedLocalNames.add(localItem.name) // Add its name to prevent other local_ items with same name
            } else {
                // This local_ item's name either matches a non-local_ item or another local_ item already added.
                Log.w("PositionsRepository", "saveLocalPositions: Local item '${localItem.name}' (ID: ${localItem.id}) is being discarded as its name is already covered by another item (likely a non-local or previously processed local one).")
            }
        }
        
        // The list `finalPositionsToSave` should now be consolidated:
        // - Non-local items are preserved.
        // - Local items are only kept if their name doesn't conflict with a non-local item or another, already-added local item.
        // As a final safety, ensure the list is distinct by ID (though the above logic should largely ensure this).
        val trulyFinalPositions = finalPositionsToSave.distinctBy { it.id }

        val json = gson.toJson(trulyFinalPositions)
        getLocalPositionsPrefs().edit().putString(LOCAL_POSITIONS_KEY, json).commit()
        Log.d("PositionsRepository", "saveLocalPositions: Saved ${trulyFinalPositions.size} positions to SharedPreferences. Original: ${positions.size}, DistinctById: ${distinctById.size}")
    }

    private fun loadLocalPositions(): MutableList<PositionItem> {
        val json = getLocalPositionsPrefs().getString(LOCAL_POSITIONS_KEY, null)
        return if (json != null) {
            val type = object : TypeToken<MutableList<PositionItem>>() {}.type
            // Ensure loaded items have userId as null if they were stored by an anonymous user
            val loadedList: MutableList<PositionItem> = gson.fromJson(json, type)
            loadedList.map { if (it.userId == "anonymous") it.copy(userId = null) else it }.toMutableList()
        } else {
            mutableListOf()
        }
    }

    fun clearLocalPositions() {
        getLocalPositionsPrefs().edit().remove(LOCAL_POSITIONS_KEY).apply()
    }


    // Upload image to Firebase Storage and get download URL
    suspend fun uploadImage(
        imageFileUriToUpload: Uri, // Should be a file URI for the actual file to upload
        forUserId: String?,
        deterministicFileName: String // The name (e.g., UUID.jpg) to use in Firebase Storage
    ): String? {
        if (forUserId == null) {
            Log.w("PositionsRepository", "uploadImage: forUserId is null, cannot upload.")
            return null
        }
        if (deterministicFileName.isBlank()) {
            Log.e("PositionsRepository", "uploadImage: deterministicFileName is blank.")
            return null
        }

        val imageRef = storage.reference.child("$POSITION_IMAGES_STORAGE_PATH/$forUserId/$deterministicFileName")

        try {
            // Attempt to get metadata to check if the file already exists
            Log.d("PositionsRepository", "uploadImage: Checking metadata for $imageRef")
            imageRef.metadata.await() // Throws exception if not found
            // If metadata fetch succeeds, file exists. Get its download URL.
            Log.d("PositionsRepository", "uploadImage: File already exists at $imageRef. Fetching download URL.")
            val downloadUrl = imageRef.downloadUrl.await().toString()
            Log.d("PositionsRepository", "uploadImage: Existing file URL: $downloadUrl")
            return downloadUrl
        } catch (e: Exception) {
            if (e is com.google.firebase.storage.StorageException && e.errorCode == com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND) {
                // File does not exist, proceed with upload
                Log.d("PositionsRepository", "uploadImage: File not found at $imageRef. Proceeding with upload.")
                return try {
                    imageRef.putFile(imageFileUriToUpload).await() // Use the passed file URI
                    val downloadUrl = imageRef.downloadUrl.await().toString()
                    Log.d("PositionsRepository", "uploadImage: File uploaded successfully to $imageRef. New URL: $downloadUrl")
                    downloadUrl
                } catch (uploadException: Exception) {
                    Log.e("PositionsRepository", "uploadImage: Upload failed for $imageRef", uploadException)
                    null // Upload failed
                }
            } else {
                // Other error during metadata fetch (e.g., permission issues, network error)
                Log.e("PositionsRepository", "uploadImage: Error fetching metadata for $imageRef", e)
                return null
            }
        }
    }

    // Add a new position or update an existing one.
    // Returns the PositionItem as saved/updated in Firestore, or null on failure for online operations.
    // For offline operations, it implies local save success but returns null as no Firestore interaction.
    suspend fun addOrUpdatePosition(
        position: PositionItem,
        imageUriToUpload: Uri? = null,
        uploadedImageCache: MutableMap<String, String>? = null // Cache for local path -> remote URL
    ): PositionItem? {
        val currentProcessingUserId = FirebaseAuth.getInstance().currentUser?.uid

        if (currentProcessingUserId == null) { // Anonymous user, save locally
            val localPositions = loadLocalPositions()
            var positionToSave = position.copy(userId = null) // Ensure userId is null for local items

            if (imageUriToUpload != null) {
                android.util.Log.d("PositionsRepository", "addOrUpdatePosition (offline): imageUriToUpload present ($imageUriToUpload). Copying to internal storage.")
                val internalImagePath = copyImageToInternalStorage(imageUriToUpload)
                positionToSave = positionToSave.copy(imageName = internalImagePath ?: "") // Use blank if copy fails, keep original name if no URI
                android.util.Log.d("PositionsRepository", "addOrUpdatePosition (offline): Image processed. New imageName: ${positionToSave.imageName}")
            } else {
                android.util.Log.d("PositionsRepository", "addOrUpdatePosition (offline): No imageUriToUpload. Using existing imageName: ${positionToSave.imageName}")
                // Keep existing imageName if no new image is provided
            }

            // Logic for ID assignment and finding existing by name for local save
            if (positionToSave.id.isBlank() || !positionToSave.id.startsWith("local_")) {
                // This is a new item or an item whose ID needs to be localized.
                // Try to find an existing local item by name first.
                val existingByName = localPositions.find { it.name == positionToSave.name }
                if (existingByName != null) {
                    Log.d("PositionsRepository", "addOrUpdatePosition (offline): New item name '${positionToSave.name}' matches existing local item ID '${existingByName.id}'. Using existing ID.")
                    positionToSave = positionToSave.copy(id = existingByName.id) // Use existing local ID
                } else {
                    // No existing local item by this name, generate a new local ID.
                    val newLocalId = "local_${UUID.randomUUID()}"
                    Log.d("PositionsRepository", "addOrUpdatePosition (offline): New item name '${positionToSave.name}' not found locally. Assigning new local ID '$newLocalId'.")
                    positionToSave = positionToSave.copy(id = newLocalId)
                }
            }
            // At this point, positionToSave.id is guaranteed to be a valid local_ ID

            android.util.Log.d("PositionsRepository", "addOrUpdatePosition (offline): Saving to local SharedPreferences: ID ${positionToSave.id}, Name: ${positionToSave.name}, ImageName: ${positionToSave.imageName}")
            val existingIndexById = localPositions.indexOfFirst { it.id == positionToSave.id }
            if (existingIndexById != -1) {
                localPositions[existingIndexById] = positionToSave
            } else {
                // This case implies it's a new ID (either genuinely new name, or name collision was resolved to a new ID if logic changes)
                localPositions.add(positionToSave)
            }
            // Ensure the list saved to SharedPreferences is distinct by ID.
            saveLocalPositions(localPositions.distinctBy { it.id })
            return null // Offline operation, no Firestore item to return
        } else { // Logged-in user, process for Firestore
            var positionForFirestore = position.copy(userId = currentProcessingUserId)
            var finalImageNameForFirestore = positionForFirestore.imageName // Default to existing imageName
            var existingFirestorePosition: PositionItem? = null

            // If the incoming position ID is a Firestore ID, try to fetch it directly.
            // Otherwise, if it's a local ID or blank, search by name for existing.
            if (!positionForFirestore.id.isBlank() && !positionForFirestore.id.startsWith("local_")) {
                try {
                    val doc = db.collection(POSITIONS_COLLECTION).document(positionForFirestore.id).get().await()
                    if (doc.exists()) existingFirestorePosition = doc.toObject(PositionItem::class.java)?.copy(id = doc.id)
                } catch (e: Exception) { Log.e("PositionsRepository", "Error fetching existing position by ID ${positionForFirestore.id}", e)}
            } else { // Local ID or new item, search by name
                try {
                    val query = db.collection(POSITIONS_COLLECTION)
                        .whereEqualTo("userId", currentProcessingUserId)
                        .whereEqualTo("name", positionForFirestore.name)
                        .limit(1).get().await()
                    if (!query.isEmpty) {
                        val doc = query.documents.first()
                        existingFirestorePosition = doc.toObject(PositionItem::class.java)?.copy(id = doc.id)
                    }
                } catch (e: Exception) { Log.e("PositionsRepository", "Error fetching existing position by name ${positionForFirestore.name}", e)}
            }

            if (imageUriToUpload != null && !positionForFirestore.isAsset) {
                // New image provided via content URI (e.g., user picked a new image)
                android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): imageUriToUpload ($imageUriToUpload) is present. Copying and uploading.")
                val tempLocalPathForUpload = copyImageToInternalStorage(imageUriToUpload) // This creates a file with UUID name
                if (tempLocalPathForUpload != null) {
                    val tempLocalFile = File(tempLocalPathForUpload)
                    val uploadedUrl = uploadImage(
                        Uri.fromFile(tempLocalFile),
                        currentProcessingUserId,
                        tempLocalFile.name // Use the UUID-based name from the copied file
                    )
                    finalImageNameForFirestore = uploadedUrl ?: existingFirestorePosition?.imageName ?: ""
                    android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): Content URI copied to $tempLocalPathForUpload, then Uploaded. New imageName: $finalImageNameForFirestore")
                } else {
                    android.util.Log.e("PositionsRepository", "addOrUpdatePosition (online): Failed to copy content URI $imageUriToUpload to internal storage.")
                    finalImageNameForFirestore = existingFirestorePosition?.imageName ?: "" // Fallback to existing or blank
                }
            } else if (!positionForFirestore.isAsset &&
                       positionForFirestore.imageName.isNotBlank() &&
                       !positionForFirestore.imageName.startsWith("http") &&
                       !positionForFirestore.imageName.startsWith("content://")) {
                // ImageName is a local path. Check if Firestore already has a remote URL for this item.
                if (existingFirestorePosition?.imageName?.startsWith("http") == true) {
                    android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): imageName is local path, but Firestore has remote URL '${existingFirestorePosition.imageName}'. Using remote URL.")
                    finalImageNameForFirestore = existingFirestorePosition.imageName
                } else {
                    // No remote URL in Firestore, or item doesn't exist yet. Upload from local path.
                    android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): imageName (${positionForFirestore.imageName}) is a local path. Uploading.")
                    val internalFile = File(positionForFirestore.imageName)
                    if (internalFile.exists()) {
                        val localPathKey = internalFile.absolutePath // Cache key is the full local path
                        if (uploadedImageCache != null && uploadedImageCache.containsKey(localPathKey)) {
                            finalImageNameForFirestore = uploadedImageCache[localPathKey] ?: ""
                            android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): Used cached URL for $localPathKey: $finalImageNameForFirestore")
                        } else {
                            val uploadedUrl = uploadImage(
                                Uri.fromFile(internalFile),
                                currentProcessingUserId,
                                internalFile.name // Use the existing UUID-based name of the local file
                            )
                            finalImageNameForFirestore = uploadedUrl ?: ""
                            if (uploadedUrl != null && uploadedImageCache != null) {
                                uploadedImageCache[localPathKey] = uploadedUrl
                                android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): Uploaded and cached $localPathKey -> $uploadedUrl")
                            }
                            android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): Local Path Uploaded. New imageName: $finalImageNameForFirestore")
                        }
                    } else {
                        android.util.Log.e("PositionsRepository", "addOrUpdatePosition (online): Internal file for imageName not found: ${positionForFirestore.imageName}")
                        finalImageNameForFirestore = "" // File not found
                    }
                }
            }
            // Else: imageName is already an HTTP URL, or blank and no new image, or an asset.

            positionForFirestore = positionForFirestore.copy(imageName = finalImageNameForFirestore)

            // Decide whether to add new or update existing in Firestore
            return if (existingFirestorePosition != null) { // Item exists in Firestore (found by ID or name)
                android.util.Log.d("PositionsRepository", "addOrUpdatePosition (online): Existing found (ID: ${existingFirestorePosition.id}, Name: ${existingFirestorePosition.name}). Updating it. Final ImageName: ${positionForFirestore.imageName}")
                val finalPositionToSet = positionForFirestore.copy(id = existingFirestorePosition.id) // Ensure we use the correct Firestore ID
                db.collection(POSITIONS_COLLECTION).document(existingFirestorePosition.id)
                    .set(finalPositionToSet)
                    .await()
                // Save to local SharedPreferences after successful Firestore update
                val localPositionsUpdate = loadLocalPositions()
                val existingIndexUpdate = localPositionsUpdate.indexOfFirst { it.id == finalPositionToSet.id }
                if (existingIndexUpdate != -1) {
                    localPositionsUpdate[existingIndexUpdate] = finalPositionToSet
                } else {
                    localPositionsUpdate.add(finalPositionToSet)
                }
                saveLocalPositions(localPositionsUpdate.distinctBy { it.id })
                Log.d("PositionsRepository", "addOrUpdatePosition (online update): Synced to local SharedPreferences: ID ${finalPositionToSet.id}")
                finalPositionToSet
            } else { // Item is new to Firestore
                 Log.d("PositionsRepository", "addOrUpdatePosition (online): No existing found by ID or name. Adding new. Original Name: '${position.name}', Final ImageName: ${positionForFirestore.imageName}")
                try {
                    val checkQuery = db.collection(POSITIONS_COLLECTION)
                        .whereEqualTo("userId", currentProcessingUserId)
                        .whereEqualTo("name", position.name)
                        .limit(1).get().await()

                    if (!checkQuery.isEmpty) {
                        val docSnapshot = checkQuery.documents.first()
                        Log.w("PositionsRepository", "addOrUpdatePosition: Item with original name '${position.name}' found (ID: ${docSnapshot.id}) during pre-add check. Updating it.")
                        val positionToUpdate = positionForFirestore.copy(id = docSnapshot.id, name = position.name)
                        db.collection(POSITIONS_COLLECTION).document(docSnapshot.id)
                            .set(positionToUpdate)
                            .await()
                        // Save to local SharedPreferences
                        val localPositionsUpdate = loadLocalPositions()
                        val existingIndexUpdate = localPositionsUpdate.indexOfFirst { it.id == positionToUpdate.id }
                        if (existingIndexUpdate != -1) {
                            localPositionsUpdate[existingIndexUpdate] = positionToUpdate
                        } else {
                            localPositionsUpdate.add(positionToUpdate)
                        }
                        saveLocalPositions(localPositionsUpdate.distinctBy { it.id })
                        Log.d("PositionsRepository", "addOrUpdatePosition (online add - found by name): Synced to local SharedPreferences: ID ${positionToUpdate.id}")
                        return positionToUpdate
                    }
                    
                    Log.d("PositionsRepository", "addOrUpdatePosition: Pre-add check found no existing item. Proceeding to add '${position.name}'.")
                    val newDocRef = db.collection(POSITIONS_COLLECTION)
                        .add(positionForFirestore.copy(id = "", name = position.name))
                        .await()
                    val newlyAddedPosition = positionForFirestore.copy(id = newDocRef.id, name = position.name)
                    // Save to local SharedPreferences
                    val localPositionsAdd = loadLocalPositions()
                    localPositionsAdd.add(newlyAddedPosition) // Add the new item
                    saveLocalPositions(localPositionsAdd.distinctBy { it.id })
                    Log.d("PositionsRepository", "addOrUpdatePosition (online add - new doc): Synced to local SharedPreferences: ID ${newlyAddedPosition.id}")
                    newlyAddedPosition
                } catch (e: Exception) {
                    Log.e("PositionsRepository", "addOrUpdatePosition (online): Error adding/updating position for name '${position.name}'", e)
                    null
                }
            }
        }
    }

    // Get all positions for the current user (real-time updates from Firestore or local)
    fun getUserPositions(): Flow<List<PositionItem>> = flow {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid == null) { // User is anonymous
            emit(loadLocalPositions())
        } else {
            // For logged-in users, fetch from Firestore
            val firestoreFlow: Flow<List<PositionItem>> = callbackFlow {
                val listenerRegistration = db.collection(POSITIONS_COLLECTION)
                    .whereEqualTo("userId", currentUid) // Use the actual UID
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }
                        if (snapshot != null) {
                            val positions = snapshot.documents.mapNotNull { document ->
                                document.toObject(PositionItem::class.java)?.copy(id = document.id)
                            }
                            trySend(positions).isSuccess
                        }
                    }
                awaitClose { listenerRegistration.remove() }
            }
            firestoreFlow.collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)


    // Renamed and modified to accept a list of specific items to sync.
    suspend fun syncListOfLocalPositionsToFirestore(itemsToSyncThisRun: List<PositionItem>) = withContext(Dispatchers.IO) {
        if (itemsToSyncThisRun.isEmpty()) {
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: No items passed to sync this run.")
            return@withContext
        }
        val currentSyncingUserId = FirebaseAuth.getInstance().currentUser?.uid

        if (currentSyncingUserId == null) {
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: No logged-in user. Aborting sync.")
            return@withContext
        }

        Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: User $currentSyncingUserId. Received ${itemsToSyncThisRun.size} items to process.")
        
        // Final safeguard: ensure the list to iterate over is distinct by ID.
        val distinctItemsToSync = itemsToSyncThisRun.distinctBy { it.id }
        Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: After distinctBy ID, processing ${distinctItemsToSync.size} items.")

        // Load ALL current local positions from SP to merge results correctly.
        // Use a map for efficient update/removal. Key by original ID.
        val allCurrentLocalPositionsMap = loadLocalPositions().associateBy { it.id }.toMutableMap()

        val imageUploadCache = mutableMapOf<String, String>() // Cache for this sync operation

        distinctItemsToSync.forEach { localPositionToSync ->
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Processing item ID ${localPositionToSync.id}, Name: ${localPositionToSync.name}, Original ImageName: ${localPositionToSync.imageName}")
            
            val syncedPosition = addOrUpdatePosition(
                position = localPositionToSync,
                imageUriToUpload = null,
                uploadedImageCache = imageUploadCache
            )

            if (syncedPosition != null) {
                // Successfully synced (or updated existing in Firestore).
                // Update the map: remove old local_ ID entry if ID changed, then put the synced version.
                if (localPositionToSync.id != syncedPosition.id && localPositionToSync.id.startsWith("local_")) {
                    allCurrentLocalPositionsMap.remove(localPositionToSync.id)
                    Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Removed old local ID '${localPositionToSync.id}' from map as ID changed to '${syncedPosition.id}'.")
                }
                allCurrentLocalPositionsMap[syncedPosition.id] = syncedPosition // Add/update with the (potentially new) Firestore ID as key
                Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Successfully synced '${syncedPosition.name}'. Updated map with ID '${syncedPosition.id}' and ImageName '${syncedPosition.imageName}'.")
                // Save immediately after updating the map for this item to maximize chance of consistency
                saveLocalPositions(allCurrentLocalPositionsMap.values.toList())
                Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Saved SharedPreferences mid-loop for item '${syncedPosition.name}'.")
            } else {
                // addOrUpdatePosition returned null. This means either:
                // 1. It was an offline-only save (not applicable here as we have a currentSyncingUserId).
                // 2. A Firestore operation failed.
                // In case of failure, the original localPositionToSync remains in allCurrentLocalPositionsMap (if it was there).
                // If it was a NEW item that failed its first sync, it won't be in the map unless addOrUpdatePosition saved it locally first (which it does for anonymous).
                // For sync, we assume itemsToSyncThisRun are already in local storage.
                Log.w("PositionsRepository", "syncListOfLocalPositionsToFirestore: Failed to get a synced version for '${localPositionToSync.name}' (ID: ${localPositionToSync.id}). Original local record (if any) is preserved in map.")
            }
        }

        saveLocalPositions(allCurrentLocalPositionsMap.values.toList())
        Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Sync processing finished for this batch. Saved ${allCurrentLocalPositionsMap.values.size} total items to SharedPreferences.")
    }

    // New method for ViewModel to get all local positions for pre-filtering
    fun getAllLocalPositionsForSync(): List<PositionItem> {
        Log.d("PositionsRepository", "getAllLocalPositionsForSync: Loading all local positions from SharedPreferences.")
        return loadLocalPositions()
    }


    // Get a specific position by its ID (checks local then Firestore)
    suspend fun getPositionById(positionId: String): PositionItem? {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (positionId.startsWith("local_")) {
            return loadLocalPositions().find { it.id == positionId && it.userId == null } // Local items belong to anonymous
        }
        // If not a local ID, try Firestore only if user is logged in
        if (currentUid != null) {
            return try {
                db.collection(POSITIONS_COLLECTION)
                    .document(positionId)
                    .get()
                    .await()
                    .toObject(PositionItem::class.java)?.takeIf { it.userId == currentUid }?.copy(id = positionId)
            } catch (e: Exception) {
                null
            }
        }
        return null // Not found locally, or user is anonymous and ID is not local, or ID doesn't belong to user
    }

    // Delete a position
    suspend fun deletePosition(positionIdToDelete: String, positionNameToDelete: String) {
        val currentDeletingUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (positionIdToDelete.isBlank() && positionNameToDelete.isBlank()) {
            android.util.Log.w("PositionsRepository", "deletePosition: Both ID and Name are blank. Aborting.")
            return
        }
        android.util.Log.d("PositionsRepository", "deletePosition: Attempting to delete item. ID hint: '$positionIdToDelete', Name: '$positionNameToDelete', User: $currentDeletingUserId")

        // --- Step 1: Attempt to delete from local storage ---
        val localPositions = loadLocalPositions()
        var localItemActuallyRemoved = false
        
        // Try removing by ID first if it's a local ID
        if (positionIdToDelete.startsWith("local_")) {
            if (localPositions.removeAll { it.id == positionIdToDelete && it.name == positionNameToDelete }) { // Be more specific
                localItemActuallyRemoved = true
                android.util.Log.d("PositionsRepository", "deletePosition: Removed local item by ID '$positionIdToDelete' and name '$positionNameToDelete'.")
            }
        }
        
        // Always try removing by name, as ID might be Firestore's or local ID was not primary for this call
        if (localPositions.removeAll { it.name == positionNameToDelete }) {
            localItemActuallyRemoved = true
            android.util.Log.d("PositionsRepository", "deletePosition: Removed local item(s) by name '$positionNameToDelete'.")
        }

        if (localItemActuallyRemoved) {
            saveLocalPositions(localPositions)
            android.util.Log.d("PositionsRepository", "deletePosition: Local positions saved after deletion attempt for '$positionNameToDelete'.")
        } else {
            android.util.Log.d("PositionsRepository", "deletePosition: No local item found matching ID '$positionIdToDelete' or name '$positionNameToDelete' for local deletion.")
        }

        // --- Step 2: Attempt to delete from Firestore ---
        if (currentDeletingUserId != null) {
            var firestoreDocIdToProcess: String? = null
            var firestoreImageNameToProcess: String? = null
            var isAssetItemInFirestore = false

            if (!positionIdToDelete.startsWith("local_") && positionIdToDelete.isNotBlank()) {
                // Assume positionIdToDelete is a Firestore ID
                android.util.Log.d("PositionsRepository", "deletePosition: Provided ID '$positionIdToDelete' is likely a Firestore ID. Verifying ownership for user '$currentDeletingUserId'.")
                try {
                    val docSnapshot = db.collection(POSITIONS_COLLECTION).document(positionIdToDelete).get().await()
                    if (docSnapshot.exists()) {
                        val firestorePosition = docSnapshot.toObject(PositionItem::class.java)
                        if (firestorePosition?.userId == currentDeletingUserId) {
                            // Confirm name matches as an extra check, though ID should be unique
                            if (firestorePosition.name == positionNameToDelete) {
                                firestoreDocIdToProcess = docSnapshot.id
                                firestoreImageNameToProcess = firestorePosition.imageName
                                isAssetItemInFirestore = firestorePosition.isAsset
                                android.util.Log.d("PositionsRepository", "deletePosition: Confirmed ownership of Firestore doc '$firestoreDocIdToProcess' (name: '${firestorePosition.name}') for user '$currentDeletingUserId'.")
                            } else {
                                android.util.Log.w("PositionsRepository", "deletePosition: Firestore ID '$positionIdToDelete' owned by user, but name '${firestorePosition.name}' does not match expected '$positionNameToDelete'. Skipping Firestore delete.")
                            }
                        } else {
                            android.util.Log.w("PositionsRepository", "deletePosition: User '$currentDeletingUserId' does not own Firestore document '$positionIdToDelete' (owner: ${firestorePosition?.userId}). Skipping Firestore delete.")
                        }
                    } else {
                        android.util.Log.w("PositionsRepository", "deletePosition: Firestore document '$positionIdToDelete' not found.")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PositionsRepository", "deletePosition: Error fetching Firestore document '$positionIdToDelete'", e)
                }
            } else {
                // positionIdToDelete was local or blank, so search Firestore by name for the current user
                android.util.Log.d("PositionsRepository", "deletePosition: ID was local or blank. Searching Firestore for item named '$positionNameToDelete' for user '$currentDeletingUserId'.")
                try {
                    val querySnapshot = db.collection(POSITIONS_COLLECTION)
                        .whereEqualTo("userId", currentDeletingUserId)
                        .whereEqualTo("name", positionNameToDelete)
                        .limit(1)
                        .get()
                        .await()
                    if (!querySnapshot.isEmpty) {
                        val doc = querySnapshot.documents.first()
                        firestoreDocIdToProcess = doc.id
                        val firestorePosition = doc.toObject(PositionItem::class.java)
                        firestoreImageNameToProcess = firestorePosition?.imageName
                        isAssetItemInFirestore = firestorePosition?.isAsset ?: false
                        android.util.Log.d("PositionsRepository", "deletePosition: Found corresponding Firestore doc ID '$firestoreDocIdToProcess' for name '$positionNameToDelete'.")
                    } else {
                        android.util.Log.d("PositionsRepository", "deletePosition: No Firestore document found with name '$positionNameToDelete' for user '$currentDeletingUserId'.")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PositionsRepository", "deletePosition: Error querying Firestore for item named '$positionNameToDelete'", e)
                }
            }

            // If a Firestore document was identified for deletion:
            if (firestoreDocIdToProcess != null) {
                try {
                    // Delete image from Storage if applicable
                    if (!isAssetItemInFirestore && firestoreImageNameToProcess != null && firestoreImageNameToProcess.startsWith("https://firebasestorage.googleapis.com/")) {
                        try {
                            val imageRef = storage.getReferenceFromUrl(firestoreImageNameToProcess)
                            imageRef.delete().await()
                            android.util.Log.d("PositionsRepository", "deletePosition: Deleted image '$firestoreImageNameToProcess' from Storage for doc '$firestoreDocIdToProcess'.")
                        } catch (e: Exception) {
                            android.util.Log.e("PositionsRepository", "deletePosition: Failed to delete image '$firestoreImageNameToProcess' from Storage for doc '$firestoreDocIdToProcess'", e)
                        }
                    }
                    // Delete Firestore document
                    db.collection(POSITIONS_COLLECTION).document(firestoreDocIdToProcess).delete().await()
                    android.util.Log.d("PositionsRepository", "deletePosition: Deleted document '$firestoreDocIdToProcess' (name: '$positionNameToDelete') from Firestore.")
                } catch (e: Exception) {
                    android.util.Log.e("PositionsRepository", "deletePosition: Failed to delete document '$firestoreDocIdToProcess' from Firestore", e)
                }
            }
        } else {
            android.util.Log.d("PositionsRepository", "deletePosition: User not logged in. Firestore deletion skipped for name '$positionNameToDelete'. Local deletion attempt was made.")
        }
    }

    suspend fun updateFavoriteStatus(positionId: String, isFavorite: Boolean) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (positionId.isBlank()) return

        if (positionId.startsWith("local_")) {
            if (currentUid == null) { // Only anonymous user can modify local items
                val localPositions = loadLocalPositions()
                val index = localPositions.indexOfFirst { it.id == positionId }
                if (index != -1) {
                    localPositions[index] = localPositions[index].copy(isFavorite = isFavorite)
                    saveLocalPositions(localPositions)
                }
            }
        } else if (currentUid != null) { // Logged-in user
            // Before updating, ensure the position belongs to the current user
            val docRef = db.collection(POSITIONS_COLLECTION).document(positionId)
            val documentSnapshot = docRef.get().await()
            if (documentSnapshot.exists() && documentSnapshot.getString("userId") == currentUid) {
                docRef.update("favorite", isFavorite).await()
            }
        }
    }
    // getAllPositionsIncludingDefaults is removed as ViewModel will handle combining asset and user positions.
    // The repository will focus on user-specific data (local or Firestore).
}