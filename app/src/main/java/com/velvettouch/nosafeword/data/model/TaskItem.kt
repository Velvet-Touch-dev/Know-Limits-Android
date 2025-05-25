package com.velvettouch.nosafeword.data.model

import java.util.UUID

data class TaskItem(
    val id: String = UUID.randomUUID().toString(), // Unique ID for each task
    var title: String = "",
    var deadline: Long? = null, // Timestamp for deadline, nullable
    var isCompleted: Boolean = false,
    var order: Int = 0, // For custom ordering, can be updated with drag-drop
    var createdByUid: String = "", // UID of the user who created the task
    var createdByName: String? = null, // Display name of the creator (optional, for convenience)
    var createdByRole: String? = null // "Dom" or "Sub" (optional, for convenience)
) {
    // No-argument constructor for Firebase deserialization
    constructor() : this(
        id = UUID.randomUUID().toString(), // Ensure ID is always initialized
        title = "",
        deadline = null,
        isCompleted = false,
        order = 0,
        createdByUid = "",
        createdByName = null,
        createdByRole = null
    )
}
