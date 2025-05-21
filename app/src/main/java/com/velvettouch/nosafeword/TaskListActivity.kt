package com.velvettouch.nosafeword

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout // Ensured import is clean
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.velvettouch.nosafeword.data.model.TaskItem // Updated import
import com.velvettouch.nosafeword.data.repository.TasksRepository
import com.velvettouch.nosafeword.ui.tasks.TasksViewModel
import com.velvettouch.nosafeword.ui.tasks.TasksViewModelFactory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TaskListActivity : BaseActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var drawerToggle: ActionBarDrawerToggle

    private lateinit var recyclerView: RecyclerView
    private lateinit var taskListAdapter: TaskListAdapter
    private lateinit var fabAddTask: FloatingActionButton
    // private val taskItems = mutableListOf<TaskItem>() // Removed: ViewModel will manage this
    private var itemTouchHelper: ItemTouchHelper? = null
    private lateinit var emptyTaskListView: View
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout // Added SwipeRefreshLayout

    // ViewModel initialization
    private val tasksViewModel: TasksViewModel by viewModels {
        // In a real app, use Hilt or another DI framework to provide the repository
        TasksViewModelFactory(TasksRepository())
    }

    // Removed PREFS_NAME and TASKS_KEY companion object

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_list)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Task List"

        drawerLayout = findViewById(R.id.drawer_layout_task_list)
        navigationView = findViewById(R.id.nav_view_task_list)

        drawerToggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        navigationView.setNavigationItemSelectedListener { menuItem ->
            handleNavigationItemSelected(menuItem)
            true
        }
        navigationView.setCheckedItem(R.id.nav_task_list)
        if (navigationView.headerCount > 0) {
            navigationView.removeHeaderView(navigationView.getHeaderView(0))
        }

        recyclerView = findViewById(R.id.task_list_recycler_view)
        fabAddTask = findViewById(R.id.fab_add_task)
        emptyTaskListView = findViewById(R.id.empty_task_list_view)
        progressBar = findViewById(R.id.progress_bar_task_list)
        swipeRefreshLayout = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh_layout_tasks) // Explicit type for findViewById

        setupRecyclerView()
        setupFab()
        setupSwipeRefresh() // Call new setup method
        observeViewModel()

        // loadTasks() and updateEmptyViewVisibility() will be handled by ViewModel observation

        // Setup custom back press handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupRecyclerView() {
        taskListAdapter = TaskListAdapter(
            onTaskChecked = { task, _ -> // isChecked is part of task now
                tasksViewModel.toggleTaskCompletion(task)
            },
            onDeleteClicked = { task ->
                tasksViewModel.deleteTask(task.id)
                Toast.makeText(this, "Task '${task.title}' deleted", Toast.LENGTH_SHORT).show()
                // saveTasksToPreferences() and updateEmptyViewVisibility() handled by ViewModel
            },
            onDragStarted = { viewHolder ->
                itemTouchHelper?.startDrag(viewHolder)
            }
        )
        recyclerView.adapter = taskListAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, // Drag directions
            0 // Swipe directions (none for now)
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                // Directly update the list and submit it
                val currentList = taskListAdapter.currentList.toMutableList()
                if (fromPosition < toPosition) {
                    for (i in fromPosition until toPosition) {
                        Collections.swap(currentList, i, i + 1)
                    }
                } else {
                    for (i in fromPosition downTo toPosition + 1) {
                        Collections.swap(currentList, i, i - 1)
                    }
                }
                // Update order property for persistence by notifying ViewModel
                // The adapter will be updated when the ViewModel's flow emits the new list
                taskListAdapter.notifyItemMoved(fromPosition, toPosition) // For immediate visual feedback
                tasksViewModel.updateTaskOrder(currentList)
                // saveTasksToPreferences() handled by ViewModel
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used for now
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper?.attachToRecyclerView(recyclerView)
    }

    private fun setupFab() {
        fabAddTask.setOnClickListener {
            showAddTaskDialog()
        }
    }

    private fun showAddTaskDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_task, null)
        val titleEditText = dialogView.findViewById<TextInputEditText>(R.id.task_title_edittext)
        val selectDeadlineButton = dialogView.findViewById<Button>(R.id.select_deadline_button)
        val selectedDeadlineTextView = dialogView.findViewById<TextView>(R.id.selected_deadline_textview)
        val clearDeadlineButton = dialogView.findViewById<ImageButton>(R.id.clear_deadline_button)

        var selectedDeadlineMillis: Long? = null
        val sdf = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())

        fun updateDeadlineDisplay() {
            if (selectedDeadlineMillis != null) {
                selectedDeadlineTextView.text = "Deadline: ${sdf.format(Date(selectedDeadlineMillis!!))}"
                selectedDeadlineTextView.visibility = View.VISIBLE
                clearDeadlineButton.visibility = View.VISIBLE
            } else {
                selectedDeadlineTextView.visibility = View.GONE
                clearDeadlineButton.visibility = View.GONE
            }
        }

        selectDeadlineButton.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Select deadline date")
                .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
                .build()

            datePicker.addOnPositiveButtonClickListener { dateSelection ->
                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                calendar.timeInMillis = dateSelection

                val timePicker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_12H)
                    .setHour(calendar.get(Calendar.HOUR_OF_DAY))
                    .setMinute(calendar.get(Calendar.MINUTE))
                    .setTitleText("Select deadline time")
                    .build()

                timePicker.addOnPositiveButtonClickListener {
                    calendar.set(Calendar.HOUR_OF_DAY, timePicker.hour)
                    calendar.set(Calendar.MINUTE, timePicker.minute)
                    selectedDeadlineMillis = calendar.timeInMillis
                    updateDeadlineDisplay()
                }
                timePicker.show(supportFragmentManager, "MaterialTimePicker")
            }
            datePicker.show(supportFragmentManager, "MaterialDatePicker")
        }

        clearDeadlineButton.setOnClickListener {
            selectedDeadlineMillis = null
            updateDeadlineDisplay()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_new_task))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val title = titleEditText.text.toString().trim()
                if (title.isNotEmpty()) {
                    tasksViewModel.addTask(title, selectedDeadlineMillis)
                    // UI updates (list, empty view, scroll) will be handled by ViewModel observation
                } else {
                    Toast.makeText(this, "Task title cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // Removed saveTasksToPreferences(), loadTasks(), loadSampleTasks()
    // updateEmptyViewVisibility() will be called from ViewModel observer

    private fun observeViewModel() {
        lifecycleScope.launch {
            tasksViewModel.tasks.collect { tasks ->
                taskListAdapter.submitList(tasks)
                updateEmptyViewVisibility(tasks.isEmpty())
                // If a new task was added, we might want to scroll to it.
                // This requires more complex logic to detect new item vs. general update.
                // For now, manual scroll or no scroll on general updates.
            }
        }

        lifecycleScope.launch {
            tasksViewModel.isLoading.collect { isLoading ->
                // Control both ProgressBar and SwipeRefreshLayout's refreshing state
                if (!swipeRefreshLayout.isRefreshing && isLoading) { // Show progress bar only if not already swipe-refreshing
                    progressBar.visibility = View.VISIBLE
                } else if (!isLoading) {
                    progressBar.visibility = View.GONE
                }
                swipeRefreshLayout.isRefreshing = isLoading // ViewModel drives SwipeRefreshLayout's animation
            }
        }

        lifecycleScope.launch {
            tasksViewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@TaskListActivity, it, Toast.LENGTH_LONG).show()
                    tasksViewModel.clearError() // Clear error after showing
                }
            }
        }
    }

    private fun updateEmptyViewVisibility(isEmpty: Boolean) {
        if (isEmpty) {
            emptyTaskListView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyTaskListView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            tasksViewModel.loadTasks() // Tell ViewModel to reload tasks
            // The isRefreshing state will be managed by observing tasksViewModel.isLoading
        }
        // Optional: Configure the colors of the refresh indicator (ensure this line is fully commented or valid if used)
        // swipeRefreshLayout.setColorSchemeResources(R.color.primary, R.color.secondary) // Example if you have these colors
    }

    private fun handleNavigationItemSelected(menuItem: MenuItem) {
        drawerLayout.closeDrawer(GravityCompat.START)
        when (menuItem.itemId) {
            R.id.nav_scenes -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
                finish()
            }
            R.id.nav_positions -> {
                val intent = Intent(this, PositionsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
                finish()
            }
            R.id.nav_body_worship -> {
                val intent = Intent(this, BodyWorshipActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
                finish()
            }
            R.id.nav_task_list -> {
                // Already on this screen
            }
            R.id.nav_plan_night -> {
                val intent = Intent(this, PlanNightActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP // Or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish() // Finish TaskListActivity
            }
            R.id.nav_favorites -> {
                 val intent = Intent(this, FavoritesActivity::class.java)
                 intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                 startActivity(intent)
                 finish()
            }
            R.id.nav_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
                // finish() // Typically don't finish when going to settings
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Removed deprecated override fun onBackPressed()
}