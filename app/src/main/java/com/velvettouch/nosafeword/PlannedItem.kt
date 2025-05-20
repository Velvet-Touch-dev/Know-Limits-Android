package com.velvettouch.nosafeword

import java.util.UUID

data class PlannedItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String, // "Position" or "Scene"
    val details: String? = null,
    var order: Int = 0 // For reordering
)