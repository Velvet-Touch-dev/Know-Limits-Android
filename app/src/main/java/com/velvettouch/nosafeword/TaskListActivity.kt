package com.velvettouch.nosafeword

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val taskItems = mutableListOf<TaskItem>() // In-memory list for now
    private var itemTouchHelper: ItemTouchHelper? = null
    private lateinit var emptyTaskListView: View

    companion object {
        private const val PREFS_NAME = "TaskListPrefs"
        private const val TASKS_KEY = "tasks"
    }

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

        setupRecyclerView()
        setupFab()

        // Load tasks from preferences or sample data if none found
        loadTasks()
        updateEmptyViewVisibility()
    }

    private fun setupRecyclerView() {
        taskListAdapter = TaskListAdapter(
            onTaskChecked = { task, isChecked ->
                // Update UI or data source
                val index = taskItems.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    // Create a new copy of the item with the updated status
                    val updatedTask = taskItems[index].copy(isCompleted = isChecked)
                    taskItems[index] = updatedTask // Update the item in the mutable list
                    // Post the update to the RecyclerView's message queue
                    recyclerView.post {
                        taskListAdapter.submitList(taskItems.toList())
                    }
                }
                saveTasksToPreferences()
                updateEmptyViewVisibility()
            },
            onDeleteClicked = { task ->
                val index = taskItems.indexOfFirst { it.id == task.id }
                if (index != -1) {
                    taskItems.removeAt(index)
                    taskListAdapter.submitList(taskItems.toList()) // Submit a new list
                }
                Toast.makeText(this, "Task '${task.title}' deleted", Toast.LENGTH_SHORT).show()
                saveTasksToPreferences()
                updateEmptyViewVisibility()
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
                if (fromPosition < toPosition) {
                    for (i in fromPosition until toPosition) {
                        Collections.swap(taskItems, i, i + 1)
                    }
                } else {
                    for (i in fromPosition downTo toPosition + 1) {
                        Collections.swap(taskItems, i, i - 1)
                    }
                }
                // Update order property for persistence
                taskItems.forEachIndexed { index, task -> task.order = index }
                
                // Notify the adapter by submitting the new list
                // Posting to ensure it runs after layout pass
                recyclerView.post {
                    taskListAdapter.submitList(taskItems.toList())
                }
                saveTasksToPreferences()
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
                    val newTask = TaskItem(
                        title = title,
                        deadline = selectedDeadlineMillis,
                        order = taskItems.size // New tasks go to the end
                    )
                    taskItems.add(newTask)
                    // Ensure order is updated for all items if needed, though adding at end is simple
                    taskItems.sortBy { it.order } // Re-sort if order logic is complex
                    recyclerView.post { taskListAdapter.submitList(taskItems.toList()) }
                    saveTasksToPreferences()
                    updateEmptyViewVisibility()
                    recyclerView.smoothScrollToPosition(taskItems.indexOf(newTask).takeIf { it != -1} ?: taskItems.size -1)
                } else {
                    Toast.makeText(this, "Task title cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun saveTasksToPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val gson = Gson()
        // Sort by order before saving to maintain it
        val sortedTasks = taskItems.sortedBy { it.order }
        val json = gson.toJson(sortedTasks)
        editor.putString(TASKS_KEY, json)
        editor.apply()
    }

    private fun loadTasks() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val gson = Gson()
        val json = prefs.getString(TASKS_KEY, null)
        val type = object : TypeToken<MutableList<TaskItem>>() {}.type

        if (json != null) {
            val loadedTasks: MutableList<TaskItem>? = gson.fromJson(json, type)
            if (loadedTasks != null) {
                taskItems.clear()
                taskItems.addAll(loadedTasks)
            }
        }

        if (taskItems.isEmpty()) {
            // Load sample tasks if preferences are empty or loading failed
            loadSampleTasks()
        } else {
            // Ensure order is consistent if loaded from prefs
            taskItems.sortBy { it.order }
            taskListAdapter.submitList(taskItems.toList())
        }
        updateEmptyViewVisibility()
    }

    private fun loadSampleTasks() {
        taskItems.clear()
        // No sample tasks will be added
        taskListAdapter.submitList(taskItems.toList())
        // It's important to save the empty list to preferences if we want the list to remain empty on next launch
        // Otherwise, if the app is launched and there are no tasks in prefs, it might call loadSampleTasks again.
        // However, the current logic in loadTasks() calls loadSampleTasks() only if taskItems is empty *after* trying to load from prefs.
        // So, if we save an empty list here, it should correctly reflect an empty state.
        saveTasksToPreferences()
        updateEmptyViewVisibility()
    }

    private fun updateEmptyViewVisibility() {
        if (taskItems.isEmpty()) {
            emptyTaskListView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyTaskListView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
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

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}