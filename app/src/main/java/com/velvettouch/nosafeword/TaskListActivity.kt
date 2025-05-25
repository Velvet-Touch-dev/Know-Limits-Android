package com.velvettouch.nosafeword

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
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
// import androidx.swiperefreshlayout.widget.SwipeRefreshLayout // Ensured import is clean
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.velvettouch.nosafeword.data.model.RuleItem
import com.velvettouch.nosafeword.data.model.TaskItem // Updated import
import com.velvettouch.nosafeword.data.repository.TasksRepository
import com.velvettouch.nosafeword.data.repository.UserRepository // Added
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
    private var taskItemTouchHelper: ItemTouchHelper? = null // Renamed for clarity
    private lateinit var emptyTaskListView: View
    private lateinit var taskListProgressIndicator: LinearProgressIndicator

    // Rules UI Elements
    private lateinit var rulesCardView: com.google.android.material.card.MaterialCardView // Added CardView
    private lateinit var rulesHeaderLayout: RelativeLayout
    private lateinit var rulesRecyclerView: RecyclerView
    private lateinit var rulesAdapter: RulesAdapter
    private lateinit var rulesEditModeIcon: ImageView // Changed from rulesExpandIcon
    private lateinit var addRuleButtonHeader: ImageButton
    private lateinit var emptyRulesListView: View
    private var rulesItemTouchHelper: ItemTouchHelper? = null
    private var isRulesInEditMode = false // Changed from isRulesSectionExpanded

    // ViewModel initialization
    private val tasksViewModel: TasksViewModel by viewModels {
        // In a real app, use Hilt or another DI framework to provide the repository
        val userRepository = UserRepository() // Instantiate UserRepository
        TasksViewModelFactory(userRepository) // Pass UserRepository to the factory
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
        taskListProgressIndicator = findViewById(R.id.task_list_linear_progress_indicator)

        // Initialize Rules UI
        rulesCardView = findViewById(R.id.rules_card_view) // Added CardView
        rulesHeaderLayout = findViewById(R.id.rules_header_layout)
        rulesRecyclerView = findViewById(R.id.rules_recycler_view)
        rulesEditModeIcon = findViewById(R.id.rules_edit_mode_icon) // Changed from rulesExpandIcon
        addRuleButtonHeader = findViewById(R.id.add_rule_button_header)
        emptyRulesListView = findViewById(R.id.empty_rules_list_view)

        // Rules RecyclerView is always visible, no more expand/collapse for the list itself
        rulesRecyclerView.visibility = View.VISIBLE

        setupRecyclerView()
        setupRulesRecyclerView() // New setup for rules
        setupFab()
        setupRulesHeader() // New setup for rules header actions
        observeViewModel()

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
                val currentUserRole = tasksViewModel.currentUserProfile.value?.role
                val currentUserId = tasksViewModel.currentUserId

                if (currentUserId == null || currentUserRole == null) {
                    Toast.makeText(this, "Cannot determine user role. Please try again.", Toast.LENGTH_SHORT).show()
                    return@TaskListAdapter
                }

                val canDelete = if (currentUserRole.equals("Dom", ignoreCase = true)) {
                    true // Dom can delete any task
                } else if (currentUserRole.equals("Sub", ignoreCase = true)) {
                    task.createdByUid == currentUserId // Sub can only delete their own tasks
                } else {
                    false // Unknown role cannot delete
                }

                if (canDelete) {
                    tasksViewModel.deleteTask(task.id)
                    // Toast.makeText(this, "Task '${task.title}' deleted", Toast.LENGTH_SHORT).show() // ViewModel might show its own toast or handle UI update
                } else {
                    if (currentUserRole.equals("Sub", ignoreCase = true)) {
                        Toast.makeText(this, "Subs can only delete tasks they created.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "You do not have permission to delete this task.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDragStarted = { viewHolder ->
                taskItemTouchHelper?.startDrag(viewHolder)
            }
        )
        recyclerView.adapter = taskListAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        val taskCallback = object : ItemTouchHelper.SimpleCallback(
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
                // Create a new list with the moved item
                val newList = taskListAdapter.currentList.toMutableList().apply {
                    val item = removeAt(fromPosition)
                    add(toPosition, item)
                }
                // Submit the new list to the adapter. This updates currentList.
                // DiffUtil will handle animations. notifyItemMoved is not strictly needed here
                // but can be kept if it provides a smoother immediate visual cue before submitList processes.
                // For simplicity and to rely on DiffUtil, we can remove notifyItemMoved if submitList is efficient.
                // taskListAdapter.notifyItemMoved(fromPosition, toPosition)
                taskListAdapter.submitList(newList)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used for now
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                tasksViewModel.updateTaskOrder(taskListAdapter.currentList.toList())
            }
        }
        taskItemTouchHelper = ItemTouchHelper(taskCallback)
        taskItemTouchHelper?.attachToRecyclerView(recyclerView)
    }

    private fun setupRulesRecyclerView() {
        rulesAdapter = RulesAdapter(
            onDeleteClicked = { rule ->
                // The ViewHolder's click listener already ensures isUserDom() and isRulesInEditMode.
                // So, we can directly call the ViewModel method.
                tasksViewModel.deleteRule(rule.id)
            },
            onDragStarted = { viewHolder ->
                rulesItemTouchHelper?.startDrag(viewHolder)
            },
            onRuleClicked = { rule ->
                // Click on item to edit is only possible if isUserDom() and isRulesInEditMode
                if (isUserDom() && isRulesInEditMode) {
                    showAddEditRuleDialog(rule)
                }
                // Otherwise, click does nothing on the item itself for now
            },
            isUserDomProvider = { isUserDom() } // Provide lambda to check Dom status
        )
        rulesRecyclerView.adapter = rulesAdapter
        rulesRecyclerView.layoutManager = LinearLayoutManager(this)
        // Prevent nested scrolling issues if the card view itself is in a scrollable container
        // rulesRecyclerView.isNestedScrollingEnabled = false; // Already set in XML by tools, but good to be aware

        val rulesCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                // Disable drag unless user is Dom and in edit mode
                return if (isUserDom() && isRulesInEditMode) {
                    super.getMovementFlags(recyclerView, viewHolder)
                } else {
                    makeMovementFlags(0, 0) // No drag, no swipe
                }
            }
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (!isUserDom()) return false // Only Dom can reorder

                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                val currentList = rulesAdapter.currentList.toMutableList()
                if (fromPosition < toPosition) {
                    for (i in fromPosition until toPosition) {
                        Collections.swap(currentList, i, i + 1)
                    }
                } else {
                    for (i in fromPosition downTo toPosition + 1) {
                        Collections.swap(currentList, i, i - 1)
                    }
                }
                rulesAdapter.submitList(currentList)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (isUserDom()) {
                    tasksViewModel.updateRuleOrder(rulesAdapter.currentList.toList())
                }
            }

            override fun isLongPressDragEnabled(): Boolean {
                // Drag is initiated by onTouchListener in ViewHolder, which already checks Dom and EditMode
                return false // Let the touch listener handle it
            }
        }
        rulesItemTouchHelper = ItemTouchHelper(rulesCallback)
        rulesItemTouchHelper?.attachToRecyclerView(rulesRecyclerView)
    }

    private fun setupFab() {
        fabAddTask.setOnClickListener {
            showAddTaskDialog()
        }
    }

    private fun setupRulesHeader() {
        // rulesHeaderLayout.setOnClickListener removed - click on whole header no longer toggles edit mode

        rulesEditModeIcon.setOnClickListener { // Click listener moved to the icon itself
            if (isUserDom()) {
                toggleRulesEditMode()
            }
            // For Sub, this icon is GONE, so no click possible
        }

        addRuleButtonHeader.setOnClickListener {
            // This button is only visible if Dom and in Edit Mode
            showAddEditRuleDialog(null)
        }
        updateRulesHeaderIcons() // Initial setup of icons
    }

    private fun toggleRulesEditMode() {
        if (!isUserDom()) return // Should not happen if UI controls are correctly hidden

        isRulesInEditMode = !isRulesInEditMode
        rulesAdapter.setEditMode(isRulesInEditMode) // This will trigger notifyItemRangeChanged with payload
        updateRulesHeaderIcons()
        updateEmptyRuleViewVisibility(rulesAdapter.currentList.isEmpty()) // Refresh empty view based on new mode
    }

    private fun updateRulesHeaderIcons() {
        if (isUserDom()) {
            rulesEditModeIcon.visibility = View.VISIBLE
            rulesEditModeIcon.setImageResource(if (isRulesInEditMode) R.drawable.ic_check_24 else R.drawable.ic_edit) // Need ic_check_24
            addRuleButtonHeader.visibility = if (isRulesInEditMode) View.VISIBLE else View.GONE
        } else {
            rulesEditModeIcon.visibility = View.GONE
            addRuleButtonHeader.visibility = View.GONE
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

    private fun showAddEditRuleDialog(ruleToEdit: RuleItem?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_edit_rule, null)
        val descriptionEditText = dialogView.findViewById<TextInputEditText>(R.id.rule_description_edittext)

        ruleToEdit?.let {
            descriptionEditText.setText(it.description)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (ruleToEdit == null) "Add New Rule" else "Edit Rule")
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val description = descriptionEditText.text.toString().trim()
                if (description.isNotEmpty()) {
                    if (ruleToEdit == null) {
                        tasksViewModel.addRule(description)
                    } else {
                        tasksViewModel.updateRule(ruleToEdit.copy(description = description))
                    }
                } else {
                    Toast.makeText(this, "Rule description cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            tasksViewModel.tasks.collect { tasks ->
                taskListAdapter.submitList(tasks)
                updateEmptyTaskViewVisibility(tasks.isEmpty())
            }
        }

        lifecycleScope.launch {
            tasksViewModel.rules.collect { rules ->
                rulesAdapter.submitList(rules) {
                    // After list is submitted, re-evaluate empty view visibility
                    updateEmptyRuleViewVisibility(rules.isEmpty())
                }
            }
        }

        lifecycleScope.launch {
            tasksViewModel.isLoading.collect { isLoading ->
                taskListProgressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            tasksViewModel.error.collect { errorMessage ->
                errorMessage?.let {
                    Toast.makeText(this@TaskListActivity, it, Toast.LENGTH_LONG).show()
                    tasksViewModel.clearError()
                }
            }
        }

        // Observe user profile to update UI based on role (e.g., show/hide add rule button)
        lifecycleScope.launch {
            tasksViewModel.currentUserProfile.collect { userProfile ->
                // This will trigger when the profile loads or changes.
                val wasDom = isUserDom() // Capture state before profile update if needed for complex logic
                // Re-evaluate visibility of Dom-only controls and edit mode status
                if (!isUserDom() && isRulesInEditMode) {
                    // If user role changed from Dom to Sub while in edit mode, exit edit mode
                    isRulesInEditMode = false
                    rulesAdapter.setEditMode(false)
                }
                updateRulesHeaderIcons()
                updateEmptyRuleViewVisibility(rulesAdapter.currentList.isEmpty())
                // If adapter needs full refresh due to role change affecting item views:
                // rulesAdapter.notifyDataSetChanged() // Or more specific updates
            }
        }
    }

    private fun updateEmptyTaskViewVisibility(isEmpty: Boolean) {
        emptyTaskListView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateEmptyRuleViewVisibility(isEmpty: Boolean) {
        val isDom = isUserDom()
        val emptyRulesTextView = emptyRulesListView.findViewById<TextView>(R.id.empty_rules_text_view)

        if (isEmpty) {
            rulesRecyclerView.visibility = View.GONE
            emptyRulesListView.visibility = View.VISIBLE
            if (isDom) {
                emptyRulesTextView?.text = "No rules defined yet. Tap '+' in header to add."
            } else {
                emptyRulesTextView?.text = "Your dom hasn’t set the rules...yet"
            }
        } else {
            rulesRecyclerView.visibility = View.VISIBLE
            emptyRulesListView.visibility = View.GONE
        }
        // Visibility of add/edit icons in header is handled by updateRulesHeaderIcons()
    }

    private fun isUserDom(): Boolean {
        return tasksViewModel.currentUserProfile.value?.role.equals("Dom", ignoreCase = true)
    }


    // private fun setupSwipeRefresh() {
    //     swipeRefreshLayout.setOnRefreshListener {
    //         tasksViewModel.loadTasks() // Tell ViewModel to reload tasks
    //         // The isRefreshing state will be managed by observing tasksViewModel.isLoading
    //     }
    //     // Optional: Configure the colors of the refresh indicator (ensure this line is fully commented or valid if used)
    //     // swipeRefreshLayout.setColorSchemeResources(R.color.primary, R.color.secondary) // Example if you have these colors
    // }

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
