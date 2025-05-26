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

    private var assetPositionsList: List<PositionItem> = emptyList()

    // Observe auth state to update userId dynamically
    private var userId: String? = FirebaseAuth.getInstance().currentUser?.uid

    init {
        FirebaseAuth.getInstance().addAuthStateListener { firebaseAuth ->
            userId = firebaseAuth.currentUser?.uid
        }
        // Load asset positions upon initialization
        assetPositionsList = loadAssetsFromDisk(context)
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
        getLocalPositionsPrefs().edit().putString(LOCAL_POSITIONS_KEY, json).apply()
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

// Merges positions from Firestore into local SharedPreferences
    fun mergeAndSaveFirestorePositionsToLocal(firestorePositions: List<PositionItem>) {
        Log.d("PositionsRepository", "mergeAndSaveFirestorePositionsToLocal: Merging ${firestorePositions.size} Firestore positions into local.")
        if (firestorePositions.isEmpty()) {
            Log.d("PositionsRepository", "mergeAndSaveFirestorePositionsToLocal: No Firestore positions to merge.")
            // Optionally, decide if you want to clear local if Firestore is empty for a logged-in user.
            // For now, we'll just return, preserving local items if Firestore is empty.
            return
        }

        val localPositions = loadLocalPositions()
        Log.d("PositionsRepository", "mergeAndSaveFirestorePositionsToLocal: Loaded ${localPositions.size} existing local positions.")

        // Create a map of existing local positions by ID for efficient lookup and update
        val localPositionsMap = localPositions.associateBy { it.id }.toMutableMap()

        // Add or update positions from Firestore. Firestore versions take precedence.
        firestorePositions.forEach { firestorePosition ->
            // Ensure the userId from Firestore is preserved.
            // If a local item with the same ID exists, it will be overwritten by the Firestore version.
            localPositionsMap[firestorePosition.id] = firestorePosition
        }

        val consolidatedList = localPositionsMap.values.toList()
        Log.d("PositionsRepository", "mergeAndSaveFirestorePositionsToLocal: Consolidated list size before saving: ${consolidatedList.size}")
        saveLocalPositions(consolidatedList) // This handles de-duplication and final saving
        Log.d("PositionsRepository", "mergeAndSaveFirestorePositionsToLocal: Finished merging and saving to local SharedPreferences.")
    }

    // Upload image to Firebase Storage and get download URL
    suspend fun uploadImage(
        imageFileUriToUpload: Uri, // Should be a file URI for the actual file to upload
        forUserId: String?,
        deterministicFileName: String // The name (e.g., UUID.jpg) to use in Firebase Storage
    ): String? {
        // This log was already present from a previous attempt, ensuring it's correct.
        Log.d("PositionsRepository", "[[UPLOAD_IMAGE_OLD_START]] File: '$deterministicFileName', User: '$forUserId'")
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

    suspend fun uploadPositionImageWithMetadata(
        imageUriToUpload: Uri, // Content URI from picker
        positionName: String,
        isFavorite: Boolean,
        // Add other relevant primitive fields from PositionItem as needed
        forUserId: String // The ID of the user this position belongs to
    ): Boolean { // Returns true if upload with metadata was successful
        // This log was already present from a previous attempt, ensuring it's correct.
        Log.d("PositionsRepository", "[[UPLOAD_WITH_METADATA_START]] For item: '$positionName', User: '$forUserId'")
        if (forUserId.isBlank()) {
            Log.e("PositionsRepository", "uploadPositionImageWithMetadata: forUserId is blank.")
            return false
        }

        val tempLocalPathForUpload = copyImageToInternalStorage(imageUriToUpload)
        if (tempLocalPathForUpload == null) {
            Log.e("PositionsRepository", "uploadPositionImageWithMetadata: Failed to copy image to internal storage.")
            return false
        }

        val tempLocalFile = File(tempLocalPathForUpload)
        val fileExtension = tempLocalFile.extension.ifBlank { "jpg" }

        // Generate a deterministic file name based on user ID and sanitized position name
        val sanitizedPositionName = positionName
            .replace(Regex("[^a-zA-Z0-9\\-_]"), "_") // Allow alphanumeric, hyphen, underscore
            .lowercase()
            .take(100) // Limit length
        val storageFileName = "${sanitizedPositionName}.$fileExtension"

        Log.d("PositionsRepository", "uploadPositionImageWithMetadata: Inputs -- forUserId: '$forUserId', original positionName: '$positionName', sanitizedPositionName: '$sanitizedPositionName', fileExtension: '$fileExtension', final storageFileName: '$storageFileName'")
        
        // Using a path that the Cloud Function will listen to
        val imageRef = storage.reference.child("$POSITION_IMAGES_STORAGE_PATH/$forUserId/$storageFileName")
        Log.d("PositionsRepository", "uploadPositionImageWithMetadata: Determined storage path: ${imageRef.path}")

        // Check if file already exists at the deterministic path
        Log.d("PositionsRepository", "uploadPositionImageWithMetadata: Attempting to get metadata for ${imageRef.path}")
        try {
            imageRef.metadata.await()
            // File exists, log and skip upload. Assume CF will handle or has handled.
            Log.i("PositionsRepository", "uploadPositionImageWithMetadata: METADATA CHECK SUCCESS - File already exists at ${imageRef.path}. Skipping upload.")
            // Even if file exists, we might want the CF to re-evaluate metadata if it changed,
            // but the CF should be idempotent on Firestore document creation.
            // For now, we consider this "successful" from client's perspective of ensuring image is there.
            tempLocalFile.delete() // Delete the temporary local copy
            return true // Indicate success as image is present or will be processed
        } catch (e: com.google.firebase.storage.StorageException) {
            if (e.errorCode == com.google.firebase.storage.StorageException.ERROR_OBJECT_NOT_FOUND) {
                // File does not exist, proceed with upload
                Log.i("PositionsRepository", "uploadPositionImageWithMetadata: METADATA CHECK INFO - File not found at ${imageRef.path} (ERROR_OBJECT_NOT_FOUND). Proceeding with upload.")
            } else {
                // Other storage error during metadata check
                Log.e("PositionsRepository", "uploadPositionImageWithMetadata: METADATA CHECK STORAGE EXCEPTION for ${imageRef.path}. ErrorCode: ${e.errorCode}, Message: ${e.message}", e)
                tempLocalFile.delete() // Delete temp local copy
                return false
            }
        } catch (e: Exception) {
            // Other unexpected error during metadata check
            Log.e("PositionsRepository", "uploadPositionImageWithMetadata: METADATA CHECK UNEXPECTED EXCEPTION for ${imageRef.path}. Message: ${e.message}", e)
            tempLocalFile.delete()
            return false
        }

        Log.d("PositionsRepository", "uploadPositionImageWithMetadata: Proceeding to build metadata and upload for ${imageRef.path}")
        val metadataBuilder = com.google.firebase.storage.StorageMetadata.Builder()
        metadataBuilder.setCustomMetadata("positionName", positionName)
        metadataBuilder.setCustomMetadata("isFavorite", isFavorite.toString())
        metadataBuilder.setCustomMetadata("isAsset", "false") // New positions via this flow are not assets
        metadataBuilder.setCustomMetadata("originalUserId", forUserId) // Crucial for the Cloud Function
        metadataBuilder.setCustomMetadata("uploadTimestamp", System.currentTimeMillis().toString())
        // Set content type if known, helps Firebase Storage
        context.contentResolver.getType(imageUriToUpload)?.let { mimeType ->
            metadataBuilder.contentType = mimeType
        }

        return try {
            Log.d("PositionsRepository", "Uploading ${tempLocalFile.name} to ${imageRef.path} with metadata.")
            imageRef.putFile(Uri.fromFile(tempLocalFile), metadataBuilder.build()).await()
            Log.d("PositionsRepository", "Successfully uploaded ${tempLocalFile.name} with metadata to ${imageRef.path}.")
            // Delete the temporary local copy after successful upload
            tempLocalFile.delete()
            true
        } catch (e: Exception) {
            Log.e("PositionsRepository", "Failed to upload image with metadata to ${imageRef.path}", e)
            // Attempt to delete the temporary local copy even if upload fails
            tempLocalFile.delete()
            false
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
    fun getUserPositions(): Flow<List<PositionItem>> = callbackFlow<List<PositionItem>> {
        val initialUserId = FirebaseAuth.getInstance().currentUser?.uid
        Log.d("PositionsRepository", "getUserPositions called. Initial UserID: $initialUserId")

        if (initialUserId == null) { // User is anonymous
            Log.d("PositionsRepository", "User is anonymous. Emitting local positions and closing flow.")
            trySend(loadLocalPositions())
            channel.close() // Close as there's no listener to maintain for anonymous
            return@callbackFlow
        }

        // For logged-in users, fetch from Firestore and listen for changes
        Log.d("PositionsRepository", "User $initialUserId. Setting up listener and auth monitor for positions.")

        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser?.uid != initialUserId) {
                Log.w("PositionsRepository", "User changed or logged out ($initialUserId -> ${firebaseAuth.currentUser?.uid}). Closing positions flow for $initialUserId.")
                if (!channel.isClosedForSend) {
                    channel.close() // This will trigger awaitClose
                }
            }
        }
        FirebaseAuth.getInstance().addAuthStateListener(authListener)

        if (channel.isClosedForSend) {
            Log.d("PositionsRepository", "getUserPositions: Channel closed by auth listener for $initialUserId before Firestore listener registration.")
            FirebaseAuth.getInstance().removeAuthStateListener(authListener)
            return@callbackFlow
        }
        
        Log.d("PositionsRepository", "Setting up Firestore listener for positions, userId: $initialUserId")
        val listenerRegistration = db.collection(POSITIONS_COLLECTION)
            .whereEqualTo("userId", initialUserId)
            .addSnapshotListener { snapshot, error ->
                if (channel.isClosedForSend) {
                    Log.d("PositionsRepository", "Positions SnapshotListener for $initialUserId: Channel already closed. Ignoring event.")
                    return@addSnapshotListener
                }

                if (FirebaseAuth.getInstance().currentUser?.uid != initialUserId) {
                     Log.w("PositionsRepository", "Positions SnapshotListener for $initialUserId received event, but user is now ${FirebaseAuth.getInstance().currentUser?.uid}. Closing channel.")
                    if (!channel.isClosedForSend) channel.close()
                    return@addSnapshotListener
                }

                if (error != null) {
                    Log.e("PositionsRepository", "Error in Firestore positions listener for $initialUserId: ", error)
                    if (!channel.isClosedForSend) channel.close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    Log.d("PositionsRepository", "Positions snapshot received for $initialUserId. Document count: ${snapshot.size()}.")
                    val positions = snapshot.documents.mapNotNull { document ->
                        document.toObject(PositionItem::class.java)?.copy(id = document.id)
                    }
                    if (!channel.isClosedForSend) {
                        val sendResult = trySend(positions)
                        if (!sendResult.isSuccess) {
                             Log.w("PositionsRepository", "Failed to send positions to flow for $initialUserId. Channel may be closed. isClosedForSend: ${channel.isClosedForSend}")
                        }
                    }
                } else {
                    Log.d("PositionsRepository", "Positions snapshot was null for $initialUserId, no error.")
                     if (!channel.isClosedForSend) trySend(emptyList())
                }
            }

        awaitClose {
            Log.d("PositionsRepository", "Positions flow for $initialUserId cancelled/closed, removing Firestore listener and auth monitor.")
            listenerRegistration.remove()
            FirebaseAuth.getInstance().removeAuthStateListener(authListener)
        }
    }.flowOn(Dispatchers.IO) // Keep flowOn(Dispatchers.IO) if appropriate for loadLocalPositions or Firestore SDK work


    // Renamed and modified to accept a list of specific items to sync.
    // Returns a list of local IDs that were successfully processed (handed to CF or directly synced)
    suspend fun syncListOfLocalPositionsToFirestore(itemsToSyncThisRun: List<PositionItem>): List<String> = withContext(Dispatchers.IO) {
        val localIdsMarkedAsPotentiallyProcessedInThisRun = mutableListOf<String>()
        val verifiedSuccessfullyProcessedAndRemovedLocalIds = mutableListOf<String>() // This will be returned

        if (itemsToSyncThisRun.isEmpty()) {
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: No items passed to sync this run.")
            return@withContext verifiedSuccessfullyProcessedAndRemovedLocalIds
        }
        val currentSyncingUserId = FirebaseAuth.getInstance().currentUser?.uid

        if (currentSyncingUserId == null) {
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: No logged-in user. Aborting sync.")
            return@withContext verifiedSuccessfullyProcessedAndRemovedLocalIds
        }

        Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: User $currentSyncingUserId. Received ${itemsToSyncThisRun.size} items to process.")
        
        // Final safeguard: ensure the list to iterate over is distinct by ID.
        val distinctItemsToSync = itemsToSyncThisRun.distinctBy { it.id }
        Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: After distinctBy ID, processing ${distinctItemsToSync.size} items.")

        // Load ALL current local positions from SP to merge results correctly.
        // Use a map for efficient update/removal. Key by original ID.
        val allCurrentLocalPositionsMap = loadLocalPositions().associateBy { it.id }.toMutableMap()

        val imageUploadCache = mutableMapOf<String, String>() // Cache for this sync operation
        val itemsForDirectFirestoreBatch = mutableListOf<PositionItem>() // Declare the list

        distinctItemsToSync.forEach { localPositionToSync ->
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Processing item ID ${localPositionToSync.id}, Name: ${localPositionToSync.name}, Original ImageName: ${localPositionToSync.imageName}")

            val isLocalImagePresent = localPositionToSync.imageName.isNotBlank() &&
                                      !localPositionToSync.imageName.startsWith("http") &&
                                      !localPositionToSync.imageName.startsWith("content://")
            
            // Condition: Item is local (ID starts with "local_") AND has a local image path to upload
            if (localPositionToSync.id.startsWith("local_") && isLocalImagePresent) {
                Log.i("PositionsRepository", "SYNC_LOOP: Item '${localPositionToSync.name}' (ID: ${localPositionToSync.id}). Path: Cloud Function Upload.")
                val localImageFile = File(localPositionToSync.imageName)
                if (localImageFile.exists()) {
                    val uploadWithMetadataSuccess = uploadPositionImageWithMetadata(
                        imageUriToUpload = Uri.fromFile(localImageFile),
                        positionName = localPositionToSync.name,
                        isFavorite = localPositionToSync.isFavorite,
                        // Pass other relevant fields if your metadata function expects them
                        forUserId = currentSyncingUserId!! // Not null due to earlier check
                    )

                    if (uploadWithMetadataSuccess) {
                        Log.d("PositionsRepository", "Sync: Successfully uploaded image for '${localPositionToSync.name}' with metadata. Removing local version ID '${localPositionToSync.id}' from map, as Cloud Function will create it.")
                        allCurrentLocalPositionsMap.remove(localPositionToSync.id)
                        // REMOVED: saveLocalPositions(allCurrentLocalPositionsMap.values.toList())
                        if (localPositionToSync.id.startsWith("local_")) {
                            localIdsMarkedAsPotentiallyProcessedInThisRun.add(localPositionToSync.id)
                        }
                        Log.d("PositionsRepository", "Sync: Item '${localPositionToSync.name}' (ID: ${localPositionToSync.id}) processed via CF path. Updated in-memory map. Marked ${localPositionToSync.id} as potentially processed.")
                    } else {
                        Log.e("PositionsRepository", "Sync: Failed to upload image with metadata for '${localPositionToSync.name}'. Local item will remain in map for now.")
                    }
                } else { // Local image file for local_ ID item not found.
                    Log.w("PositionsRepository", "SYNC_LOOP: Item '${localPositionToSync.name}' (ID: ${localPositionToSync.id}). Local image file NOT FOUND at '${localPositionToSync.imageName}'. Will attempt to sync data without image or with existing URL if any.")
                    // Mark for direct Firestore processing, potentially with cleared imageName
                    itemsForDirectFirestoreBatch.add(localPositionToSync.copy(imageName = "")) // Or decide to keep existing imageName if it might be a URL
                }
            } else { // Item does not have a local_ ID and a local image path, or is not local_ ID at all.
                Log.i("PositionsRepository", "SYNC_LOOP: Item '${localPositionToSync.name}' (ID: ${localPositionToSync.id}). Queuing for direct Firestore batch. isLocalImagePresent=${isLocalImagePresent}, idStartsWithLocal=${localPositionToSync.id.startsWith("local_")}")
                itemsForDirectFirestoreBatch.add(localPositionToSync)
            }
        }

        // Now process itemsForDirectFirestoreBatch
        if (itemsForDirectFirestoreBatch.isNotEmpty()) {
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Processing ${itemsForDirectFirestoreBatch.size} items via Firestore batch.")
            val batch = db.batch()
            val positionsCollection = db.collection(POSITIONS_COLLECTION)

            for (itemToBatchProcess in itemsForDirectFirestoreBatch) {
                var positionForFirestore = itemToBatchProcess.copy(userId = currentSyncingUserId)
                var finalImageNameForFirestore = positionForFirestore.imageName
                var existingFirestoreDocId: String? = null

                // 1. Determine existing Firestore document ID (if any)
                if (!positionForFirestore.id.isBlank() && !positionForFirestore.id.startsWith("local_")) {
                    // Assume itemToBatchProcess.id is a Firestore ID, check if it exists and belongs to user
                    try {
                        val doc = positionsCollection.document(positionForFirestore.id).get().await()
                        if (doc.exists() && doc.getString("userId") == currentSyncingUserId) {
                            existingFirestoreDocId = doc.id
                            finalImageNameForFirestore = doc.getString("imageName") ?: positionForFirestore.imageName // Prefer existing cloud URL
                        }
                    } catch (e: Exception) { Log.e("PositionsRepository", "Error checking existing by ID ${positionForFirestore.id}", e) }
                } else { // Local ID or new item, search by name for existing Firestore doc
                    try {
                        val query = positionsCollection
                            .whereEqualTo("userId", currentSyncingUserId)
                            .whereEqualTo("name", positionForFirestore.name)
                            .limit(1).get().await()
                        if (!query.isEmpty) {
                            val doc = query.documents.first()
                            existingFirestoreDocId = doc.id
                            finalImageNameForFirestore = doc.getString("imageName") ?: positionForFirestore.imageName // Prefer existing cloud URL
                        }
                    } catch (e: Exception) { Log.e("PositionsRepository", "Error checking existing by name ${positionForFirestore.name}", e) }
                }

                // 2. Handle image upload if imageName is a local path (and not an asset)
                if (!positionForFirestore.isAsset &&
                    positionForFirestore.imageName.isNotBlank() &&
                    !positionForFirestore.imageName.startsWith("http") &&
                    !positionForFirestore.imageName.startsWith("content://")) {
                    
                    val internalFile = File(positionForFirestore.imageName)
                    if (internalFile.exists()) {
                        val localPathKey = internalFile.absolutePath
                        if (imageUploadCache.containsKey(localPathKey)) {
                            finalImageNameForFirestore = imageUploadCache[localPathKey] ?: ""
                        } else {
                            val uploadedUrl = uploadImage(Uri.fromFile(internalFile), currentSyncingUserId, internalFile.name)
                            finalImageNameForFirestore = uploadedUrl ?: existingFirestoreDocId?.let { positionsCollection.document(it).get().await().getString("imageName") } ?: "" // Fallback
                            if (uploadedUrl != null) imageUploadCache[localPathKey] = uploadedUrl
                        }
                    } else {
                        Log.w("PositionsRepository", "SYNC_BATCH: Local image file not found: ${positionForFirestore.imageName} for item ${positionForFirestore.name}. Will use current/empty imageName.")
                        finalImageNameForFirestore = existingFirestoreDocId?.let { positionsCollection.document(it).get().await().getString("imageName") } ?: "" // Fallback to existing cloud URL or empty
                    }
                }
                positionForFirestore = positionForFirestore.copy(imageName = finalImageNameForFirestore)

                // 3. Add to batch
                val docRefToUse: com.google.firebase.firestore.DocumentReference
                if (existingFirestoreDocId != null) {
                    docRefToUse = positionsCollection.document(existingFirestoreDocId)
                } else {
                    // No existing doc found by initial ID or name query.
                    // Perform a final check by name before creating a new document.
                    // This helps prevent duplicates if the initial name query missed an existing item
                    // or if an item was created by a Cloud Function in the meantime.
                    Log.d("PositionsRepository", "SYNC_BATCH: existingFirestoreDocId is null for '${positionForFirestore.name}'. Performing final name check.")
                    val finalCheckQuery = positionsCollection
                        .whereEqualTo("userId", currentSyncingUserId)
                        .whereEqualTo("name", positionForFirestore.name) // Use the name of the item being processed
                        .limit(1)
                        .get()
                        .await()
                    if (!finalCheckQuery.isEmpty) {
                        val foundDoc = finalCheckQuery.documents.first()
                        docRefToUse = positionsCollection.document(foundDoc.id)
                        Log.i("PositionsRepository", "SYNC_BATCH: Final name check found existing document (ID: ${foundDoc.id}) for '${positionForFirestore.name}'. Using this ID.")
                        // If found by name, ensure the imageName is updated if the current item has a newer/better one
                        // (though finalImageNameForFirestore should already have handled this)
                        positionForFirestore = positionForFirestore.copy(imageName = finalImageNameForFirestore)
                    } else {
                        Log.i("PositionsRepository", "SYNC_BATCH: Final name check found no existing document for '${positionForFirestore.name}'. Creating new document.")
                        docRefToUse = positionsCollection.document() // Create new document ID
                    }
                }
                
                batch.set(docRefToUse, positionForFirestore.copy(id = docRefToUse.id)) // Ensure the object in batch has the correct Firestore ID.
                Log.i("PositionsRepository", "SYNC_BATCH: Queued set for ${positionForFirestore.name} (Firestore ID: ${docRefToUse.id})")
                
                // Update local cache map
                val itemToBatchProcess = itemsForDirectFirestoreBatch.find { it.name == positionForFirestore.name } // Find the original item for its ID
                if (itemToBatchProcess != null && itemToBatchProcess.id != docRefToUse.id && itemToBatchProcess.id.startsWith("local_")) {
                    allCurrentLocalPositionsMap.remove(itemToBatchProcess.id)
                }
                allCurrentLocalPositionsMap[docRefToUse.id] = positionForFirestore.copy(id = docRefToUse.id)
                if (itemToBatchProcess != null && itemToBatchProcess.id.startsWith("local_")) {
                     localIdsMarkedAsPotentiallyProcessedInThisRun.add(itemToBatchProcess.id)
                }
            }

            // The following try block was an erroneous duplication and has been removed.
            // The logic for updating allCurrentLocalPositionsMap and localIdsMarkedAsPotentiallyProcessedInThisRun
            // is correctly handled within the for loop above.

            try {
                batch.commit().await()
                Log.i("PositionsRepository", "syncListOfLocalPositionsToFirestore: Successfully committed batch of ${itemsForDirectFirestoreBatch.size} items.")
            } catch (e: Exception) {
                Log.e("PositionsRepository", "syncListOfLocalPositionsToFirestore: Error committing batch.", e)
                // If batch fails, items remain in allCurrentLocalPositionsMap as they were.
                // localIdsMarkedAsPotentiallyProcessedInThisRun should probably be cleared or handled if batch fails.
                // For now, they will be re-evaluated in verification step.
            }
        } else {
            Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: No items for direct Firestore batch.")
        }
        
        // Final save of SharedPreferences after all processing (both CF path and direct batch path) for this sync run.
        saveLocalPositions(allCurrentLocalPositionsMap.values.toList())
        Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Final save to SharedPreferences. Map size: ${allCurrentLocalPositionsMap.values.size}")

        // Verification step (remains the same)
        val finalLocalStateAfterSync = loadLocalPositions()
        val finalLocalIdsSet = finalLocalStateAfterSync.map { it.id }.toSet()

        localIdsMarkedAsPotentiallyProcessedInThisRun.distinct().forEach { potentialId ->
            if (!finalLocalIdsSet.contains(potentialId)) {
                verifiedSuccessfullyProcessedAndRemovedLocalIds.add(potentialId)
                Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Confirmed: Local ID '$potentialId' was processed and is no longer in SharedPreferences.")
            } else {
                Log.w("PositionsRepository", "syncListOfLocalPositionsToFirestore: Verification FAILED: Local ID '$potentialId' was marked for processing but was still found in SharedPreferences. It will NOT be reported as successfully processed to ViewModel this time.")
            }
        }
        Log.d("PositionsRepository", "syncListOfLocalPositionsToFirestore: Returning ${verifiedSuccessfullyProcessedAndRemovedLocalIds.size} verified processed local IDs: $verifiedSuccessfullyProcessedAndRemovedLocalIds")
        return@withContext verifiedSuccessfullyProcessedAndRemovedLocalIds
    }

    // New method for ViewModel to get all local positions for pre-filtering
    fun getAllLocalPositionsForSync(): List<PositionItem> {
        Log.d("PositionsRepository", "getAllLocalPositionsForSync: Loading all local positions from SharedPreferences.")
        return loadLocalPositions()
    }

    // Helper function to load asset positions from the assets directory
    private fun loadAssetsFromDisk(context: Context): List<PositionItem> {
        val items = mutableListOf<PositionItem>()
        try {
            val imageFileNames = context.assets.list("positions")?.filter {
                it.endsWith(".jpg", ignoreCase = true) ||
                it.endsWith(".jpeg", ignoreCase = true) ||
                it.endsWith(".png", ignoreCase = true)
            } ?: emptyList()

            for (fileName in imageFileNames) {
                val nameWithoutExtension = fileName.substringBeforeLast(".")
                // Consistent naming: Replace underscores, then capitalize words.
                // This capitalization should ideally be a shared utility if used in multiple places for display names.
                // For ID matching, the raw filename is more critical.
                val displayName = nameWithoutExtension.replace("_", " ").split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }
                }
                val assetId = "asset_$fileName" // e.g., asset_Lazyman.jpg

                items.add(
                    PositionItem(
                        id = assetId,
                        name = displayName,
                        imageName = fileName, // Filename like "Lazyman.jpg"
                        isAsset = true,
                        userId = "", // Assets don't have a userId
                        isFavorite = false // Default, actual favorite status comes from Favorites system
                    )
                )
            }
            Log.d("PositionsRepository", "Loaded ${items.size} asset positions from disk.")
        } catch (e: IOException) {
            Log.e("PositionsRepository", "Error loading asset positions from disk", e)
        }
        return items
    }

    // Get a specific position by its ID (checks assets, local, then Firestore)
    suspend fun getPositionById(positionId: String): PositionItem? {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid

        // 1. Check asset positions first (these are global and don't depend on login state)
        // Assuming asset IDs are prefixed with "asset_" or match a known asset naming convention.
        // The check `it.id == positionId` should suffice if IDs are consistent.
        // The problem description implies IDs like "asset_Flatiron.jpg" are used.
        val assetPosition = assetPositionsList.find { it.id == positionId }
        if (assetPosition != null) {
            Log.d("PositionsRepository", "getPositionById: Found '$positionId' in assetPositionsList.")
            return assetPosition
        }

        // 2. Check local positions (SharedPreferences) if ID starts with "local_"
        if (positionId.startsWith("local_")) {
            val localPosition = loadLocalPositions().find { it.id == positionId && it.userId == null } // Local items belong to anonymous
            if (localPosition != null) {
                Log.d("PositionsRepository", "getPositionById: Found '$positionId' in local SharedPreferences.")
                return localPosition
            }
        }

        // 3. Check Firestore if user is logged in and ID is not local_ (could be Firestore ID)
        if (currentUid != null && !positionId.startsWith("local_")) {
            try {
                val firestoreDoc = db.collection(POSITIONS_COLLECTION)
                    .document(positionId)
                    .get()
                    .await()
                if (firestoreDoc.exists()) {
                    val firestorePositionItem = firestoreDoc.toObject(PositionItem::class.java)?.copy(id = firestoreDoc.id)
                    // Ensure the fetched position belongs to the current user
                    if (firestorePositionItem?.userId == currentUid) {
                        Log.d("PositionsRepository", "getPositionById: Found '$positionId' in Firestore for user '$currentUid'.")
                        return firestorePositionItem
                    } else {
                        Log.d("PositionsRepository", "getPositionById: Firestore document '$positionId' found, but userId mismatch (docUser: ${firestorePositionItem?.userId}, currentUser: $currentUid).")
                    }
                } else {
                     Log.d("PositionsRepository", "getPositionById: Firestore document '$positionId' not found.")
                }
            } catch (e: Exception) {
                Log.e("PositionsRepository", "getPositionById: Error fetching '$positionId' from Firestore", e)
                return null // Error during Firestore fetch
            }
        }
        
        Log.d("PositionsRepository", "getPositionById: Position '$positionId' not found in assets, local, or user's Firestore.")
        return null // Not found
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

    suspend fun getAllUserPositionsOnce(userId: String): Result<List<PositionItem>> {
        return try {
            if (userId.isBlank()) {
                Log.w("PositionsRepository", "getAllUserPositionsOnce called with empty userId.")
                return Result.failure(IllegalArgumentException("User ID cannot be empty"))
            }
            Log.d("PositionsRepository", "Fetching all positions once for userId: $userId")
            val snapshot = db.collection(POSITIONS_COLLECTION)
                .whereEqualTo("userId", userId)
                .get()
                .await()
            
            val positions = snapshot.documents.mapNotNull { document ->
                try {
                    document.toObject(PositionItem::class.java)?.copy(id = document.id)
                } catch (e: Exception) {
                    Log.e("PositionsRepository", "Error converting document ${document.id} to PositionItem", e)
                    null // Skip problematic document
                }
            }
            Log.d("PositionsRepository", "Successfully fetched ${positions.size} positions once for userId: $userId")
            Result.success(positions)
        } catch (e: Exception) {
            Log.e("PositionsRepository", "Error in getAllUserPositionsOnce for userId $userId", e)
            Result.failure(e)
        }
    }
}
