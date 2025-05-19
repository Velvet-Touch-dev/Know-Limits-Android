package com.velvettouch.nosafeword

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.velvettouch.nosafeword.FavoritesActivity.Position

class FavoritePositionsAdapter(
    private val onPositionClick: (Position) -> Unit,
    private val onRemoveClick: (Position) -> Unit
) : ListAdapter<Position, FavoritePositionsAdapter.ViewHolder>(PositionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_position, parent, false)
        return ViewHolder(view, onPositionClick, onRemoveClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onPositionClick: (Position) -> Unit,
        private val onRemoveClick: (Position) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val positionNameTextView: TextView = itemView.findViewById(R.id.position_name)
        private val positionImageView: ImageView = itemView.findViewById(R.id.position_image)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_favorite_button)
        private lateinit var currentPosition: Position

        init {
            // Set click listener for the card
            itemView.setOnClickListener {
                onPositionClick(currentPosition)
            }
            
            // Set click listener for the remove button
            removeButton.setOnClickListener {
                onRemoveClick(currentPosition)
            }
        }

        fun bind(position: Position) {
            currentPosition = position
            
            // Set position name
            positionNameTextView.text = position.name
            
            // Load position image
            try {
                val context = itemView.context
                val inputStream = context.assets.open(position.imagePath)
                val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                positionImageView.setImageDrawable(drawable)
            } catch (e: Exception) {
                e.printStackTrace()
                // Set placeholder if image loading fails
                positionImageView.setImageResource(R.drawable.ic_image_24)
            }
        }
    }

    class PositionDiffCallback : DiffUtil.ItemCallback<Position>() {
        override fun areItemsTheSame(oldItem: Position, newItem: Position): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: Position, newItem: Position): Boolean {
            return oldItem.name == newItem.name && oldItem.imagePath == newItem.imagePath
        }
    }
}