package com.velvettouch.nosafeword.data.model

import java.util.UUID

data class TaskItem(
    val id: String = UUID.randomUUID().toString(), // Unique ID for each task
    var title: String,
    var deadline: Long? = null, // Timestamp for deadline, nullable
    var isCompleted: Boolean = false,
    var order: Int = 0 // For custom ordering, can be updated with drag-drop
) {
    // No-argument constructor for Firebase deserialization
    constructor() : this("", "", null, false, 0)
}