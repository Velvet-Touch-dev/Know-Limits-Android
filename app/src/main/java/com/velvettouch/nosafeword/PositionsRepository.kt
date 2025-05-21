package com.velvettouch.nosafeword

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.google.firebase.firestore.QuerySnapshot

class PositionsRepository {

    private val db = Firebase.firestore
    private val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous" // Handle anonymous or unauthenticated users

    companion object {
        private const val POSITIONS_COLLECTION = "positions"
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

    // Get all positions for the current user
    fun getUserPositions(): Flow<List<PositionItem>> = flow {
        if (userId == "anonymous") {
            emit(emptyList<PositionItem>()) // No custom positions for anonymous users
            return@flow
        }
        val snapshot = db.collection(POSITIONS_COLLECTION)
            .whereEqualTo("userId", userId)
            .get()
            .await()
        val positions = snapshot.toObjects(PositionItem::class.java)
        emit(positions)
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

    // Delete a position
    suspend fun deletePosition(positionId: String) {
        if (userId == "anonymous") return // Anonymous users cannot delete
        db.collection(POSITIONS_COLLECTION)
            .document(positionId)
            .delete()
            .await()
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
    fun getAllPositionsIncludingDefaults(): Flow<List<PositionItem>> = flow {
        if (userId == "anonymous") {
            // Potentially load only defaults for anonymous users
            // For now, let's assume defaults are handled locally or via a separate mechanism
            emit(emptyList())
            return@flow
        }

        val userPositionsSnapshot = db.collection(POSITIONS_COLLECTION)
            .whereEqualTo("userId", userId)
            .get()
            .await()
        val userPositions = userPositionsSnapshot.toObjects(PositionItem::class.java)

        // Placeholder for fetching default/asset positions if they were also in Firestore
        // For example, if defaults have `isAsset == true` and no specific `userId` or a special one.
        // val defaultPositionsSnapshot = db.collection(POSITIONS_COLLECTION)
        //     .whereEqualTo("isAsset", true) // Or another marker for default items
        //     .get()
        //     .await()
        // val defaultPositions = defaultPositionsSnapshot.toObjects(PositionItem::class.java)
        // emit(userPositions + defaultPositions)

        emit(userPositions) // For now, just emitting user-specific positions
    }
}