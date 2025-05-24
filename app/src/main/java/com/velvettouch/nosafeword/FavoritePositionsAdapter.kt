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
import com.bumptech.glide.Glide // Added for image loading
import android.net.Uri // Added for Uri parsing

class FavoritePositionsAdapter(
    private val onPositionClick: (PositionItem) -> Unit, // Changed to PositionItem
    private val onRemoveClick: (PositionItem) -> Unit   // Changed to PositionItem
) : ListAdapter<PositionItem, FavoritePositionsAdapter.ViewHolder>(PositionDiffCallback()) { // Changed to PositionItem

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_position, parent, false)
        return ViewHolder(view, onPositionClick, onRemoveClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    fun removeItem(position: Int) {
        val favoritePosition = getItem(position)
        onRemoveClick(favoritePosition)
    }

    class ViewHolder(
        itemView: View,
        private val onPositionClick: (PositionItem) -> Unit, // Changed to PositionItem
        private val onRemoveClick: (PositionItem) -> Unit    // Changed to PositionItem
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val positionNameTextView: TextView = itemView.findViewById(R.id.position_name)
        private val positionImageView: ImageView = itemView.findViewById(R.id.position_image)
        private lateinit var currentPositionItem: PositionItem // Changed to PositionItem

        init {
            // Set click listener for the card
            itemView.setOnClickListener {
                if (::currentPositionItem.isInitialized) {
                    onPositionClick(currentPositionItem)
                }
            }
        }

        fun bind(positionItem: PositionItem) { // Changed to positionItem
            currentPositionItem = positionItem
            
            // Set position name
            positionNameTextView.text = positionItem.name
            
            // Load position image
            positionImageView.setImageResource(R.drawable.ic_image_24) // Default placeholder
            try {
                val context = itemView.context
                if (positionItem.imageName.isNotBlank()) {
                    if (positionItem.isAsset) {
                        val inputStream = context.assets.open("positions/${positionItem.imageName}")
                        val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                        positionImageView.setImageDrawable(drawable)
                        inputStream.close()
                    } else {
                        // Not an asset, could be URL or local file path (though less likely for synced favorites)
                        val imagePath = positionItem.imageName
                        when {
                            imagePath.startsWith("http://") || imagePath.startsWith("https://") -> {
                                Glide.with(context).load(imagePath)
                                    .placeholder(R.drawable.ic_image_24)
                                    .error(R.drawable.ic_image_24)
                                    .into(positionImageView)
                            }
                            imagePath.startsWith("content://") -> {
                                Glide.with(context).load(Uri.parse(imagePath))
                                    .placeholder(R.drawable.ic_image_24)
                                    .error(R.drawable.ic_image_24)
                                    .into(positionImageView)
                            }
                            imagePath.startsWith("/") -> { // Absolute file path
                                val localFile = java.io.File(imagePath)
                                if (localFile.exists()) {
                                    Glide.with(context).load(localFile)
                                        .placeholder(R.drawable.ic_image_24)
                                        .error(R.drawable.ic_image_24)
                                        .into(positionImageView)
                                } else {
                                    positionImageView.setImageResource(R.drawable.ic_image_24)
                                }
                            }
                            else -> { // Fallback for unknown path, or if it's just a filename for a non-asset (should not happen)
                                positionImageView.setImageResource(R.drawable.ic_image_24)
                            }
                        }
                    }
                } else {
                     positionImageView.setImageResource(R.drawable.ic_image_24)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                positionImageView.setImageResource(R.drawable.ic_image_24)
            }
        }
    }

    class PositionDiffCallback : DiffUtil.ItemCallback<PositionItem>() { // Changed to PositionItem
        override fun areItemsTheSame(oldItem: PositionItem, newItem: PositionItem): Boolean {
            return oldItem.id == newItem.id // Compare by ID
        }

        override fun areContentsTheSame(oldItem: PositionItem, newItem: PositionItem): Boolean {
            return oldItem == newItem
        }
    }
}