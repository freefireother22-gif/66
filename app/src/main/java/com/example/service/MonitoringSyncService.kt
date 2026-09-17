package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.backend.FirebaseMonitoringRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringSyncService : Service() {
    companion object {
        private const val CHANNEL = "authorized_monitoring_sync"
        private const val NOTIFICATION_ID = 2402
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitoringSyncService::class.java))
        }
        fun stop(context: Context) { context.stopService(Intent(context, MonitoringSyncService::class.java)) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Child monitoring active", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_screen_mirror)
            .setContentTitle("Parental controls active")
            .setContentText("Location, screen-time and permitted message data are syncing")
            .setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (syncJob?.isActive != true) {
            syncJob = scope.launch {
                val prefs = getSharedPreferences("parental_control_prefs", MODE_PRIVATE)
                val repo = FirebaseMonitoringRepository.getInstance(applicationContext)
                while (isActive) {
                    val pairingId = prefs.getString("pairing_id", "").orEmpty()
                    val childId = prefs.getString("child_device_id", "").orEmpty()
                    if (pairingId.isNotBlank() && childId.isNotBlank()) runCatching { repo.syncChild(pairingId, childId) }
                    delay(60_000L)
                }
            }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
