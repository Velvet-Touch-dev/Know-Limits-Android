package com.example.randomsceneapp

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class FavoriteScenesAdapter(
    private val onItemClick: (Scene) -> Unit
) : ListAdapter<Scene, FavoriteScenesAdapter.FavoriteViewHolder>(SceneDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_scene, parent, false)
        return FavoriteViewHolder(view, onItemClick)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FavoriteViewHolder(
        itemView: View, 
        private val onItemClick: (Scene) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val titleTextView: TextView = itemView.findViewById(R.id.favorite_title)
        private val previewTextView: TextView = itemView.findViewById(R.id.favorite_preview)
        private lateinit var currentScene: Scene

        init {
            // Set click listener for the card
            itemView.setOnClickListener {
                onItemClick(currentScene)
            }
            
            // Enable links in the preview text
            previewTextView.movementMethod = LinkMovementMethod.getInstance()
            titleTextView.movementMethod = LinkMovementMethod.getInstance()
        }

        fun bind(scene: Scene) {
            currentScene = scene
            
            // Format and set the title with HTML links
            val formattedTitle = formatMarkdownText(scene.title)
            titleTextView.text = HtmlCompat.fromHtml(formattedTitle, HtmlCompat.FROM_HTML_MODE_COMPACT)
            
            // Create a formatted preview with proper links
            val previewContent = scene.content.take(250) // Limit to first 250 chars
                .let { if (it.length >= 250) "$it..." else it } // Add ellipsis if needed
            
            val formattedPreview = formatMarkdownText(previewContent)
            previewTextView.text = HtmlCompat.fromHtml(formattedPreview, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
        
        private fun formatMarkdownText(text: String): String {
            // Convert markdown links [text](url) to HTML links <a href="url">text</a>
            var formattedText = text.replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">$1</a>")
            
            // Convert newlines to HTML breaks
            formattedText = formattedText.replace("\n", "<br>")
            
            return formattedText
        }
    }

    class SceneDiffCallback : DiffUtil.ItemCallback<Scene>() {
        override fun areItemsTheSame(oldItem: Scene, newItem: Scene): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Scene, newItem: Scene): Boolean {
            return oldItem == newItem
        }
    }
}
