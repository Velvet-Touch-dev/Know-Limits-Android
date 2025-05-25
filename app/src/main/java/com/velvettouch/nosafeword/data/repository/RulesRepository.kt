package com.velvettouch.nosafeword.data.repository

import com.velvettouch.nosafeword.data.model.RuleItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import kotlinx.coroutines.flow.Flow
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.google.firebase.database.DatabaseReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

// Using the same UserNotLoggedInException from TasksRepository or define locally if preferred
// For now, assuming it's accessible or we can redefine if necessary.

class RulesRepository(private val userRepository: UserRepository) {

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private suspend fun getRulesRefForCurrentUser(): DatabaseReference? {
        val currentUser = auth.currentUser ?: return null
        val userProfile = userRepository.getUserProfile(currentUser.uid)

        return if (userProfile?.pairedWith != null) {
            val partnerUid = userProfile.pairedWith!!
            // Ensure consistent pairingId for rules, same as tasks
            val pairingId = listOf(currentUser.uid, partnerUid).sorted().joinToString("_")
            database.getReference("shared_task_lists/$pairingId/rules") // Changed 'tasks' to 'rules'
        } else {
            database.getReference("users/${currentUser.uid}/rules") // Changed 'tasks' to 'rules'
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllRules(): Flow<List<RuleItem>> {
        return auth.currentUser?.uid?.let { uid ->
            userRepository.getUserProfileFlow(uid).flatMapLatest { userProfile ->
                val currentUserId = auth.currentUser?.uid
                if (currentUserId == null) {
                    Timber.w("User signed out while observing profile for rules.")
                    return@flatMapLatest flowOf(emptyList<RuleItem>())
                }

                val rulesRefPath = if (userProfile?.pairedWith != null) {
                    val partnerUid = userProfile.pairedWith!!
                    val pairingId = listOf(currentUserId, partnerUid).sorted().joinToString("_")
                    "shared_task_lists/$pairingId/rules"
                } else {
                    "users/$currentUserId/rules"
                }
                val rulesRef = database.getReference(rulesRefPath)
                Timber.d("Observing rules at path: $rulesRefPath")

                callbackFlow {
                    val eventListener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val ruleList = snapshot.children.mapNotNull { it.getValue(RuleItem::class.java) }
                            Timber.d("Rules data changed at $rulesRefPath, sending ${ruleList.size} items.")
                            trySend(ruleList.sortedBy { it.order })
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Timber.e(error.toException(), "Firebase rules listener cancelled for $rulesRefPath")
                            close(error.toException())
                        }
                    }
                    rulesRef.addValueEventListener(eventListener)
                    awaitClose {
                        Timber.d("getAllRules flow closing for $rulesRefPath. Removing listener.")
                        rulesRef.removeEventListener(eventListener)
                    }
                }
            }
        } ?: flowOf(emptyList())
    }

    suspend fun addRule(rule: RuleItem): Result<Unit> {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.e("Cannot add rule: User not logged in.")
            return Result.failure(UserNotLoggedInException("User not logged in to add rule."))
        }

        // Ensure createdByUid and createdByName are set if not already
        val ruleWithCreatorInfo = rule.copy(
            createdByUid = currentUser.uid,
            createdByName = rule.createdByName ?: currentUser.displayName ?: "Unknown"
        )
        val rulesRef = getRulesRefForCurrentUser()

        if (rulesRef == null) {
            Timber.e("Cannot add rule: Rules reference is null.")
            return Result.failure(UserNotLoggedInException("Rules reference became null."))
        }

        return suspendCoroutine { continuation ->
            rulesRef.child(ruleWithCreatorInfo.id).setValue(ruleWithCreatorInfo)
                .addOnSuccessListener {
                    Timber.d("Rule added successfully: ${ruleWithCreatorInfo.id} to ${rulesRef.path}")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to add rule: ${ruleWithCreatorInfo.id} to ${rulesRef.path}")
                    continuation.resume(Result.failure(e))
                }
        }
    }

    suspend fun updateRule(rule: RuleItem): Result<Unit> {
        val rulesRef = getRulesRefForCurrentUser()
        if (rulesRef == null) {
            Timber.e("Cannot update rule: User not logged in or rules reference is null.")
            return Result.failure(UserNotLoggedInException("User not logged in to update rule."))
        }
        // Update the timestamp on every update
        val ruleToUpdate = rule.copy(lastUpdatedTimestamp = System.currentTimeMillis())
        return suspendCoroutine { continuation ->
            rulesRef.child(ruleToUpdate.id).setValue(ruleToUpdate)
                .addOnSuccessListener {
                    Timber.d("Rule updated successfully: ${ruleToUpdate.id} at ${rulesRef.path}")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to update rule: ${ruleToUpdate.id} at ${rulesRef.path}")
                    continuation.resume(Result.failure(e))
                }
        }
    }

    suspend fun deleteRule(ruleId: String): Result<Unit> {
        val rulesRef = getRulesRefForCurrentUser()
        if (rulesRef == null) {
            Timber.e("Cannot delete rule: User not logged in or rules reference is null.")
            return Result.failure(UserNotLoggedInException("User not logged in to delete rule."))
        }
        return suspendCoroutine { continuation ->
            rulesRef.child(ruleId).removeValue()
                .addOnSuccessListener {
                    Timber.d("Rule deleted successfully: $ruleId from ${rulesRef.path}")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to delete rule: $ruleId from ${rulesRef.path}")
                    continuation.resume(Result.failure(e))
                }
        }
    }

    suspend fun updateRuleOrder(rulesToUpdate: List<RuleItem>): Result<Unit> {
        val rulesRef = getRulesRefForCurrentUser()
        if (rulesRef == null) {
            Timber.e("Cannot update rule order: User not logged in or rules reference is null.")
            return Result.failure(UserNotLoggedInException("User not logged in to update rule order."))
        }
        return suspendCoroutine { continuation ->
            // Ensure order is updated and also lastUpdatedTimestamp for each rule being reordered
            val updates = mutableMapOf<String, Any>()
            rulesToUpdate.forEach { rule ->
                updates["/${rule.id}/order"] = rule.order
                updates["/${rule.id}/lastUpdatedTimestamp"] = System.currentTimeMillis()
            }

            rulesRef.updateChildren(updates)
                .addOnSuccessListener {
                    Timber.d("Rules order updated successfully at ${rulesRef.path}")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to update rules order at ${rulesRef.path}")
                    continuation.resume(Result.failure(e))
                }
        }
    }
}
