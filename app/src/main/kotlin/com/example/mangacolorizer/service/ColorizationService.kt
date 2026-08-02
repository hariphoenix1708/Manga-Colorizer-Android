package com.example.mangacolorizer.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.mangacolorizer.MainActivity
import com.example.mangacolorizer.data.ProcessState
import com.example.mangacolorizer.inference.ColorizationManager
import com.example.mangacolorizer.utils.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean

@AndroidEntryPoint
class ColorizationService : Service() {

    @Inject
    lateinit var manager: ColorizationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isForegroundActive = AtomicBoolean(false)
    private var collectorJob: Job? = null

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
                // Stop processing logic is handled by UI/Manager, service just follows manager state
                // But if explicitly stopped via intent, we stop self.
                stopServiceCleanly()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        if (isForegroundActive.getAndSet(true)) {
            Logger.d("ColorizationService: Foreground service already active, skipping re-init")
            // We just ensure the collector is running
        } else {
            Logger.i("ColorizationService: Entering foreground state")
            val notification = createNotification("Preparing colorizer...", 0, 0)

            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Logger.e("ColorizationService: Failed to start foreground service", e)
            }
        }
        
        collectorJob?.cancel()
        collectorJob = serviceScope.launch {
            manager.processingState.collect { state ->
                val status = state.currentStatusText
                val processed = state.completedCount
                val total = state.totalInSession

                Logger.d("ColorizationService: Syncing notification (Status=$status, Processed=$processed, Total=$total, State=${state.processState})")

                if (state.processState == ProcessState.IDLE) {
                    Logger.i("ColorizationService: Conditions met for termination (State: ${state.processState}). Stopping.")
                    stopServiceCleanly()
                } else {
                    updateNotification(status, processed, total)
                }
            }
        }
    }

    private fun stopServiceCleanly() {
        isForegroundActive.set(false)
        collectorJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
