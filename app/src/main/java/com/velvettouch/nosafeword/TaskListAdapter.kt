package com.velvettouch.nosafeword

import com.velvettouch.nosafeword.data.model.TaskItem // Added import
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskListAdapter(
    private val onTaskChecked: (TaskItem, Boolean) -> Unit,
    private val onDeleteClicked: (TaskItem) -> Unit,
    private val onDragStarted: (RecyclerView.ViewHolder) -> Unit // To initiate drag
) : ListAdapter<TaskItem, TaskListAdapter.TaskViewHolder>(TaskDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = getItem(position)
        holder.bind(task, onTaskChecked, onDeleteClicked, onDragStarted)
    }

    class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.task_title_textview)
        private val deadlineTextView: TextView = itemView.findViewById(R.id.task_deadline_textview)
        private val checkBox: CheckBox = itemView.findViewById(R.id.task_checkbox)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_task_button)
        private val dragHandle: ImageView = itemView.findViewById(R.id.drag_handle_icon)

        fun bind(
            task: TaskItem,
            onTaskChecked: (TaskItem, Boolean) -> Unit,
            onDeleteClicked: (TaskItem) -> Unit,
            onDragStarted: (RecyclerView.ViewHolder) -> Unit
        ) {
            titleTextView.text = task.title

            // Temporarily remove listener to prevent firing during programmatic update
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = task.isCompleted

            if (task.isCompleted) {
                titleTextView.paintFlags = titleTextView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                titleTextView.paintFlags = titleTextView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            if (task.deadline != null && task.deadline!! > 0) {
                val sdf = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
                deadlineTextView.text = "Deadline: ${sdf.format(Date(task.deadline!!))}"
                deadlineTextView.visibility = View.VISIBLE
            } else {
                deadlineTextView.visibility = View.GONE
            }

            // Re-attach the listener
            // Re-attach the listener
            checkBox.setOnCheckedChangeListener { _, newCheckedState ->
                // This listener should only be active for user interactions because we set it to null
                // before programmatically setting checkBox.isChecked.
                // We only call onTaskChecked if the new state is different from the model's current state.
                if (newCheckedState != task.isCompleted) {
                    onTaskChecked(task, newCheckedState)
                }
            }

            deleteButton.setOnClickListener {
                onDeleteClicked(task)
            }

            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    onDragStarted(this)
                }
                false // Important: return false so other touch events are not consumed if not dragging
            }
        }
    }

    class TaskDiffCallback : DiffUtil.ItemCallback<TaskItem>() {
        override fun areItemsTheSame(oldItem: TaskItem, newItem: TaskItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TaskItem, newItem: TaskItem): Boolean {
            return oldItem == newItem
        }
    }
}