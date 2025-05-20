package com.velvettouch.nosafeword

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.velvettouch.nosafeword.databinding.ItemPlannedNightBinding
import java.util.Collections

class PlannedNightAdapter(
    private val plannedItems: MutableList<PlannedItem>,
    private val onItemClick: (PlannedItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onItemDismiss: (Int) -> Unit
) : RecyclerView.Adapter<PlannedNightAdapter.PlannedItemViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlannedItemViewHolder {
        val binding = ItemPlannedNightBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlannedItemViewHolder(binding)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: PlannedItemViewHolder, position: Int) {
        val item = plannedItems[position]
        holder.bind(item)
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.binding.buttonDeletePlannedItem.setOnClickListener {
            onItemDismiss(holder.adapterPosition)
        }
        holder.binding.imageViewDragHandlePlannedItem.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int = plannedItems.size

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(plannedItems, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(plannedItems, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        // Update order in items if necessary for persistence
        plannedItems.forEachIndexed { index, item -> item.order = index }
        return true
    }

    fun removeItem(position: Int) {
        if (position >= 0 && position < plannedItems.size) {
            plannedItems.removeAt(position)
            notifyItemRemoved(position)
            // Update order in remaining items if necessary for persistence
            plannedItems.forEachIndexed { index, item -> item.order = index }
        }
    }


    inner class PlannedItemViewHolder(val binding: ItemPlannedNightBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlannedItem) {
            binding.textViewPlannedItemName.text = item.name
            binding.textViewPlannedItemType.text = item.type
            // Potentially load an image or set an icon based on type
        }
    }
}