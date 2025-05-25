package com.velvettouch.nosafeword

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.velvettouch.nosafeword.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val userRepository by lazy { UserRepository() }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("New FCM Token: $token")
        sendRegistrationToServer(token)
    }

    private fun sendRegistrationToServer(token: String?) {
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            if (token != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val result = userRepository.updateFcmToken(uid, token)
                    if (result.isSuccess) {
                        Timber.d("FCM token updated successfully for user $uid")
                    } else {
                        Timber.e(result.exceptionOrNull(), "Failed to update FCM token for user $uid")
                    }
                }
            }
        } ?: Timber.w("Cannot send FCM token to server: User not logged in.")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.d("From: ${remoteMessage.from}")

        // Check if message contains a data payload.
        remoteMessage.data.isNotEmpty().let {
            Timber.d("Message data payload: " + remoteMessage.data)
            // Handle data payload if needed, e.g., for specific actions or silent updates
        }

        // Check if message contains a notification payload.
        remoteMessage.notification?.let {
            Timber.d("Message Notification Body: ${it.body}")
            sendNotification(it.title, it.body)
        }
    }

    private fun sendNotification(messageTitle: String?, messageBody: String?) {
        val intent = Intent(this, TaskListActivity::class.java) // Or MainActivity, depending on desired landing
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0 /* Request code */, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = getString(R.string.default_notification_channel_id)
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_tasks_24) // Replace with your app's notification icon
            .setContentTitle(messageTitle ?: getString(R.string.app_name))
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)


        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Since android Oreo notification channel is needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Task Notifications", // User-visible name of the channel.
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0 /* ID of notification */, notificationBuilder.build())
    }
}
