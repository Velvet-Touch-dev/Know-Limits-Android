package com.velvettouch.nosafeword

import androidx.annotation.Keep

@Keep
data class ExportableScene(
    val exportId: String,
    val title: String,
    val content: String
    // isCustom is implied true
)

@Keep
data class ExportablePosition(
    val exportId: String,
    val name: String,
    val isFavorite: Boolean,
    val imageData: String, // Base64 encoded image
    val imageMimeType: String
    // isAsset is implied false
)

@Keep
data class AppExportData( // Renamed from ExportData to avoid potential conflicts and be more specific
    val version: Int = 1,
    val exportedTimestamp: String,
    val customScenes: List<ExportableScene>,
    val customPositions: List<ExportablePosition>,
    val favoriteSceneExportIds: List<String>,
    val favoriteAssetPositionExportIds: List<String>
)
