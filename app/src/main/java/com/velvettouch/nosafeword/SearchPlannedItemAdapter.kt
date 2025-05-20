package com.velvettouch.nosafeword

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.velvettouch.nosafeword.databinding.ItemSearchResultBinding

class SearchPlannedItemAdapter(
    private var searchResults: List<PlannedItem> // Can be positions or scenes
) : RecyclerView.Adapter<SearchPlannedItemAdapter.SearchResultViewHolder>() {

    private val selectedItems = mutableSetOf<PlannedItem>()
    var onSelectionChanged: ((List<PlannedItem>) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val binding = ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SearchResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        val item = searchResults[position]
        holder.bind(item, selectedItems.contains(item))

        holder.itemView.setOnClickListener {
            if (selectedItems.contains(item)) {
                selectedItems.remove(item)
            } else {
                selectedItems.add(item)
            }
            notifyItemChanged(position)
            onSelectionChanged?.invoke(selectedItems.toList())
        }
    }

    override fun getItemCount(): Int = searchResults.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newResults: List<PlannedItem>) {
        searchResults = newResults
        selectedItems.clear() // Clear selection when data updates
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedItems.toList()) // Notify that selection is now empty
    }

    fun getSelectedItems(): List<PlannedItem> {
        return selectedItems.toList()
    }

    inner class SearchResultViewHolder(private val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlannedItem, isSelected: Boolean) {
            binding.itemNameTextview.text = item.name
            binding.itemCheckbox.isChecked = isSelected
            // itemTypeIcon was removed from the layout, so remove its logic
        }
    }
}