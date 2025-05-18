package com.velvettouch.nosafeword.cardstack

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import com.velvettouch.nosafeword.R
import com.velvettouch.nosafeword.Scene

class SceneCardAdapter(
    private val context: Context,
    private var scenes: List<Scene> = emptyList()
) : RecyclerView.Adapter<SceneCardAdapter.CardViewHolder>() {

    private var onCardSwipedListener: ((Scene) -> Unit)? = null

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.card_title_text_view)
        val contentTextView: TextView = itemView.findViewById(R.id.card_content_text_view)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scene_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val scene = scenes[position]
        
        // Format the title and content with markdown
        val formattedTitle = formatMarkdownText(scene.title)
        val formattedContent = formatMarkdownText(scene.content)
        
        // Set formatted text with HTML support
        holder.titleTextView.text = HtmlCompat.fromHtml(formattedTitle, HtmlCompat.FROM_HTML_MODE_COMPACT)
        holder.contentTextView.text = HtmlCompat.fromHtml(formattedContent, HtmlCompat.FROM_HTML_MODE_COMPACT)
        
        // Enable links in the text views
        holder.titleTextView.movementMethod = LinkMovementMethod.getInstance()
        holder.contentTextView.movementMethod = LinkMovementMethod.getInstance()
    }

    override fun getItemCount(): Int = scenes.size

    fun updateScenes(newScenes: List<Scene>) {
        scenes = newScenes
        notifyDataSetChanged()
    }

    fun setOnCardSwipedListener(listener: (Scene) -> Unit) {
        onCardSwipedListener = listener
    }
    
    fun notifyCardSwiped(position: Int) {
        if (position in scenes.indices) {
            onCardSwipedListener?.invoke(scenes[position])
        }
    }
    
    // Helper function to format markdown text
    private fun formatMarkdownText(text: String): String {
        // Convert markdown links [text](url) to HTML links <a href="url">text</a>
        var formattedText = text.replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">$1</a>")
        
        // Convert newlines to HTML breaks
        formattedText = formattedText.replace("\n", "<br>")
        
        return formattedText
    }
}
