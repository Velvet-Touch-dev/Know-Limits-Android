package com.velvettouch.nosafeword

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class EditScenesAdapter(
    private val onEditClick: (Scene) -> Unit,
    private val onDeleteClick: (Scene) -> Unit
) : ListAdapter<Scene, EditScenesAdapter.EditViewHolder>(SceneDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EditViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_edit_scene, parent, false)
        return EditViewHolder(view, onEditClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: EditViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class EditViewHolder(
        itemView: View,
        private val onEditClick: (Scene) -> Unit,
        private val onDeleteClick: (Scene) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val titleTextView: TextView = itemView.findViewById(R.id.edit_title)
        private val previewTextView: TextView = itemView.findViewById(R.id.edit_preview)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)
        private lateinit var currentScene: Scene

        init {
            // Set click listener for the card (edit)
            itemView.setOnClickListener {
                onEditClick(currentScene)
            }
            
            // Set click listener for the delete button
            deleteButton.setOnClickListener {
                onDeleteClick(currentScene)
            }
        }

        fun bind(scene: Scene) {
            currentScene = scene
            
            // Set title and preview
            titleTextView.text = scene.title
            
            // Create a simplified preview
            val preview = scene.content
                .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1") // Strip markdown links
                .replace("\n", " ") // Replace newlines
                .take(100) // Limit length
                .let { if (it.length >= 100) "$it..." else it } // Add ellipsis
                
            previewTextView.text = preview
        }
    }

    class SceneDiffCallback : DiffUtil.ItemCallback<Scene>() {
        override fun areItemsTheSame(oldItem: Scene, newItem: Scene): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Scene, newItem: Scene): Boolean {
            return oldItem.title == newItem.title && oldItem.content == newItem.content
        }
    }
}
