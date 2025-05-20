package com.velvettouch.nosafeword

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView // Added for SearchView
import com.google.android.material.tabs.TabLayout // Added for TabLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.velvettouch.nosafeword.databinding.ActivityPlanNightBinding
import com.velvettouch.nosafeword.databinding.DialogAddPlannedItemBinding // Added for dialog binding
import org.json.JSONArray // Added for loading scenes
import java.io.BufferedReader // Added for loading scenes
import java.io.InputStreamReader // Added for loading scenes
import java.util.Locale // Added for search

class PlanNightActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityPlanNightBinding
    private lateinit var plannedItemsAdapter: PlannedNightAdapter
    private val plannedItems = mutableListOf<PlannedItem>() // Replace with actual data source
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var drawerToggle: ActionBarDrawerToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlanNightBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarPlanNight)
        supportActionBar?.setDisplayHomeAsUpEnabled(true) // This enables the <- arrow
        // The title is set in the XML, but can be overridden here if needed
        // supportActionBar?.title = getString(R.string.title_plan_night)

        setupDrawer()
        setupRecyclerView()

        binding.fabAddPlannedItem.setOnClickListener {
            showAddPlannedItemDialog()
        }
    }

    private fun showAddPlannedItemDialog() {
        val dialogBinding = DialogAddPlannedItemBinding.inflate(layoutInflater)
        val dialogView = dialogBinding.root
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        // Set title using the TextView in the custom layout
        dialogBinding.dialogTitle.text = getString(R.string.add_planned_item)


        val masterAllItems = mutableListOf<PlannedItem>() // Renamed to avoid conflict in lambdas
        // Load Scenes
        try {
            val sceneInputStream = assets.open("scenes.json")
            val sceneReader = BufferedReader(InputStreamReader(sceneInputStream))
            val sceneJsonString = sceneReader.readText()
            val sceneJsonArray = JSONArray(sceneJsonString)
            for (i in 0 until sceneJsonArray.length()) {
                val sceneObject = sceneJsonArray.getJSONObject(i)
                masterAllItems.add(PlannedItem(name = sceneObject.getString("title"), type = "Scene", details = sceneObject.getString("content")))
            }
        } catch (e: Exception) {
            e.printStackTrace() // Handle error loading scenes
        }

        // Load Positions (from assets for now, similar to PositionsActivity)
        try {
            val positionAssetManager = assets
            val positionFiles = positionAssetManager.list("positions")
            positionFiles?.forEach { fileName ->
                if (fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true)) {
                    val positionName = fileName.substringBeforeLast(".")
                        .replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                    masterAllItems.add(PlannedItem(name = positionName, type = "Position", details = fileName))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace() // Handle error loading positions
        }
        // TODO: Load custom positions if they are stored elsewhere

        masterAllItems.sortBy { it.name } // Sort all items alphabetically by name

        val searchAdapter = SearchPlannedItemAdapter(masterAllItems.toMutableList()) // Initial full list

        dialogBinding.recyclerViewSearchResults.apply {
            layoutManager = LinearLayoutManager(this@PlanNightActivity)
            adapter = searchAdapter
        }

        fun updateAddButtonText(selectedCount: Int) {
            if (selectedCount > 0) {
                dialogBinding.buttonAddSelectedItem.text = getString(R.string.add) + " ($selectedCount)"
                dialogBinding.buttonAddSelectedItem.isEnabled = true
            } else {
                dialogBinding.buttonAddSelectedItem.text = getString(R.string.add)
                dialogBinding.buttonAddSelectedItem.isEnabled = false
            }
        }
        updateAddButtonText(0) // Initial state

        searchAdapter.onSelectionChanged = { selectedList ->
            updateAddButtonText(selectedList.size)
        }

        var currentFilterType = "Scene" // Default to "Scene" as "All" is removed
        var currentSearchQuery = ""

        fun filterAndDisplayItems() {
            var tempList = masterAllItems.toList() // Start with a copy of the master list
            // Filter by type (No "All" option anymore, so always filter by Scene or Position)
            tempList = tempList.filter { it.type.equals(currentFilterType, ignoreCase = true) }

            // Filter by search query
            if (currentSearchQuery.isNotEmpty()) {
                tempList = tempList.filter {
                    it.name.contains(currentSearchQuery, ignoreCase = true)
                }.toMutableList()
            }
            searchAdapter.updateData(tempList)
        }

        // Select "Scenes" tab by default
        dialogBinding.tabLayoutItemType.getTabAt(0)?.select() // Scenes is now at index 0

        dialogBinding.tabLayoutItemType.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentFilterType = when (tab?.position) {
                    0 -> "Scene" // Index 0 is "Scene"
                    1 -> "Position" // Index 1 is "Position"
                    else -> "Scene" // Default to Scene
                }
                filterAndDisplayItems()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        dialogBinding.searchViewPlannedItem.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                filterAndDisplayItems()
                return true
            }
        })

        val alertDialog = builder.create()

        dialogBinding.buttonCancelAddItem.setOnClickListener {
            alertDialog.dismiss()
        }

        dialogBinding.buttonAddSelectedItem.setOnClickListener {
            val itemsToAdd = searchAdapter.getSelectedItems()
            if (itemsToAdd.isNotEmpty()) {
                val startPosition = plannedItems.size
                itemsToAdd.forEach { item ->
                    if (!plannedItems.any { pi -> pi.name == item.name && pi.type == item.type }) {
                        plannedItems.add(PlannedItem(
                            name = item.name,
                            type = item.type,
                            details = item.details,
                            order = plannedItems.size // This will be the new index
                        ))
                    }
                }
                // Re-calculate order for all items after adding
                plannedItems.forEachIndexed { index, item -> item.order = index }
                plannedItems.sortBy { it.order } // Ensure list is sorted by order for adapter

                if (plannedItems.size > startPosition) { // If new items were actually added
                    plannedItemsAdapter.notifyItemRangeInserted(startPosition, plannedItems.size - startPosition)
                    binding.recyclerViewPlannedItems.scrollToPosition(plannedItems.size - 1)
                }
            }
            alertDialog.dismiss()
        }
        filterAndDisplayItems() // Initial display
        alertDialog.show()
    }

    private fun setupRecyclerView() {
        plannedItemsAdapter = PlannedNightAdapter(
            plannedItems,
            onItemClick = { item ->
                // Handle item click if needed, e.g., open details
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            },
            onItemDismiss = { position ->
                plannedItemsAdapter.removeItem(position)
                // TODO: Add Snackbar with Undo option
            }
        )

        binding.recyclerViewPlannedItems.apply {
            layoutManager = LinearLayoutManager(this@PlanNightActivity)
            adapter = plannedItemsAdapter
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, // Drag directions
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT // Swipe directions
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return plannedItemsAdapter.onItemMove(
                    viewHolder.adapterPosition,
                    target.adapterPosition
                )
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                plannedItemsAdapter.removeItem(viewHolder.adapterPosition)
                // TODO: Add Snackbar with Undo option for swipe
            }

            // Optional: Customize appearance during drag/swipe
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.7f // Example: make item semi-transparent
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f // Restore full opacity
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewPlannedItems)
    }

    private fun setupDrawer() {
        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayoutPlanNight,
            binding.toolbarPlanNight, // Pass the toolbar here
            R.string.drawer_open,
            R.string.drawer_close
        )
        binding.drawerLayoutPlanNight.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        binding.navViewPlanNight.setNavigationItemSelectedListener(this)
        binding.navViewPlanNight.setCheckedItem(R.id.nav_plan_night) // Set "Plan your Night" as checked
        // Remove header view if it exists and is not needed (like in MainActivity)
        // binding.navViewPlanNight.removeHeaderView(binding.navViewPlanNight.getHeaderView(0))
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_scenes -> {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            R.id.nav_positions -> {
                startActivity(Intent(this, PositionsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            R.id.nav_body_worship -> {
                startActivity(Intent(this, BodyWorshipActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            R.id.nav_task_list -> {
                startActivity(Intent(this, TaskListActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            R.id.nav_plan_night -> {
                // Already here, just close drawer
            }
            R.id.nav_favorites -> {
                 startActivity(Intent(this, FavoritesActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
        }
        binding.drawerLayoutPlanNight.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onSupportNavigateUp(): Boolean {
        // If drawer is open, close it
        if (binding.drawerLayoutPlanNight.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayoutPlanNight.closeDrawer(GravityCompat.START)
            return true
        }
        // If drawer is closed and ActionBarDrawerToggle wants to handle "up" (e.g. it's showing a back arrow)
        // This part is implicitly handled by drawerToggle.onOptionsItemSelected in onOptionsItemSelected.
        // If it's a simple "up" navigation not handled by the toggle, then:
        onBackPressedDispatcher.onBackPressed() // Standard behavior for "up" is often like "back"
        return true
        // Alternatively, return super.onSupportNavigateUp() if you want default framework handling.
    }

    // Removed getHomeMenuItem() as it's no longer needed with the simplified onSupportNavigateUp

    override fun onBackPressed() {
        if (binding.drawerLayoutPlanNight.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayoutPlanNight.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}