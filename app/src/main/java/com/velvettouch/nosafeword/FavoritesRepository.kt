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
        val initialUserId = auth.currentUser?.uid // Capture initial user ID

        if (initialUserId == null) {
            Log.d("FavoritesRepository", "getFavorites: User not logged in at flow collection. Sending empty list and closing.")
            trySend(emptyList())
            channel.close()
            return@callbackFlow
        }

        Log.d("FavoritesRepository", "getFavorites: User $initialUserId. Setting up listener and auth monitor.")

        // Auth listener specific to this flow instance
        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser?.uid != initialUserId) {
                Log.w("FavoritesRepository", "User changed or logged out ($initialUserId -> ${firebaseAuth.currentUser?.uid}). Closing favorites flow for $initialUserId.")
                if (!channel.isClosedForSend) { // Prevent closing if already closed
                    channel.close() // This will trigger awaitClose
                }
            }
        }
        auth.addAuthStateListener(authListener)

        // Perform a priming read from the server first
        try {
            // Ensure we don't proceed if channel was closed by authListener already
            if (channel.isClosedForSend) {
                 Log.d("FavoritesRepository", "getFavorites priming read: Channel closed by auth listener for $initialUserId before priming read.")
                 // awaitClose will handle cleanup, so just return
                 return@callbackFlow
            }
            val initialSnapshot = getFavoritesCollection()
                .whereEqualTo("user_id", initialUserId)
                .orderBy("timestamp")
                .get(Source.SERVER) // Force fetch from server for priming read
                .await()
            val initialFavorites = initialSnapshot.toObjects<Favorite>().mapNotNull { fav ->
                fav
            }
            Log.d("FavoritesRepository", "getFavorites priming read for user $initialUserId. Documents: ${initialFavorites.size}")
            if (!channel.isClosedForSend) { // Check before sending
                trySend(initialFavorites)
            }
        } catch (e: Exception) {
            Log.e("FavoritesRepository", "getFavorites priming read error for user $initialUserId", e)
            if (!channel.isClosedForSend) {
                 // If priming read fails, and channel is still open, decide whether to close or let listener attempt.
                 // For now, logging error and letting listener attempt. If critical, channel.close(e)
            }
        }

        // Ensure we don't set up listener if channel was closed by authListener or priming read issue
        if (channel.isClosedForSend) {
            Log.d("FavoritesRepository", "getFavorites listener setup: Channel closed for $initialUserId before listener registration.")
            // awaitClose will handle cleanup
            return@callbackFlow
        }
        
        Log.d("FavoritesRepository", "getFavorites: User $initialUserId. Setting up Firestore listener for subsequent updates.")
        val listenerRegistration = getFavoritesCollection()
            .whereEqualTo("user_id", initialUserId)
            .orderBy("timestamp")
            .addSnapshotListener { snapshot, error ->
                if (channel.isClosedForSend) { // If authListener closed it, just return
                    Log.d("FavoritesRepository", "SnapshotListener for $initialUserId: Channel already closed. Ignoring event.")
                    return@addSnapshotListener
                }
                
                // This explicit check might be redundant if authListener is quick, but adds safety.
                if (auth.currentUser?.uid != initialUserId) {
                    Log.w("FavoritesRepository", "SnapshotListener for $initialUserId received event, but user is now ${auth.currentUser?.uid}. Closing channel.")
                    channel.close() // This will trigger awaitClose
                    return@addSnapshotListener
                }

                if (error != null) {
                    Log.e("FavoritesRepository", "getFavorites listener error for user $initialUserId", error)
                    channel.close(error) 
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    Log.d("FavoritesRepository", "getFavorites listener update for user $initialUserId. Documents: ${snapshot.size()}. FromCache: ${snapshot.metadata.isFromCache}")
                    val favorites = snapshot.toObjects<Favorite>().mapNotNull { fav ->
                        fav
                    }
                    trySend(favorites)
                } else {
                    Log.d("FavoritesRepository", "getFavorites listener snapshot for user $initialUserId was null.")
                    trySend(emptyList())
                }
            }

        awaitClose {
            Log.d("FavoritesRepository", "getFavorites: awaitClose for user $initialUserId, removing listener and auth monitor.")
            listenerRegistration.remove()
            auth.removeAuthStateListener(authListener) // Clean up the auth listener
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
            val favoritesCollection = getFavoritesCollection()
            val itemsToActuallyWrite = mutableListOf<Favorite>()

            // Check each candidate favorite for existence before adding to batch
            for (favCandidate in favoritesToAdd) {
                val existingQuery = favoritesCollection
                    .whereEqualTo("user_id", userId)
                    .whereEqualTo("item_id", favCandidate.itemId)
                    .whereEqualTo("item_type", favCandidate.itemType)
                    .limit(1)
                    .get() // Using default source, server preferred if critical but adds latency
                    .await()
                
                if (existingQuery.isEmpty) {
                    itemsToActuallyWrite.add(favCandidate.copy(userId = userId)) // Ensure userId is set
                } else {
                    Log.d("FavoritesRepository", "Favorite already exists, skipping: ${favCandidate.itemId} (${favCandidate.itemType}) for user $userId")
                }
            }

            if (itemsToActuallyWrite.isNotEmpty()) {
                val batch = firestore.batch()
                itemsToActuallyWrite.forEach { favToWrite ->
                    val docRef = favoritesCollection.document() // Generate new unique document ID
                    batch.set(docRef, favToWrite) // favToWrite already has userId
                }
                batch.commit().await()
                Log.d("FavoritesRepository", "Successfully batched ${itemsToActuallyWrite.size} new favorites for user $userId.")
            } else {
                Log.d("FavoritesRepository", "No new favorites to batch-add for user $userId (all candidates already exist).")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FavoritesRepository", "Error adding/checking favorites in batch for user $userId", e)
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
