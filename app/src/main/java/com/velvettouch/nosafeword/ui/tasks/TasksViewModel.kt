package com.velvettouch.nosafeword.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velvettouch.nosafeword.data.model.TaskItem
import com.velvettouch.nosafeword.data.repository.TasksRepository
import com.velvettouch.nosafeword.data.repository.UserNotLoggedInException // Import the custom exception
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

class TasksViewModel(private val repository: TasksRepository) : ViewModel() {

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null // Clear previous errors
            repository.getAllTasks()
                .catch { e ->
                    Timber.e(e, "Error loading tasks")
                    _error.value = "Failed to load tasks: ${e.localizedMessage}"
                    _tasks.value = emptyList() // Clear tasks on error or show cached if available
                    _isLoading.value = false
                }
                .collect { taskList ->
                    _tasks.value = taskList
                    _isLoading.value = false
                    Timber.d("Tasks loaded in ViewModel: ${taskList.size}")
                }
        }
    }

    fun addTask(title: String, deadline: Long? = null) {
        if (title.isBlank()) {
            _error.value = "Task title cannot be empty."
            return
        }
        viewModelScope.launch {
            val newTask = TaskItem(title = title, deadline = deadline, order = _tasks.value.size)
            val result = repository.addTask(newTask)
            result.fold(
                onSuccess = {
                    Timber.d("Task add request successful for: $title")
                    // Data should refresh via the getAllTasks flow listener
                },
                onFailure = { e ->
                    Timber.e(e, "Error adding task")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to add tasks."
                    } else {
                        _error.value = "Failed to add task: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    fun updateTask(task: TaskItem) {
        viewModelScope.launch {
            val result = repository.updateTask(task)
            result.fold(
                onSuccess = {
                    Timber.d("Task update request successful for: ${task.id}")
                },
                onFailure = { e ->
                    Timber.e(e, "Error updating task")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to update tasks."
                    } else {
                        _error.value = "Failed to update task: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    fun toggleTaskCompletion(task: TaskItem) {
        val updatedTask = task.copy(isCompleted = !task.isCompleted)
        updateTask(updatedTask)
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            val result = repository.deleteTask(taskId)
            result.fold(
                onSuccess = {
                    Timber.d("Task delete request successful for: $taskId")
                },
                onFailure = { e ->
                    Timber.e(e, "Error deleting task")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to delete tasks."
                    } else {
                        _error.value = "Failed to delete task: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    fun updateTaskOrder(orderedTasks: List<TaskItem>) {
        viewModelScope.launch {
            val tasksWithNewOrder = orderedTasks.mapIndexed { index, taskItem ->
                taskItem.copy(order = index)
            }
            val result = repository.updateTaskOrder(tasksWithNewOrder)
            result.fold(
                onSuccess = {
                    Timber.d("Task order update request successful.")
                    // Optimistically update local state or rely on Firebase listener
                    // _tasks.value = tasksWithNewOrder // Example of optimistic update
                },
                onFailure = { e ->
                    Timber.e(e, "Error updating task order")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to update task order."
                    } else {
                        _error.value = "Failed to update task order: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    fun clearError() {
        _error.value = null
    }
}

// ViewModel Factory to inject the repository
class TasksViewModelFactory(private val repository: TasksRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TasksViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TasksViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}