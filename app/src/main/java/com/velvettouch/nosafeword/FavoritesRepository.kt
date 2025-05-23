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
            trySend(emptyList())
            close(Exception("User not logged in"))
            return@callbackFlow
        }

        val listenerRegistration = getFavoritesCollection()
            .whereEqualTo("user_id", userId)
            .orderBy("timestamp") // Assuming you want them in order of addition
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("FavoritesRepository", "getFavorites listener error", error)
                    close(error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    Log.d("FavoritesRepository", "getFavorites snapshot received. Documents count: ${snapshot.size()}")
                    val favorites = snapshot.documents.mapNotNull { doc ->
                        val favorite = doc.toObject<Favorite>()?.apply { id = doc.id }
                        Log.d("FavoritesRepository", "Mapping doc: ${doc.id} to $favorite")
                        favorite
                    }
                    Log.d("FavoritesRepository", "Mapped favorites list. Size: ${favorites.size}. Sending to flow.")
                    trySend(favorites).isSuccess
                } else {
                    Log.d("FavoritesRepository", "getFavorites snapshot was null.")
                }
            }
        awaitClose { listenerRegistration.remove() }
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