package com.velvettouch.nosafeword

/**
 * Data model for representing a scene from the JSON file
 */
data class Scene(
    val id: Int,
    val title: String,
    val content: String,
    val isCustom: Boolean = false // Added to distinguish custom scenes
)
