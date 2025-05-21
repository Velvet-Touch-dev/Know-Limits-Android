package com.velvettouch.nosafeword

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.IOException

data class PositionItem(
    val id: String = "", // Document ID from Firestore
    val name: String = "",
    val imageName: String = "",
    val isAsset: Boolean = false, // Default to false; true for assets created in code
    val userId: String = "", // To associate with a Firebase User
    val isFavorite: Boolean = false // New field for favorite status
)

class PositionLibraryAdapter(
    private val context: Context,
    private var positions: List<PositionItem>,
    private val onItemClick: (PositionItem) -> Unit,
    private val onDeleteClick: (PositionItem) -> Unit
) : RecyclerView.Adapter<PositionLibraryAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_position_library, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val positionItem = positions[position]
        holder.bind(positionItem)
    }

    override fun getItemCount(): Int = positions.size

    fun updatePositions(newPositions: List<PositionItem>) {
        positions = newPositions
        notifyDataSetChanged() // Or use DiffUtil for better performance
    }

    fun getPositionAt(adapterPosition: Int): PositionItem {
        return positions[adapterPosition]
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val positionNameTextView: TextView = itemView.findViewById(R.id.position_library_name)
        private val positionImageView: ImageView = itemView.findViewById(R.id.position_library_image)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.position_library_delete_button)

        fun bind(positionItem: PositionItem) {
            positionNameTextView.text = positionItem.name
            try {
                if (positionItem.isAsset) {
                    val inputStream = context.assets.open("positions/${positionItem.imageName}")
                    val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                    positionImageView.setImageDrawable(drawable)
                    inputStream.close()
                } else {
                    // Handle loading from app storage if/when implemented
                    // For now, this branch won't be hit if all items are assets
                    positionImageView.setImageURI(Uri.parse(positionItem.imageName))
                }
            } catch (e: IOException) {
                e.printStackTrace()
                // Set a placeholder image in case of error
                positionImageView.setImageResource(R.drawable.ic_image_24)
            }

            itemView.setOnClickListener {
                onItemClick(positionItem)
            }
            deleteButton.setOnClickListener {
                onDeleteClick(positionItem)
            }
        }
    }
}