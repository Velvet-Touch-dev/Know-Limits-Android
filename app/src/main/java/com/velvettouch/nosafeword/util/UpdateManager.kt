package com.velvettouch.nosafeword.util

import android.app.Activity
// import android.app.AlertDialog // Replaced by androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AlertDialog // Added for MaterialAlertDialogBuilder
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings // Added for install permission
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.velvettouch.nosafeword.R
import com.velvettouch.nosafeword.model.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

object UpdateManager {

    private var downloadID: Long = -1L
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var progressJob: Job? = null
    private var updateDeferredInSessionForVersionCode: Int = -1 // Stores the versionCode deferred

    fun showUpdateAvailableDialog(activity: Activity, updateInfo: UpdateInfo, onUpdateAccepted: () -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update Available")
            .setMessage("A new version (${updateInfo.latestVersionName}) is available. \n\nWhat's new:\n${updateInfo.releaseNotes ?: "No release notes available."}")
            .setPositiveButton("Update Now") { dialog, _ ->
                updateDeferredInSessionForVersionCode = -1 // Reset deferral if user accepts
                onUpdateAccepted()
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                updateDeferredInSessionForVersionCode = updateInfo.latestVersionCode // Mark this version as deferred for the session
                Timber.i("Update deferred for version code: ${updateInfo.latestVersionCode}")
                dialog.dismiss()
            }
            .setCancelable(true) // If cancelled by back press, it's similar to "Later" for this session
            .setOnCancelListener {
                updateDeferredInSessionForVersionCode = updateInfo.latestVersionCode
                Timber.i("Update dialog cancelled, deferred for version code: ${updateInfo.latestVersionCode}")
            }
            .show()
    }

