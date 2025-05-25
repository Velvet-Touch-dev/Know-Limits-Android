package com.velvettouch.nosafeword

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.velvettouch.nosafeword.data.model.RuleItem

class RulesAdapter(
    private val onDeleteClicked: (RuleItem) -> Unit,
    private val onDragStarted: (RecyclerView.ViewHolder) -> Unit,
    private val onRuleClicked: (RuleItem) -> Unit, // For editing a rule when in edit mode
    private val isUserDomProvider: () -> Boolean // To check user role
) : ListAdapter<RuleItem, RulesAdapter.RuleViewHolder>(RuleDiffCallback()) {

    private var isInEditMode: Boolean = false

    fun setEditMode(editMode: Boolean) {
        if (isInEditMode != editMode) {
            isInEditMode = editMode
            notifyItemRangeChanged(0, itemCount, PAYLOAD_EDIT_MODE_CHANGED) // Use payload for efficiency
        }
    }

    fun getEditMode(): Boolean = isInEditMode

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule, parent, false)
        return RuleViewHolder(view, isUserDomProvider)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        val rule = getItem(position)
        holder.bind(rule, position + 1, isInEditMode, onDeleteClicked, onDragStarted, onRuleClicked)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_EDIT_MODE_CHANGED)) {
            // val rule = getItem(position) // Not needed for just updating visibility based on mode
            holder.updateControlsVisibility(isInEditMode) // Pass the adapter's current overall edit mode
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    class RuleViewHolder(itemView: View, private val isUserDomProvider: () -> Boolean) : RecyclerView.ViewHolder(itemView) {
        private val descriptionTextView: TextView = itemView.findViewById(R.id.rule_description_textview)
        private val ruleNumberTextView: TextView = itemView.findViewById(R.id.rule_number_textview)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_rule_button)
        private val dragHandle: ImageView = itemView.findViewById(R.id.drag_handle_rule)
        private var currentIsInEditMode: Boolean = false // Store current edit mode state for listeners

        fun bind(
            rule: RuleItem,
            ruleNumber: Int,
            isInitialEditMode: Boolean, // Initial edit mode state from adapter for this bind
            onDeleteClicked: (RuleItem) -> Unit,
            onDragStarted: (RecyclerView.ViewHolder) -> Unit,
            onRuleClicked: (RuleItem) -> Unit
        ) {
            descriptionTextView.text = rule.description
            ruleNumberTextView.text = "$ruleNumber."

            updateControlsVisibility(isInitialEditMode) // Pass the adapter's current overall edit mode

            deleteButton.setOnClickListener {
                if (isUserDomProvider() && currentIsInEditMode) { // Use ViewHolder's currentIsInEditMode
                    onDeleteClicked(rule)
                }
            }

            itemView.setOnClickListener {
                if (isUserDomProvider() && currentIsInEditMode) { // Use ViewHolder's currentIsInEditMode
                    onRuleClicked(rule)
                }
            }

            dragHandle.setOnTouchListener { _, event ->
                if (isUserDomProvider() && currentIsInEditMode && event.actionMasked == MotionEvent.ACTION_DOWN) { // Use ViewHolder's currentIsInEditMode
                    onDragStarted(this)
                    true // Consume the event
                } else {
                    false // Do not consume if not dragging
                }
            }
        }

        // Renamed parameter to avoid confusion with member variable
        fun updateControlsVisibility(isAdapterInEditMode: Boolean) {
            this.currentIsInEditMode = isAdapterInEditMode // Update ViewHolder's state
            val isDom = isUserDomProvider()

            if (isDom && this.currentIsInEditMode) {
                dragHandle.visibility = View.VISIBLE
                deleteButton.visibility = View.VISIBLE
                ruleNumberTextView.visibility = View.GONE
            } else {
                dragHandle.visibility = View.GONE
                deleteButton.visibility = View.GONE
                ruleNumberTextView.visibility = View.VISIBLE
            }
        }
    }

    class RuleDiffCallback : DiffUtil.ItemCallback<RuleItem>() {
        override fun areItemsTheSame(oldItem: RuleItem, newItem: RuleItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: RuleItem, newItem: RuleItem): Boolean {
            // Consider if edit mode visual changes should trigger content change.
            // For now, only data matters.
            return oldItem == newItem
        }
    }

    companion object {
        private const val PAYLOAD_EDIT_MODE_CHANGED = "PAYLOAD_EDIT_MODE_CHANGED"
    }
}
