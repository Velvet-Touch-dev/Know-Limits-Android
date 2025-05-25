package com.velvettouch.nosafeword.data.model

import java.util.UUID

data class RuleItem(
    val id: String = UUID.randomUUID().toString(), // Unique ID for each rule
    var description: String = "", // The content of the rule
    var order: Int = 0, // For custom ordering, can be updated with drag-drop
    var createdByUid: String = "", // UID of the user (Dom) who created the rule
    var createdByName: String? = null, // Display name of the creator (optional)
    var lastUpdatedTimestamp: Long = System.currentTimeMillis() // To track when it was last updated
) {
    // No-argument constructor for Firebase deserialization
    constructor() : this(
        id = UUID.randomUUID().toString(),
        description = "",
        order = 0,
        createdByUid = "",
        createdByName = null,
        lastUpdatedTimestamp = System.currentTimeMillis()
    )
}
