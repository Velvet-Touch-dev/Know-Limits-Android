package com.velvettouch.nosafeword

import android.content.Context
import android.util.Log

class LocalFavoritesRepository(private val context: Context) {

    companion object {
        private const val FAVORITES_PREFS_NAME = "FavoritesPrefs" // Consistent with existing
        private const val FAVORITE_SCENE_IDS_KEY = "favoriteSceneIds" // Consistent with existing
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

    fun clearLocalFavoriteScenes() {
        sharedPreferences.edit().remove(FAVORITE_SCENE_IDS_KEY).apply()
        Log.d(TAG, "Cleared all local favorite scene IDs.")
    }
}