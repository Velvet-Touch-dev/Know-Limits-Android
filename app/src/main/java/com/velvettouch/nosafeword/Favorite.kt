package com.velvettouch.nosafeword

import androidx.annotation.Keep // Import @Keep
import com.google.firebase.firestore.PropertyName

@Keep // Add @Keep annotation
data class Favorite(
    var id: String = "", // Document ID in Firestore
    @get:PropertyName("item_id") @set:PropertyName("item_id")
    var itemId: String = "",
    @get:PropertyName("item_type") @set:PropertyName("item_type")
    var itemType: String = "", // "scene" or "position"
    @get:PropertyName("user_id") @set:PropertyName("user_id")
    var userId: String = "",
    var timestamp: Long = System.currentTimeMillis() // Optional: for sorting or tracking
) {
    // No-argument constructor for Firestore deserialization
    constructor() : this("", "", "", "", 0L)
}
