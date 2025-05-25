package com.velvettouch.nosafeword.data.repository

import com.velvettouch.nosafeword.data.model.TaskItem
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
import com.velvettouch.nosafeword.data.model.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // Added import

// Custom exception for clarity
class UserNotLoggedInException(message: String = "User not logged in.") : Exception(message)


class TasksRepository(private val userRepository: UserRepository) {

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private suspend fun getTasksRefForCurrentUser(): DatabaseReference? {
        val currentUser = auth.currentUser ?: return null
        val userProfile = userRepository.getUserProfile(currentUser.uid)

        return if (userProfile?.pairedWith != null) {
            val partnerUid = userProfile.pairedWith!!
            val pairingId = listOf(currentUser.uid, partnerUid).sorted().joinToString("_")
            database.getReference("shared_task_lists/$pairingId/tasks")
        } else {
            database.getReference("users/${currentUser.uid}/tasks")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllTasks(): Flow<List<TaskItem>> {
        return auth.currentUser?.uid?.let { uid ->
            userRepository.getUserProfileFlow(uid).flatMapLatest { userProfile ->
                val currentUserId = auth.currentUser?.uid
                if (currentUserId == null) {
                    Timber.w("User signed out while observing profile for tasks.")
                    return@flatMapLatest flowOf(emptyList<TaskItem>())
                }

                val tasksRefPath = if (userProfile?.pairedWith != null) {
                    val partnerUid = userProfile.pairedWith!!
                    val pairingId = listOf(currentUserId, partnerUid).sorted().joinToString("_")
                    "shared_task_lists/$pairingId/tasks"
                } else {
                    "users/$currentUserId/tasks"
                }
                val tasksRef = database.getReference(tasksRefPath)
                Timber.d("Observing tasks at path: $tasksRefPath")

                callbackFlow {
                    val eventListener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val taskList = snapshot.children.mapNotNull { it.getValue(TaskItem::class.java) }
                            Timber.d("Tasks data changed at $tasksRefPath, sending ${taskList.size} items.")
                            trySend(taskList.sortedBy { it.order })
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Timber.e(error.toException(), "Firebase tasks listener cancelled for $tasksRefPath")
                            close(error.toException())
                        }
                    }
                    tasksRef.addValueEventListener(eventListener)
                    awaitClose {
                        Timber.d("getAllTasks flow closing for $tasksRefPath. Removing listener.")
                        tasksRef.removeEventListener(eventListener)
                    }
                }
            }
        } ?: flowOf(emptyList())
    }

    suspend fun addTask(task: TaskItem): Result<Unit> {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Timber.e("Cannot add task: User not logged in.")
            return Result.failure(UserNotLoggedInException())
        }

        val taskWithCreatorInfo = task.copy(createdByUid = currentUser.uid)
        val tasksRef = getTasksRefForCurrentUser()

        if (tasksRef == null) {
            Timber.e("Cannot add task: Tasks reference is null.")
            return Result.failure(UserNotLoggedInException("Tasks reference became null."))
        }

        return suspendCoroutine { continuation ->
            tasksRef.child(taskWithCreatorInfo.id).setValue(taskWithCreatorInfo)
                .addOnSuccessListener {
                    Timber.d("Task added successfully: ${taskWithCreatorInfo.id} to ${tasksRef.path}")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to add task: ${taskWithCreatorInfo.id} to ${tasksRef.path}")
                    continuation.resume(Result.failure(e))
                }
        }
    }

    suspend fun updateTask(task: TaskItem): Result<Unit> {
        val tasksRef = getTasksRefForCurrentUser()
        if (tasksRef == null) {
            Timber.e("Cannot update task: User not logged in or tasks reference is null.")
            return Result.failure(UserNotLoggedInException())
        }
        return suspendCoroutine { continuation ->
            tasksRef.child(task.id).setValue(task)
                .addOnSuccessListener {
                    Timber.d("Task updated successfully: ${task.id} at ${tasksRef.path}")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to update task: ${task.id} at ${tasksRef.path}")
                    continuation.resume(Result.failure(e))
                }
        }
    }

    suspend fun deleteTask(taskId: String): Result<Unit> {
        val tasksRef = getTasksRefForCurrentUser()
        if (tasksRef == null) {
            Timber.e("Cannot delete task: User not logged in or tasks reference is null.")
            return Result.failure(UserNotLoggedInException())
        }
        return suspendCoroutine { continuation ->
            tasksRef.child(taskId).removeValue()
                .addOnSuccessListener {
                    Timber.d("Task deleted successfully: $taskId from ${tasksRef.path}")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to delete task: $taskId from ${tasksRef.path}")
                    continuation.resume(Result.failure(e))
                }
        }
    }

    suspend fun updateTaskOrder(tasksToUpdate: List<TaskItem>): Result<Unit> {
        val tasksRef = getTasksRefForCurrentUser()
        if (tasksRef == null) {
            Timber.e("Cannot update task order: User not logged in or tasks reference is null.")
            return Result.failure(UserNotLoggedInException())
        }
        return suspendCoroutine { continuation ->
            val updates = tasksToUpdate.associate { "/${it.id}/order" to it.order }
            tasksRef.updateChildren(updates)
                .addOnSuccessListener {
                    Timber.d("Tasks order updated successfully.")
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to update tasks order.")
                    continuation.resume(Result.failure(e))
                }
        }
    }
}
