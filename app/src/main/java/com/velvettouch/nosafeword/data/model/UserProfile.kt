package com.velvettouch.nosafeword.data.model

import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class UserProfile(
    val uid: String = "", // Firebase UID
    val email: String? = null,
    val displayName: String? = null,
    var pairingCode: String? = null,
    @ServerTimestamp // Automatically set by Firestore on creation
    var pairingCodeTimestamp: Date? = null, // To expire pairing codes after some time
    var pairedWith: String? = null, // UID of the paired user
    var role: String? = null, // "Dom" or "Sub"
    var fcmToken: String? = null,
    @ServerTimestamp
    var lastSeen: Date? = null, // For presence or activity tracking
    @ServerTimestamp
    var profileCreatedAt: Date? = null
) {
    // No-argument constructor for Firebase deserialization
    constructor() : this(
        uid = "",
        email = null,
        displayName = null,
        pairingCode = null,
        pairingCodeTimestamp = null,
        pairedWith = null,
        role = null,
        fcmToken = null,
        lastSeen = null,
        profileCreatedAt = null
    )
}
