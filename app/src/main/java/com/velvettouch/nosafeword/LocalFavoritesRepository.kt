package com.velvettouch.nosafeword

import android.content.Context
import android.util.Log

class LocalFavoritesRepository(private val context: Context) {

    companion object {
        private const val FAVORITES_PREFS_NAME = "FavoritesPrefs" // Consistent with existing
        private const val FAVORITE_SCENE_IDS_KEY = "favoriteSceneIds" // Consistent with existing
        private const val FAVORITE_POSITION_IDS_KEY = "favoritePositionIds" // New key for positions
        private const val TAG = "LocalFavoritesRepo"
    }

    private val sharedPreferences = context.getSharedPreferences(FAVORITES_PREFS_NAME, Context.MODE_PRIVATE)

    fun getLocalFavoriteSceneIds(): Set<String> {
        val ids = sharedPreferences.getStringSet(FAVORITE_SCENE_IDS_KEY, emptySet()) ?: emptySet()
        Log.d(TAG, "Loaded local favorite scene IDs: $ids")
        return ids
    }

    fun addLocalFavoriteScene(sceneId: String) {
        val currentFavorites = getLocalFavoriteSceneIds().toMutableSet()
        if (currentFavorites.add(sceneId)) {
            sharedPreferences.edit().putStringSet(FAVORITE_SCENE_IDS_KEY, currentFavorites).apply()
            Log.d(TAG, "Added local favorite scene ID: $sceneId. Current IDs: $currentFavorites")
        } else {
            Log.d(TAG, "Scene ID $sceneId already a local favorite.")
        }
    }

    fun removeLocalFavoriteScene(sceneId: String) {
        val currentFavorites = getLocalFavoriteSceneIds().toMutableSet()
        if (currentFavorites.remove(sceneId)) {
            sharedPreferences.edit().putStringSet(FAVORITE_SCENE_IDS_KEY, currentFavorites).apply()
            Log.d(TAG, "Removed local favorite scene ID: $sceneId. Current IDs: $currentFavorites")
        } else {
            Log.d(TAG, "Scene ID $sceneId was not in local favorites to remove.")
        }
    }

    fun isLocalFavoriteScene(sceneId: String): Boolean {
        val isFav = getLocalFavoriteSceneIds().contains(sceneId)
        Log.d(TAG, "Is scene ID $sceneId a local favorite? $isFav")
        return isFav
    }

    // Methods for Positions
    fun getLocalFavoritePositionIds(): Set<String> {
        val ids = sharedPreferences.getStringSet(FAVORITE_POSITION_IDS_KEY, emptySet()) ?: emptySet()
        Log.d(TAG, "Loaded local favorite position IDs: $ids")
        return ids
    }

    fun addLocalFavoritePosition(positionId: String) {
        val currentFavorites = getLocalFavoritePositionIds().toMutableSet()
        if (currentFavorites.add(positionId)) {
            sharedPreferences.edit().putStringSet(FAVORITE_POSITION_IDS_KEY, currentFavorites).apply()
            Log.d(TAG, "Added local favorite position ID: $positionId. Current IDs: $currentFavorites")
        } else {
            Log.d(TAG, "Position ID $positionId already a local favorite.")
        }
    }

    fun removeLocalFavoritePosition(positionId: String) {
        val currentFavorites = getLocalFavoritePositionIds().toMutableSet()
        if (currentFavorites.remove(positionId)) {
            sharedPreferences.edit().putStringSet(FAVORITE_POSITION_IDS_KEY, currentFavorites).apply()
            Log.d(TAG, "Removed local favorite position ID: $positionId. Current IDs: $currentFavorites")
        } else {
            Log.d(TAG, "Position ID $positionId was not in local favorites to remove.")
        }
    }

    fun isLocalFavoritePosition(positionId: String): Boolean {
        val isFav = getLocalFavoritePositionIds().contains(positionId)
        Log.d(TAG, "Is position ID $positionId a local favorite? $isFav")
        return isFav
    }

    fun getAllLocalFavoritesAsFavoriteObjects(): List<Favorite> {
        val localFavorites = mutableListOf<Favorite>()
        getLocalFavoriteSceneIds().forEach { sceneId ->
            localFavorites.add(Favorite(itemId = sceneId, itemType = "scene", userId = "")) // userId will be set by cloud repo if needed
        }
        getLocalFavoritePositionIds().forEach { positionId ->
            localFavorites.add(Favorite(itemId = positionId, itemType = "position", userId = "")) // userId will be set by cloud repo if needed
        }
        Log.d(TAG, "All local favorites as Favorite objects: $localFavorites")
        return localFavorites
    }

    fun clearAllLocalFavorites() {
        sharedPreferences.edit()
            .remove(FAVORITE_SCENE_IDS_KEY)
            .remove(FAVORITE_POSITION_IDS_KEY)
            .apply()
        Log.d(TAG, "Cleared all local favorite scene and position IDs.")
    }
}