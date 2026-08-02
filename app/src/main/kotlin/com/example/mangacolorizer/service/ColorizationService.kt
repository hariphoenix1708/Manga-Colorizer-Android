package com.example.mangacolorizer.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mangacolorizer.MainActivity
import com.example.mangacolorizer.inference.ColorizationManager
import com.example.mangacolorizer.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@AndroidEntryPoint
class ColorizationService : Service() {

    @Inject
    lateinit var manager: ColorizationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "colorization_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Logger.i("ColorizationService: Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.i("ColorizationService: onStartCommand with action=$action")
        
        when (action) {
            ACTION_START -> {
                Logger.i("ColorizationService: ACTION_START received")
                startForegroundService()
            }
            ACTION_STOP -> {
                Logger.i("ColorizationService: ACTION_STOP received. Stopping service.")
                manager.stopProcessing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        Logger.i("ColorizationService: Entering foreground state")
        val notification = createNotification("Preparing colorizer...", 0, 0)
        
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.e("ColorizationService: Failed to start foreground service", e)
        }
        
        serviceScope.launch {
            combine(
                manager.currentStatus,
                manager.processedCount,
                manager.totalInQueue,
                manager.isColorizing,
                manager.isPaused
            ) { status, processed, total, isColorizing, isPaused ->
                Logger.d("ColorizationService: Syncing notification (Status=$status, Processed=$processed, Total=$total, Active=$isColorizing, Paused=$isPaused)")
                if (isPaused || (!isColorizing && total == 0)) {
                    null // Signal termination
                } else {
                    Triple(status, processed, total)
                }
            }.collect { data ->
                if (data == null) {
                    Logger.i("ColorizationService: Conditions met for termination. Stopping.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val (status, processed, total) = data
                    updateNotification(status, processed, total)
                }
            }
        }
    }

    private fun createNotification(content: String, progress: Int, max: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Manga Colorizer Live")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true) // Prevent frequent sounds during updates
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        if (max > 0) {
            builder.setProgress(max, progress, false)
            builder.setSubText("$progress / $max images complete")
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun updateNotification(status: String, processed: Int, total: Int) {
        val notification = createNotification(status, processed, total)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Colorization Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows ongoing manga colorization progress"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("ColorizationService: App removed from recents (onTaskRemoved)")
        super.onTaskRemoved(rootIntent)
        // Note: Foreground service will continue running until completion or system kill
    }

    override fun onDestroy() {
        Logger.i("ColorizationService: Service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }
}
