package com.velvettouch.nosafeword.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import com.velvettouch.nosafeword.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    // IMPORTANT: Make sure you have uploaded update.json to this URL
    private const val UPDATE_JSON_URL = "https://app-d52.pages.dev/download/update.json"

    suspend fun getLatestUpdateInfo(context: Context): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(UPDATE_JSON_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000 // 15 seconds
                connection.readTimeout = 15000 // 15 seconds
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val reader = InputStreamReader(inputStream)
                    val updateInfo = Gson().fromJson(reader, UpdateInfo::class.java)
                    reader.close()
                    inputStream.close()
                    Timber.d("Successfully fetched update info: $updateInfo")
                    return@withContext updateInfo
                } else {
                    Timber.e("Failed to fetch update.json. Response code: ${connection.responseCode}")
                    return@withContext null
                }
            } catch (e: Exception) {
                Timber.e(e, "Error fetching or parsing update.json")
                return@withContext null
            }
        }
    }

    fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "Could not get package info")
            -1 // Should not happen
        }
    }

    suspend fun isUpdateAvailable(context: Context): Pair<Boolean, UpdateInfo?> {
        val latestUpdateInfo = getLatestUpdateInfo(context)
        if (latestUpdateInfo != null) {
            val currentVersionCode = getCurrentVersionCode(context)
            if (currentVersionCode < latestUpdateInfo.latestVersionCode) {
                Timber.d("Update available. Current: $currentVersionCode, Latest: ${latestUpdateInfo.latestVersionCode}")
                return Pair(true, latestUpdateInfo)
            } else {
                Timber.d("App is up to date. Current: $currentVersionCode, Latest: ${latestUpdateInfo.latestVersionCode}")
            }
        }
        return Pair(false, latestUpdateInfo) // Return latestUpdateInfo even if not newer, for minRequiredVersion check
    }
}