    fun downloadAndInstallUpdate(activity: Activity, updateInfo: UpdateInfo) {
        try {
            val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(updateInfo.apkUrl)
            val fileName = uri.lastPathSegment ?: "app-update-${updateInfo.latestVersionName}.apk"

            // Delete old APK if it exists to avoid conflicts
            val destinationFile = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val request = DownloadManager.Request(uri)
                .setTitle("Downloading ${activity.getString(R.string.app_name)} Update")
                .setDescription("Version ${updateInfo.latestVersionName}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
                .setMimeType("application/vnd.android.package-archive")

            downloadID = downloadManager.enqueue(request)
            Timber.d("Download enqueued with ID: $downloadID, Filename: $fileName")

            showDownloadProgressDialog(activity, downloadManager, downloadID, fileName)

            // Register receiver for download completion
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Timber.i("BroadcastReceiver: onReceive triggered. Intent action: ${intent?.action}") // Log entry into onReceive
                    val receivedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    Timber.d("BroadcastReceiver onReceive: Received ID: $receivedId, Expected ID: $downloadID")

                    if (receivedId == downloadID) {
                        Timber.i("Download completed broadcast received for correct ID: $downloadID")
                        
                        progressJob?.cancel()
                        Timber.d("ProgressJob cancelled.")

                        try {
                            progressDialog?.dismiss()
                            Timber.d("ProgressDialog dismissed.")
                        } catch (e: Exception) {
                            Timber.e(e, "Error dismissing progressDialog")
                        }

                        try {
                            // Check if activity is finishing to avoid crash on unregisterReceiver
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                activity.unregisterReceiver(this)
                                Timber.d("BroadcastReceiver unregistered successfully.")
                            } else {
                                Timber.w("Activity is finishing or destroyed, cannot unregister BroadcastReceiver.")
                            }
                        } catch (e: IllegalArgumentException) {
                            Timber.w(e, "BroadcastReceiver was already unregistered or not registered.")
                        } catch (e: Exception) {
                            Timber.e(e, "Error unregistering BroadcastReceiver")
                        }

                        val query = DownloadManager.Query().setFilterById(downloadID)
                        val cursor: Cursor? = downloadManager.query(query)
                        if (cursor != null && cursor.moveToFirst()) {
                            Timber.d("Cursor obtained for download ID $downloadID.")
                            val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val reasonColumn = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                            if (statusColumn != -1) {
                                val status = cursor.getInt(statusColumn)
                                val localUriString = if(localUriColumn != -1) cursor.getString(localUriColumn) else "N/A"
                                val reason = if (reasonColumn != -1) cursor.getInt(reasonColumn) else -1
                                
                                Timber.i("Download ID $downloadID Status: $status, Local URI: $localUriString, Reason: $reason")

                                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                    Timber.i("Download successful for ID $downloadID. Attempting to install using destinationFile: ${destinationFile.absolutePath}")
                                    installApk(activity, destinationFile)
                                } else {
                                    Timber.e("Download failed for ID $downloadID. Status: $status, Reason: $reason")
                                    Toast.makeText(activity, "Update download failed. Reason: $reason", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Timber.e("COLUMN_STATUS not found in cursor for ID $downloadID.")
                                Toast.makeText(activity, "Error checking download status.", Toast.LENGTH_LONG).show()
                            }
                            cursor.close()
                        } else {
                             Timber.e("Download cursor is null or empty for ID: $downloadID")
                             Toast.makeText(activity, "Could not retrieve download status.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Timber.w("BroadcastReceiver onReceive: Received ID $receivedId does not match expected ID $downloadID. Ignoring.")
                    }
                }
            }
            // Corrected: Removed extra brace that was here
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
                Timber.d("BroadcastReceiver registered with RECEIVER_NOT_EXPORTED for API 33+.")
            } else {
                activity.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
                Timber.d("BroadcastReceiver registered without specific export flag for pre-API 33.")
            }

        } catch (e: Exception) {
            Timber.e(e, "Error starting download")
            Toast.makeText(activity, "Error starting download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloadProgressDialog(activity: Activity, downloadManager: DownloadManager, downloadId: Long, fileName: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.pbDownload)
        val progressText = dialogView.findViewById<TextView>(R.id.tvProgressPercentage)
        val titleText = dialogView.findViewById<TextView>(R.id.tvProgressTitle)
        titleText.text = "Downloading ${activity.getString(R.string.app_name)} v${fileName.substringAfterLast("v").substringBefore(".apk")}"


        progressDialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancel") { dialog, _ ->
                downloadManager.remove(downloadId)
                progressJob?.cancel()
                dialog.dismiss()
                Toast.makeText(activity, "Update download cancelled.", Toast.LENGTH_SHORT).show()
            }
            .create()
        
        progressDialog?.show()

        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor? = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloadedColumn = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalColumn = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

                    if (bytesDownloadedColumn != -1 && bytesTotalColumn != -1 && statusColumn != -1) {
                        val bytesDownloaded = cursor.getLong(bytesDownloadedColumn) // Changed to getLong
                        val bytesTotal = cursor.getLong(bytesTotalColumn) // Changed to getLong
                        val status = cursor.getInt(statusColumn)
                        Timber.d("Download Progress: Status for ID $downloadId is $status")

                        if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                            Timber.i("Download Progress: Loop breaking due to status $status for ID $downloadId")
                            break // Exit loop if download finished or failed
                        }

                        if (bytesTotal > 0) {
                            progressBar.isIndeterminate = false
                            // Calculate progress as float first for precision
                            val progressPercentage = (bytesDownloaded.toFloat() / bytesTotal.toFloat()) * 100f
                            val progress = progressPercentage.roundToInt()
                            Timber.d("Download Progress: $bytesDownloaded / $bytesTotal | float: $progressPercentage% | int: $progress%")
                            progressBar.progress = progress
                            progressText.text = "$progress%"
                        } else {
                            // If total size is unknown or 0, keep progress bar indeterminate
                            progressBar.isIndeterminate = true
                            progressText.text = "Downloading..." // Or show bytes downloaded: "$bytesDownloaded B"
                            Timber.d("Download Progress: $bytesDownloaded / unknown total size")
                        }
                    } else {
                        Timber.w("Download Progress: Necessary columns not found in cursor.")
                    }
                    cursor.close()
                } else {
                    Timber.w("Download Progress: Cursor is null or cannot move to first for downloadId $downloadId")
                }
                delay(500) // Update interval
            }
            Timber.d("Progress update loop finished for downloadId $downloadId. isActive: $isActive") // Added isActive log
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        Timber.i("Attempting to install APK from: ${apkFile.absolutePath}") // Changed to .i for better visibility
        Timber.d("installApk called. File: ${apkFile.absolutePath}, Exists: ${apkFile.exists()}, Size: ${apkFile.length()}")
        if (!apkFile.exists()) {
            Timber.e("APK file does not exist: ${apkFile.absolutePath}")
            Toast.makeText(context, "Downloaded APK not found.", Toast.LENGTH_LONG).show()
            return
        }
        Timber.d("APK file exists: ${apkFile.absolutePath}, size: ${apkFile.length()}")

        val apkUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        Timber.d("APK URI: $apkUri")

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        // Check if there's an app that can handle this intent
        if (installIntent.resolveActivity(context.packageManager) != null) {
            try {
                context.startActivity(installIntent)
            } catch (e: Exception) {
                Timber.e(e, "Error starting APK install intent")
                Toast.makeText(context, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Timber.e("No application found to handle APK installation.")
            Toast.makeText(context, "No application found to handle APK installation.", Toast.LENGTH_LONG).show()
        }
    }

    private fun canRequestPackageInstalls(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else {
            true // Not needed for pre-Oreo
        }
    }

    private fun requestInstallPermission(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Permission Required")
            .setMessage("To install updates, please allow this app to install unknown apps in the next settings screen.")
            .setPositiveButton("Open Settings") { dialog, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(activity, "Update cancelled. Install permission denied.", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    fun checkForUpdates(activity: Activity, showNoUpdateToast: Boolean = false, onLaunch: Boolean = false) {
        CoroutineScope(Dispatchers.Main).launch {
            val (isUpdateAvailable, updateInfo) = UpdateChecker.isUpdateAvailable(activity)

            if (isUpdateAvailable && updateInfo != null) {
                if (updateInfo.latestVersionCode == updateDeferredInSessionForVersionCode && !onLaunch) {
                    Timber.i("Update for version ${updateInfo.latestVersionCode} was deferred in this session. Not showing dialog again unless onLaunch or manual check.")
                    // If it's an onLaunch check, we might still want to show it once per true app launch.
                    // The current logic with static variable handles "session".
                    // For "once per day" or "once per version ever", SharedPreferences would be needed.
                    // If onLaunch is true, we bypass the deferral for this specific call.
                    // However, the user wants "only ask on next app launch" if "Later" is clicked.
                    // So, if it's onLaunch and deferred, we skip.
                    if (onLaunch) return@launch // Skip if onLaunch and already deferred this session
                }
                 // If it's a manual check (showNoUpdateToast = true) or not deferred, proceed.
                if (updateDeferredInSessionForVersionCode == updateInfo.latestVersionCode && onLaunch) {
                     Timber.d("Update for ${updateInfo.latestVersionCode} deferred this session, skipping onLaunch prompt.")
                } else {
                    if (canRequestPackageInstalls(activity)) {
                        showUpdateAvailableDialog(activity, updateInfo) {
                            downloadAndInstallUpdate(activity, updateInfo)
                        }
                    } else {
                        requestInstallPermission(activity)
                    }
                }
            } else if (updateInfo == null) {
                // Error fetching update info
                if (showNoUpdateToast && !onLaunch) { // Avoid toast on launch if error
                     Toast.makeText(activity, "Could not check for updates. Please try again later.", Toast.LENGTH_LONG).show()
                }
                 Timber.w("Update check failed: updateInfo is null")
            } else {
                // No update available or app is up-to-date
                if (showNoUpdateToast) {
                    Toast.makeText(activity, "App is up to date.", Toast.LENGTH_SHORT).show()
                }
                Timber.i("App is up to date or no new update found.")
            }

            // Optional: Handle minRequiredVersionCode for forced updates
            if (updateInfo?.minRequiredVersionCode != null && updateInfo.minRequiredVersionCode!! > UpdateChecker.getCurrentVersionCode(activity)) {
                // Implement logic for forced update if needed, e.g., a non-cancelable dialog
                Timber.w("Minimum required version (${updateInfo.minRequiredVersionCode}) is higher than current (${UpdateChecker.getCurrentVersionCode(activity)}). Consider a forced update.")
                // For now, we just log this. You can add a more insistent dialog here.
                if (!isUpdateAvailable) { // If update wasn't already shown (e.g. same version but minRequired changed)
                     showUpdateAvailableDialog(activity, updateInfo) { // Re-show dialog, maybe with different text
                        downloadAndInstallUpdate(activity, updateInfo)
                    }
                }
            }
        }
    }
}
