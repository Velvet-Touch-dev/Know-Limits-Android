package com.velvettouch.nosafeword

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine // Add this for combining flows

class PositionsRepository {

    private val db = Firebase.firestore
    private val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous" // Handle anonymous or unauthenticated users
    private val storage = Firebase.storage

    companion object {
        private const val POSITIONS_COLLECTION = "positions"
        private const val POSITION_IMAGES_STORAGE_PATH = "position_images"
    }

    // Upload image to Firebase Storage and get download URL
    suspend fun uploadImage(imageUri: Uri): String? {
        if (userId == "anonymous") return null // Anonymous users cannot upload images
        // Create a unique filename for the image
        val filename = UUID.randomUUID().toString()
        val imageRef = storage.reference.child("$POSITION_IMAGES_STORAGE_PATH/$userId/$filename")

        return try {
            val uploadTask = imageRef.putFile(imageUri).await()
            val downloadUrl = uploadTask.storage.downloadUrl.await()
            downloadUrl.toString()
        } catch (e: Exception) {
            // Handle exceptions like FirebaseStorageException
            e.printStackTrace()
            null
        }
    }

    // Add a new position or update an existing one
    suspend fun addOrUpdatePosition(position: PositionItem) {
        val positionWithUserId = position.copy(userId = userId)
        if (positionWithUserId.id.isBlank()) {
            // New position, let Firestore generate ID
            db.collection(POSITIONS_COLLECTION)
                .add(positionWithUserId)
                .await()
        } else {
            // Existing position, update it
            db.collection(POSITIONS_COLLECTION)
                .document(positionWithUserId.id)
                .set(positionWithUserId)
                .await()
        }
    }

    // Get all positions for the current user (real-time updates)
    fun getUserPositions(): Flow<List<PositionItem>> = callbackFlow {
        if (userId == "anonymous") {
            trySend(emptyList<PositionItem>())
            close() // Close the flow for anonymous users
            return@callbackFlow
        }

        val listenerRegistration = db.collection(POSITIONS_COLLECTION)
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error) // Close the flow with an error
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val positions = snapshot.documents.mapNotNull { document ->
                        document.toObject(PositionItem::class.java)?.copy(id = document.id)
                    }
                    trySend(positions).isSuccess
                }
            }
        // When the flow is cancelled, remove the listener
        awaitClose { listenerRegistration.remove() }
    }

    // Get a specific position by its ID
    suspend fun getPositionById(positionId: String): PositionItem? {
        if (userId == "anonymous") return null
        return db.collection(POSITIONS_COLLECTION)
            .document(positionId)
            .get()
            .await()
            .toObject(PositionItem::class.java)
    }

    // Delete a position and its associated image from Firebase Storage
    suspend fun deletePosition(positionId: String) {
        if (userId == "anonymous" || positionId.isBlank()) return // Anonymous users cannot delete, or if ID is missing

        try {
            // 1. Get the position details to find the image URL
            val positionDocument = db.collection(POSITIONS_COLLECTION).document(positionId).get().await()
            val positionItem = positionDocument.toObject(PositionItem::class.java)

            if (positionItem != null && !positionItem.isAsset && positionItem.imageName.startsWith("https://firebasestorage.googleapis.com/")) {
                // 2. If it's a Firebase Storage URL, delete the image from Storage
                try {
                    val imageRef = storage.getReferenceFromUrl(positionItem.imageName)
                    imageRef.delete().await()
                } catch (e: Exception) {
                    // Log error or handle cases where image deletion fails (e.g., file not found, permissions)
                    // For now, we'll print the stack trace and proceed to delete the Firestore document
                    e.printStackTrace()
                }
            }

            // 3. Delete the Firestore document
            db.collection(POSITIONS_COLLECTION)
                .document(positionId)
                .delete()
                .await()

        } catch (e: Exception) {
            // Handle exceptions during Firestore document fetch or delete
            e.printStackTrace()
            // You might want to throw the exception or handle it more gracefully
        }
    }

    suspend fun updateFavoriteStatus(positionId: String, isFavorite: Boolean) {
        if (userId == "anonymous" || positionId.isBlank()) return // Cannot update for anonymous or if ID is missing
        db.collection(POSITIONS_COLLECTION)
            .document(positionId)
            .update("isFavorite", isFavorite)
            .await()
    }

    // Get all default (asset) positions - This might still come from local assets
    // or a separate "default_positions" collection in Firestore if you want them cloud-managed.
    // For now, assuming these are still primarily local or managed differently.
    // If you want to fetch default positions from Firestore, you'd add a method here.
    // e.g., fun getDefaultPositions(): Flow<List<PositionItem>>

    // Example: If you also store default positions in Firestore (e.g., with a specific userId like "system_default")
    fun getAllPositionsIncludingDefaults(): Flow<List<PositionItem>> = callbackFlow {
        if (userId == "anonymous") {
            // For anonymous users, you might only want to show default/asset positions.
            // This part needs to be defined based on how you store/retrieve defaults.
            // For now, emitting empty list for simplicity if defaults aren't cloud-based.
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        // Listener for user-specific positions
        val userPositionsListener = db.collection(POSITIONS_COLLECTION)
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val userPositions = snapshot.documents.mapNotNull { document ->
                        document.toObject(PositionItem::class.java)?.copy(id = document.id)
                    }
                    // This will be combined with default positions below.
                    // For now, just sending user positions. If you have defaults in another flow, combine them.
                    trySend(userPositions).isSuccess
                }
            }

        // Placeholder: If you had a separate flow for default/asset positions from Firestore:
        // val defaultPositionsFlow: Flow<List<PositionItem>> = callbackFlow { /* ... listener for defaults ... */ awaitClose { /* remove listener */ } }
        //
        // Then you would combine them:
        // combine(userPositionsFlow, defaultPositionsFlow) { user, defaults -> user + defaults }
        // .collect { combinedList -> trySend(combinedList).isSuccess }
        //
        // For now, since we only have the user positions listener directly here:
        awaitClose {
            userPositionsListener.remove()
            // defaultPositionsListener.remove() // if you had one
        }
    }
}