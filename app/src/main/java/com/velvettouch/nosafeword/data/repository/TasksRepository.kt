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

// Custom exception for clarity
class UserNotLoggedInException(message: String = "User not logged in.") : Exception(message)

class TasksRepository {

    // Removed _tasks, tasks Flow, init block, and loadTasks() method

    private val database = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun getTasksRef() = auth.currentUser?.uid?.let { userId -> // Re-added getTasksRef
        database.getReference("users/$userId/tasks")
    }

    suspend fun getAllTasks(): Flow<List<TaskItem>> = callbackFlow {
        var eventListener: ValueEventListener? = null
        var tasksRef = auth.currentUser?.uid?.let { userId ->
            database.getReference("users/$userId/tasks")
        }

        val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            // Clean up old listener if any
            eventListener?.let { tasksRef?.removeEventListener(it) }
            
            tasksRef = firebaseAuth.currentUser?.uid?.let { userId ->
                database.getReference("users/$userId/tasks")
            }

            if (tasksRef != null) {
                eventListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val taskList = snapshot.children.mapNotNull { it.getValue(TaskItem::class.java) }
                        Timber.d("Tasks data changed, sending ${taskList.size} items.")
                        trySend(taskList.sortedBy { it.order })
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Timber.e(error.toException(), "Firebase tasks listener cancelled.")
                        // Optionally send an error or empty list
                        // trySend(emptyList())
                        close(error.toException()) // Close flow on critical error
                    }
                }.also { tasksRef?.addValueEventListener(it) }
            } else {
                Timber.w("User not logged in or UID not available, sending empty task list.")
                trySend(emptyList()) // Send empty list if not logged in
            }
        }

        auth.addAuthStateListener(authStateListener)

        // Initial check in case auth state is already set
        if (tasksRef != null && eventListener == null) {
             eventListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val taskList = snapshot.children.mapNotNull { it.getValue(TaskItem::class.java) }
                    Timber.d("Initial tasks data check, sending ${taskList.size} items.")
                    trySend(taskList.sortedBy { it.order })
                }
                override fun onCancelled(error: DatabaseError) {
                    Timber.e(error.toException(), "Initial Firebase tasks listener cancelled.")
                    close(error.toException())
                }
            }.also { tasksRef?.addValueEventListener(it) }
        } else if (tasksRef == null) {
            Timber.w("Initial check: User not logged in, sending empty task list.")
            trySend(emptyList())
        }

        awaitClose {
            Timber.d("getAllTasks flow closing. Removing listeners.")
            auth.removeAuthStateListener(authStateListener)
            eventListener?.let { tasksRef?.removeEventListener(it) }
        }
    }

    suspend fun addTask(task: TaskItem): Result<Unit> = suspendCoroutine { continuation ->
        val tasksRef = getTasksRef()
        if (tasksRef == null) {
            Timber.e("Cannot add task: User not logged in or tasks reference is null.")
            continuation.resume(Result.failure(UserNotLoggedInException()))
            return@suspendCoroutine
        }
        tasksRef.child(task.id).setValue(task)
            .addOnSuccessListener {
                Timber.d("Task added successfully: ${task.id}")
                continuation.resume(Result.success(Unit))
            }
            .addOnFailureListener { e -> // e is the exception here
                Timber.e(e, "Failed to add task: ${task.id}") // Correct Timber usage
                continuation.resume(Result.failure(e))
            }
    }

    suspend fun updateTask(task: TaskItem): Result<Unit> = suspendCoroutine { continuation ->
        val tasksRef = getTasksRef()
        if (tasksRef == null) {
            Timber.e("Cannot update task: User not logged in or tasks reference is null.")
            continuation.resume(Result.failure(UserNotLoggedInException()))
            return@suspendCoroutine
        }
        tasksRef.child(task.id).setValue(task)
            .addOnSuccessListener {
                Timber.d("Task updated successfully: ${task.id}")
                continuation.resume(Result.success(Unit))
            }
            .addOnFailureListener { e -> // e is the exception here
                Timber.e(e, "Failed to update task: ${task.id}") // Correct Timber usage
                continuation.resume(Result.failure(e))
            }
    }

    suspend fun deleteTask(taskId: String): Result<Unit> = suspendCoroutine { continuation ->
        val tasksRef = getTasksRef()
        if (tasksRef == null) {
            Timber.e("Cannot delete task: User not logged in or tasks reference is null.")
            continuation.resume(Result.failure(UserNotLoggedInException()))
            return@suspendCoroutine
        }
        tasksRef.child(taskId).removeValue()
            .addOnSuccessListener {
                Timber.d("Task deleted successfully: $taskId")
                continuation.resume(Result.success(Unit))
            }
            .addOnFailureListener { e -> // e is the exception here
                Timber.e(e, "Failed to delete task: $taskId") // Correct Timber usage
                continuation.resume(Result.failure(e))
            }
    }

    suspend fun updateTaskOrder(tasksToUpdate: List<TaskItem>): Result<Unit> = suspendCoroutine { continuation ->
        val tasksRef = getTasksRef()
        if (tasksRef == null) {
            Timber.e("Cannot update task order: User not logged in or tasks reference is null.")
            continuation.resume(Result.failure(UserNotLoggedInException()))
            return@suspendCoroutine
        }
        val updates = tasksToUpdate.associate { "/${it.id}/order" to it.order }
        tasksRef.updateChildren(updates)
            .addOnSuccessListener {
                Timber.d("Tasks order updated successfully.")
                continuation.resume(Result.success(Unit))
            }
            .addOnFailureListener { e -> // e is the exception here
                Timber.e(e, "Failed to update tasks order.") // Correct Timber usage
                continuation.resume(Result.failure(e))
            }
    }
}