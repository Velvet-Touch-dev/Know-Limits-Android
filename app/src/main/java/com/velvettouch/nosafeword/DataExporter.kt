package com.velvettouch.nosafeword

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DataExporter(
    private val context: Context,
    private val scenesViewModel: ScenesViewModel,
    private val positionsRepository: PositionsRepository,
    private val localFavoritesRepository: LocalFavoritesRepository,
    private val cloudFavoritesRepository: FavoritesRepository, // Added
    private val firebaseAuth: FirebaseAuth
) {

    private val gson = Gson()
    private companion object {
        const val TAG = "DataExporter"
    }

    suspend fun generateExportData(): AppExportData? {
        return withContext(Dispatchers.IO) {
            try {
                val currentUser = firebaseAuth.currentUser
                val currentTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                // 1. Get Custom Scenes
                val customScenes = getExportableCustomScenes(currentUser?.uid)

                // 2. Get Custom Positions
                val customPositions = getExportableCustomPositions(currentUser?.uid)

                // 3. Get Favorite Scene IDs
                val favoriteSceneIds = getFavoriteSceneExportIds(currentUser?.uid)

                // 4. Get Favorite Asset Position IDs
                val favoriteAssetPositionIds = getFavoriteAssetPositionExportIds(currentUser?.uid)

                AppExportData(
                    exportedTimestamp = currentTimestamp,
                    customScenes = customScenes,
                    customPositions = customPositions,
                    favoriteSceneExportIds = favoriteSceneIds,
                    favoriteAssetPositionExportIds = favoriteAssetPositionIds
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error generating export data", e)
                null
            }
        }
    }

    private suspend fun getExportableCustomScenes(userId: String?): List<ExportableScene> {
        // Use ScenesViewModel to get the current list of scenes
        val scenes = scenesViewModel.getCurrentScenesForExport()
        
        return scenes.filter { it.isCustom }.map { scene ->
            ExportableScene(
                exportId = scene.getPersistentExportId(), // Use the Scene.kt extension function
                title = scene.title,
                content = scene.content
            )
        }
    }

    private suspend fun getExportableCustomPositions(userId: String?): List<ExportablePosition> {
        val positions = if (userId == null) {
            positionsRepository.loadLocalPositions() // Renamed from getLocalCustomPositions
        } else {
            positionsRepository.getAllUserPositionsOnce(userId).getOrNull() ?: emptyList()
        }

        return positions.filter { !it.isAsset }.mapNotNull { position ->
            val imageDataResult = getImageData(position.imageName, userId)
            if (imageDataResult == null) {
                Log.w(TAG, "Could not get image data for position: ${position.name}, ID: ${position.id}. Skipping.")
                null // Skip if image data can't be retrieved
            } else {
                ExportablePosition(
                    exportId = position.id,
                    name = position.name,
                    isFavorite = position.isFavorite,
                    imageData = imageDataResult.base64Data,
                    imageMimeType = imageDataResult.mimeType
                )
            }
        }
    }

    private data class ImageDataResult(val base64Data: String, val mimeType: String)

    private suspend fun getImageData(imagePathOrUrl: String, userId: String?): ImageDataResult? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream?
                var mimeType: String = "image/jpeg" // Default

                if (imagePathOrUrl.startsWith("http://") || imagePathOrUrl.startsWith("https://")) {
                    // Network URL (likely Firebase Storage URL for logged-in user)
                    val url = URL(imagePathOrUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.doInput = true
                    connection.connect()
                    inputStream = connection.inputStream
                    mimeType = connection.contentType ?: "image/jpeg"
                } else if (imagePathOrUrl.startsWith("content://")) {
                    // Content URI - this case should ideally not happen if images are copied to internal storage first
                    // For export, we expect local file paths for logged-out users.
                    Log.w(TAG, "getImageData: Encountered content URI '$imagePathOrUrl' directly. This should be a local file path for export.")
                    // Attempt to resolve it, but this is less robust.
                    inputStream = context.contentResolver.openInputStream(Uri.parse(imagePathOrUrl))
                    mimeType = context.contentResolver.getType(Uri.parse(imagePathOrUrl)) ?: "image/jpeg"
                }
                else if (imagePathOrUrl.isNotBlank()) {
                    // Local file path (for logged-out user, image copied to internal storage)
                    val file = File(imagePathOrUrl)
                    if (file.exists()) {
                        inputStream = FileInputStream(file)
                        mimeType = getMimeTypeFromFile(file) ?: "image/jpeg"
                    } else {
                        Log.e(TAG, "Local image file not found: $imagePathOrUrl")
                        return@withContext null
                    }
                } else {
                    Log.w(TAG, "Image path/URL is blank for a custom position.")
                    return@withContext null
                }

                if (inputStream == null) return@withContext null

                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap == null) {
                    Log.e(TAG, "Failed to decode bitmap from stream for: $imagePathOrUrl")
                    return@withContext null
                }

                val outputStream = ByteArrayOutputStream()
                val compressFormat = if (mimeType.equals("image/png", ignoreCase = true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(compressFormat, 90, outputStream)
                val byteArray = outputStream.toByteArray()
                val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
                ImageDataResult(base64String, mimeType)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting image data for $imagePathOrUrl", e)
                null
            }
        }
    }

    private fun getMimeTypeFromFile(file: File): String? {
        val extension = file.extension.lowercase(Locale.getDefault())
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null // Or a default like "application/octet-stream"
        }
    }


    private suspend fun getFavoriteSceneExportIds(userId: String?): List<String> {
        return if (userId != null) {
            // Logged in: Fetch from cloudFavoritesRepository
            val cloudFavoritesResult = cloudFavoritesRepository.getCloudFavoritesListSnapshot()
            if (cloudFavoritesResult.isSuccess) {
                cloudFavoritesResult.getOrNull()
                    ?.filter { it.itemType == "scene" }
                    ?.mapNotNull { it.itemId } // itemId should not be null in Favorite
                    ?: emptyList()
            } else {
                Log.e(TAG, "Failed to fetch cloud favorite scenes", cloudFavoritesResult.exceptionOrNull())
                emptyList() // Fallback to empty list on error
            }
        } else {
            // Logged out: Use LocalFavoritesRepository
            localFavoritesRepository.getLocalFavoriteSceneIds().toList()
        }
    }

    private suspend fun getFavoriteAssetPositionExportIds(userId: String?): List<String> {
        return if (userId != null) {
            // Logged in: Fetch from cloudFavoritesRepository
            val cloudFavoritesResult = cloudFavoritesRepository.getCloudFavoritesListSnapshot()
            if (cloudFavoritesResult.isSuccess) {
                cloudFavoritesResult.getOrNull()
                    ?.filter { it.itemType == "position" && it.itemId.startsWith("asset_") }
                    ?.mapNotNull { it.itemId }
                    ?: emptyList()
            } else {
                Log.e(TAG, "Failed to fetch cloud favorite asset positions", cloudFavoritesResult.exceptionOrNull())
                emptyList()
            }
        } else {
            // Logged out: Use LocalFavoritesRepository
            localFavoritesRepository.getLocalFavoritePositionIds().filter { it.startsWith("asset_") }.toList()
        }
    }
}
