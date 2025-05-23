package com.velvettouch.nosafeword

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
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

        Log.d("FavoritesRepository", "getFavorites: User $userId logged in. Setting up Firestore listener.")
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
                    Log.d("FavoritesRepository", "getFavorites snapshot for user $userId. Documents: ${snapshot.size()}")
                    val favorites = snapshot.documents.mapNotNull { doc ->
                        doc.toObject<Favorite>()?.apply { id = doc.id }
                    }
                    trySend(favorites)
                } else {
                    Log.d("FavoritesRepository", "getFavorites snapshot for user $userId was null.")
                    // Optionally send empty list if null snapshot means no favorites,
                    // or let it be if the listener will fire again.
                    // For safety, sending empty list if snapshot is null and no error might be good.
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
                .get()
                .await()
            Result.success(!querySnapshot.isEmpty)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}