package com.velvettouch.nosafeword

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.toObjects // Correct KTX import for lists
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FavoritesRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun getFavoritesCollection() = firestore.collection("favorites")

    suspend fun addFavorite(itemId: String, itemType: String): Result<Unit> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in"))
        // Check if already favorited to prevent duplicates, though Firestore listeners will also handle this
        if (isItemFavorited(itemId, itemType).getOrNull() == true) {
            return Result.success(Unit) // Already a favorite
        }
        val favorite = Favorite(itemId = itemId, itemType = itemType, userId = userId)
        return try {
            getFavoritesCollection().add(favorite).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFavorite(itemId: String, itemType: String): Result<Unit> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in"))
        return try {
            val querySnapshot = getFavoritesCollection()
                .whereEqualTo("user_id", userId)
                .whereEqualTo("item_id", itemId)
                .whereEqualTo("item_type", itemType)
                .get()
                .await()
            for (document in querySnapshot.documents) {
                document.reference.delete().await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getFavorites(): Flow<List<Favorite>> = callbackFlow {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.d("FavoritesRepository", "getFavorites: User not logged in. Sending empty list and closing channel.")
            trySend(emptyList())
            channel.close() // Close successfully without an error
            return@callbackFlow
        }

        Log.d("FavoritesRepository", "getFavorites: User $userId logged in. Performing priming read from server.")

        // Perform a priming read from the server first
        try {
            val initialSnapshot = getFavoritesCollection()
                .whereEqualTo("user_id", userId)
                .orderBy("timestamp")
                .get(Source.SERVER) // Force fetch from server for priming read
                .await()
            // Using toObjects KTX for cleaner mapping, though individual mapping with ID setting is also fine.
            // If 'id' field is critical and not directly part of Firestore doc, manual map is better.
            // For now, assuming Favorite data class matches Firestore structure or 'id' is handled.
            val initialFavorites = initialSnapshot.toObjects<Favorite>().mapNotNull { fav ->
                // If doc.id needs to be mapped to fav.id, we'd iterate documents:
                // initialSnapshot.documents.mapNotNull { doc -> doc.toObject<Favorite>()?.apply { id = doc.id } }
                fav // Assuming Favorite class is directly mappable or ID is handled elsewhere/not needed here
            }
            Log.d("FavoritesRepository", "getFavorites priming read for user $userId. Documents: ${initialFavorites.size}")
            trySend(initialFavorites) // Send initial server data
        } catch (e: Exception) {
            Log.e("FavoritesRepository", "getFavorites priming read error for user $userId", e)
            // Don't close the channel on priming read failure, listener might still work or recover.
            // Or, if priming read is critical, you might choose to channel.close(e)
            // For now, we'll let the listener try.
        }

        Log.d("FavoritesRepository", "getFavorites: User $userId. Setting up Firestore listener for subsequent updates.")
        val listenerRegistration = getFavoritesCollection()
            .whereEqualTo("user_id", userId)
            .orderBy("timestamp")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FavoritesRepository", "getFavorites listener error for user $userId", error)
                    channel.close(error) // Close with actual Firestore error
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    // Only process if metadata indicates it's not from cache,
                    // or if you want all updates including local cache changes.
                    // For simplicity here, we process all snapshots after the initial priming read.
                    // if (!snapshot.metadata.hasPendingWrites() && !snapshot.metadata.isFromCache) {
                    Log.d("FavoritesRepository", "getFavorites listener update for user $userId. Documents: ${snapshot.size()}. FromCache: ${snapshot.metadata.isFromCache}")
                    val favorites = snapshot.toObjects<Favorite>().mapNotNull { fav ->
                        // fav.id = find corresponding document id if necessary, or ensure Favorite has @DocumentId
                        fav
                    }
                    // If you need to set the document ID on your Favorite objects:
                    // val favorites = snapshot.documents.mapNotNull { doc ->
                    //    doc.toObject<Favorite>()?.apply { id = doc.id }
                    // }
                    trySend(favorites)
                    // } else {
                    //    Log.d("FavoritesRepository", "getFavorites listener update for user $userId skipped (pending writes or from cache).")
                    // }
                } else {
                    Log.d("FavoritesRepository", "getFavorites listener snapshot for user $userId was null.")
                    // Consider if sending emptyList() here is appropriate if snapshot is null post-priming.
                    // It might mean data was deleted.
                    trySend(emptyList())
                }
            }

        awaitClose {
            Log.d("FavoritesRepository", "getFavorites: awaitClose for user $userId, removing listener.")
            listenerRegistration.remove()
        }
    }

    suspend fun isItemFavorited(itemId: String, itemType: String): Result<Boolean> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in"))
        return try {
            val querySnapshot = getFavoritesCollection()
                .whereEqualTo("user_id", userId)
                .whereEqualTo("item_id", itemId)
                .whereEqualTo("item_type", itemType)
                .limit(1)
                .get(Source.SERVER) // Force fetch from server
                .await()
            Result.success(!querySnapshot.isEmpty)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addFavoritesBatch(favoritesToAdd: List<Favorite>): Result<Unit> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in"))
        if (favoritesToAdd.isEmpty()) return Result.success(Unit)

        return try {
            val batch = firestore.batch()
            val favoritesCollection = getFavoritesCollection()

            for (favorite in favoritesToAdd) {
                // Ensure the favorite has the correct userId, though it should be set by the caller
                val docRef = favoritesCollection.document() // Create new document for each favorite
                batch.set(docRef, favorite.copy(userId = userId))
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FavoritesRepository", "Error adding favorites in batch for user $userId", e)
            Result.failure(e)
        }
    }

    // New function to get a one-time snapshot of cloud favorites for the current user
    suspend fun getCloudFavoritesListSnapshot(): Result<List<Favorite>> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception("User not logged in for snapshot"))
        return try {
            val snapshot = getFavoritesCollection()
                .whereEqualTo("user_id", userId)
                .orderBy("timestamp") // Keep consistent with the Flow's ordering
                .get(Source.SERVER) // Crucial: fetch from server
                .await()
            // val favorites = snapshot.documents.mapNotNull { doc ->
            //    doc.toObject<Favorite>()?.apply { id = doc.id }
            // }
            // Using KTX toObjects for cleaner conversion
            val favorites = snapshot.toObjects<Favorite>()
            Result.success(favorites)
        } catch (e: Exception) {
            Log.e("FavoritesRepository", "Error getting cloud favorites snapshot for user $userId", e)
            Result.failure(e)
        }
    }
}