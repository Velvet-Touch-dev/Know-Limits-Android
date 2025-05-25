package com.velvettouch.nosafeword.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.velvettouch.nosafeword.data.model.RuleItem // Added for rules
import com.velvettouch.nosafeword.data.model.TaskItem
import com.velvettouch.nosafeword.data.repository.RulesRepository // Added for rules
import com.velvettouch.nosafeword.data.repository.TasksRepository
import com.velvettouch.nosafeword.data.repository.UserNotLoggedInException // Import the custom exception
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow // Keep only one import for StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.SharingStarted
// Removed redundant StateFlow import here
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.Flow // Added import for Flow
import kotlinx.coroutines.launch
import timber.log.Timber
import com.velvettouch.nosafeword.data.model.UserProfile // Added for user profile
import com.velvettouch.nosafeword.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth // Added for current user UID

class TasksViewModel(
    private val tasksRepository: TasksRepository,
    private val rulesRepository: RulesRepository, // Added RulesRepository
    private val userRepository: UserRepository // Added UserRepository
) : ViewModel() {

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private val _rules = MutableStateFlow<List<RuleItem>>(emptyList()) // Added for rules
    val rules: StateFlow<List<RuleItem>> = _rules.asStateFlow() // Added for rules

    // Separate loading states
    private val _isLoadingTasks = MutableStateFlow(false)
    private val _isLoadingRules = MutableStateFlow(false)

    // Combined loading state for the UI
    val isLoading: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(_isLoadingTasks, _isLoadingRules) { tasksLoading, rulesLoading ->
        tasksLoading || rulesLoading
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)


    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val currentUserProfile: StateFlow<UserProfile?> = userRepository.getCurrentUserProfileFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null) // Changed to Eagerly
    
    // Expose current user's UID for convenience
    val currentUserId: String?
        get() = FirebaseAuth.getInstance().currentUser?.uid

    init {
        loadTasks()
        loadRules() // Added call to load rules
    }

    fun loadTasks() {
        viewModelScope.launch {
            _isLoadingTasks.value = true
            _error.value = null // Clear previous errors for this specific load if needed or handle globally
            tasksRepository.getAllTasks()
                .catch { e ->
                    Timber.e(e, "Error loading tasks")
                    _error.value = "Failed to load tasks: ${e.localizedMessage}"
                    _tasks.value = emptyList()
                    _isLoadingTasks.value = false
                }
                .collect { taskList ->
                    _tasks.value = taskList
                    _isLoadingTasks.value = false
                    Timber.d("Tasks loaded in ViewModel: ${taskList.size}")
                }
        }
    }

    fun loadRules() { // Added method to load rules
        viewModelScope.launch {
            _isLoadingRules.value = true
            _error.value = null // Clear previous errors for this specific load
            rulesRepository.getAllRules()
                .catch { e ->
                    Timber.e(e, "Error loading rules")
                    _error.value = "Failed to load rules: ${e.localizedMessage}"
                    _rules.value = emptyList()
                    _isLoadingRules.value = false
                }
                .collect { ruleList ->
                    _rules.value = ruleList
                    _isLoadingRules.value = false
                    Timber.d("Rules loaded in ViewModel: ${ruleList.size}")
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
            // createdByUid will be set by TasksRepository using FirebaseAuth.getInstance().currentUser.uid
            val result = tasksRepository.addTask(newTask)
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
            // Ensure completedByUid is set if task is completed by current user
            val taskToUpdate = if (task.isCompleted && task.completedByUid == null && currentUserId != null) {
                task.copy(completedByUid = currentUserId)
            } else if (!task.isCompleted && task.completedByUid != null) {
                // Clear completedByUid if task is marked incomplete
                task.copy(completedByUid = null)
            }
            else {
                task
            }
            val result = tasksRepository.updateTask(taskToUpdate)
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
            val result = tasksRepository.deleteTask(taskId)
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
            val result = tasksRepository.updateTaskOrder(tasksWithNewOrder)
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

    // --- Rules Management ---
    fun addRule(description: String) {
        if (description.isBlank()) {
            _error.value = "Rule description cannot be empty."
            return
        }
        viewModelScope.launch {
            val newRule = RuleItem(description = description, order = _rules.value.size)
            // createdByUid will be set by RulesRepository
            val result = rulesRepository.addRule(newRule)
            result.fold(
                onSuccess = {
                    Timber.d("Rule add request successful for: $description")
                },
                onFailure = { e ->
                    Timber.e(e, "Error adding rule")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to add rules."
                    } else {
                        _error.value = "Failed to add rule: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    fun updateRule(rule: RuleItem) {
        viewModelScope.launch {
            val result = rulesRepository.updateRule(rule)
            result.fold(
                onSuccess = {
                    Timber.d("Rule update request successful for: ${rule.id}")
                },
                onFailure = { e ->
                    Timber.e(e, "Error updating rule")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to update rules."
                    } else {
                        _error.value = "Failed to update rule: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            val result = rulesRepository.deleteRule(ruleId)
            result.fold(
                onSuccess = {
                    Timber.d("Rule delete request successful for: $ruleId")
                },
                onFailure = { e ->
                    Timber.e(e, "Error deleting rule")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to delete rules."
                    } else {
                        _error.value = "Failed to delete rule: ${e.localizedMessage}"
                    }
                }
            )
        }
    }

    fun updateRuleOrder(orderedRules: List<RuleItem>) {
        viewModelScope.launch {
            val rulesWithNewOrder = orderedRules.mapIndexed { index, ruleItem ->
                ruleItem.copy(order = index)
            }
            val result = rulesRepository.updateRuleOrder(rulesWithNewOrder)
            result.fold(
                onSuccess = {
                    Timber.d("Rule order update request successful.")
                },
                onFailure = { e ->
                    Timber.e(e, "Error updating rule order")
                    if (e is UserNotLoggedInException) {
                        _error.value = "You must be logged in to update rule order."
                    } else {
                        _error.value = "Failed to update rule order: ${e.localizedMessage}"
                    }
                }
            )
        }
    }
}

// ViewModel Factory to inject the repository
class TasksViewModelFactory(
    private val userRepository: UserRepository // Keep this, as both repositories might need it or be constructed here
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TasksViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Instantiate TasksRepository and RulesRepository here
            val tasksRepo = TasksRepository(userRepository)
            val rulesRepo = RulesRepository(userRepository) // RulesRepository also needs UserRepository
            return TasksViewModel(tasksRepo, rulesRepo, userRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// Helper extension in UserRepository to get a flow of the current user's profile
fun UserRepository.getCurrentUserProfileFlow(): Flow<UserProfile?> {
    return FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
        this.getUserProfileFlow(uid)
    } ?: kotlinx.coroutines.flow.flowOf(null) // Emit null if no user logged in
}
