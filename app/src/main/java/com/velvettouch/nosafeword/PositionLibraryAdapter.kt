package com.velvettouch.nosafeword

import android.util.Log
import androidx.annotation.Keep // Import @Keep
import com.bumptech.glide.Glide // Add Glide import
import com.google.firebase.firestore.PropertyName
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

@Keep // Add @Keep annotation
data class PositionItem(
    val id: String = "", // Document ID from Firestore
    val name: String = "",
    val imageName: String = "",
    @get:PropertyName("asset") @set:PropertyName("asset") var isAsset: Boolean = false, // Default to false; true for assets created in code
    val userId: String? = null, // To associate with a Firebase User, nullable for anonymous
    @get:PropertyName("favorite") @set:PropertyName("favorite") var isFavorite: Boolean = false // New field for favorite status
)

class PositionLibraryAdapter(
    private val context: Context,
    private var positions: List<PositionItem>,
    private val onItemClick: (PositionItem) -> Unit,
    private val onDeleteClick: (positionId: String, positionName: String) -> Unit
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
            Log.d("PositionAdapter", "Binding item: Name=${positionItem.name}, ImageName=${positionItem.imageName}, IsAsset=${positionItem.isAsset}, ID=${positionItem.id}") // Log item details
            try {
                // Reset image view before loading new image to prevent showing stale image from recycled view
                positionImageView.setImageDrawable(null)
                positionImageView.setImageResource(R.drawable.ic_image_24) // Set placeholder immediately

                if (positionItem.isAsset) {
                    val inputStream = context.assets.open("positions/${positionItem.imageName}")
                    val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, null)
                    positionImageView.setImageDrawable(drawable)
                    inputStream.close()
                } else {
                    // Handle loading from app storage or content URI
                    if (positionItem.imageName.isNotBlank()) {
                        if (positionItem.imageName.startsWith("http://") || positionItem.imageName.startsWith("https://")) {
                            Log.d("PositionAdapter", "Loading network URL with Glide: ${positionItem.imageName}")
                            Glide.with(itemView.context)
                                .load(positionItem.imageName)
                                .placeholder(R.drawable.ic_image_24) // Placeholder while loading
                                .error(R.drawable.ic_image_24) // Placeholder on error
                                .into(positionImageView)
                        } else if (positionItem.imageName.startsWith("content://")) {
                            // It's a local content URI, attempt to load with Glide
                            Log.d("PositionAdapter", "Loading content URI with Glide: ${positionItem.imageName}")
                            Glide.with(itemView.context)
                                .load(Uri.parse(positionItem.imageName))
                                .placeholder(R.drawable.ic_image_24)
                                .error(R.drawable.ic_image_24)
                                .into(positionImageView)
                       } else if (positionItem.imageName.startsWith("/")) {
                           // Check if it's an absolute file path (our internal storage case)
                           Log.d("PositionAdapter", "Loading local file with Glide: ${positionItem.imageName}")
                           val localFile = java.io.File(positionItem.imageName)
                           if (localFile.exists()) {
                               Log.d("PositionAdapter", "Attempting to load local file URI: ${Uri.fromFile(localFile)}, Exists: true")
                               Glide.with(itemView.context)
                                   .load(Uri.fromFile(localFile)) // Attempt loading via File URI
                                   .placeholder(R.drawable.ic_image_24)
                                   .error(R.drawable.ic_image_24) // This will be shown if .load fails
                                   .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                                       override fun onLoadFailed(
                                           e: com.bumptech.glide.load.engine.GlideException?,
                                           model: Any?,
                                           target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, 
                                           isFirstResource: Boolean
                                       ): Boolean {
                                           Log.e("PositionAdapter", "Glide (URI) load failed for ${localFile.path}", e)
                                           return false // Let Glide handle the error placeholder
                                       }

                                       override fun onResourceReady(
                                           resource: android.graphics.drawable.Drawable, // Changed to non-nullable
                                           model: Any, // Changed to non-nullable
                                           target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, 
                                           dataSource: com.bumptech.glide.load.DataSource, // Changed to non-nullable
                                           isFirstResource: Boolean
                                       ): Boolean {
                                           Log.d("PositionAdapter", "Glide (URI) load success for ${localFile.path}")
                                           return false
                                       }
                                   })
                                   .into(positionImageView)
                           } else {
                               Log.e("PositionAdapter", "Local file does not exist: ${positionItem.imageName}")
                               positionImageView.setImageResource(R.drawable.ic_image_24)
                           }
                       } else {
                           Log.w("PositionAdapter", "Unknown imageName format for non-asset: ${positionItem.imageName}")
                           positionImageView.setImageResource(R.drawable.ic_image_24)
                       }
                   } else {
                        Log.d("PositionAdapter", "ImageName is blank for non-asset item: ${positionItem.name}")
                        positionImageView.setImageResource(R.drawable.ic_image_24)
                    }
                }
            } catch (e: Exception) { // Catch generic Exception as Glide might throw various types
                Log.e("PositionAdapter", "Exception loading image for ${positionItem.name} with URI ${positionItem.imageName}: ${e.message}")
                e.printStackTrace()
                positionImageView.setImageResource(R.drawable.ic_image_24)
                // Optionally, log this or inform the user that the image couldn't be loaded due to permissions.
                // Toast.makeText(context, "Failed to load image: Permission denied.", Toast.LENGTH_SHORT).show()
            }

            itemView.setOnClickListener {
                onItemClick(positionItem)
            }
            deleteButton.setOnClickListener {
                onDeleteClick(positionItem.id, positionItem.name)
            }
        }
    }
}
