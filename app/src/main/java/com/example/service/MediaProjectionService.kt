package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service for Child Device Screen Mirroring.
 * Strictly adheres to Android 14+ requirements for MediaProjection foreground service
 * and transparent parental supervision notification.
 */
class MediaProjectionService : Service() {

    companion object {
        private const val TAG = "MediaProjectionService"
        const val CHANNEL_ID = "media_projection_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_PROJECTION"
        const val ACTION_STOP = "ACTION_STOP_PROJECTION"
        const val ACTION_PAUSE = "ACTION_PAUSE_PROJECTION"
        const val ACTION_RESUME = "ACTION_RESUME_PROJECTION"
        const val ACTION_REVOKE = "ACTION_REVOKE_PROJECTION"

        const val EXTRA_PROJECTION_DATA = "EXTRA_PROJECTION_DATA"
        const val EXTRA_SESSION_ID = "EXTRA_SESSION_ID"
        const val EXTRA_TARGET_PARENT = "EXTRA_TARGET_PARENT"
        const val EXTRA_CHILD_DEVICE = "EXTRA_CHILD_DEVICE"

        private val _isStreaming = MutableStateFlow(false)
        val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

        /**
         * Starts the MediaProjection foreground service and delegates startChildScreenMirroring
         * once the service is running in foreground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
         */
        fun start(
            context: Context,
            resultData: Intent,
            sessionId: String,
            targetParentDeviceId: String,
            childDeviceId: String
        ) {
            val webRtc = com.example.webrtc.WebRtcManager.getInstance(context)
            webRtc.pendingProjectionResultData = resultData

            val serviceIntent = Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECTION_DATA, resultData)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_TARGET_PARENT, targetParentDeviceId)
                putExtra(EXTRA_CHILD_DEVICE, childDeviceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        fun stop(context: Context) {
            val serviceIntent = Intent(context, MediaProjectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(serviceIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val webRtcManager = com.example.webrtc.WebRtcManager.getInstance(applicationContext)
        val rawSessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: ""
        val rawTargetParent = intent?.getStringExtra(EXTRA_TARGET_PARENT) ?: ""
        val rawChildDevice = intent?.getStringExtra(EXTRA_CHILD_DEVICE) ?: ""

        val sessionId = rawSessionId.ifEmpty { webRtcManager.getCurrentSessionId() }
        val targetParent = rawTargetParent.ifEmpty { webRtcManager.getRemoteDeviceId() }
        val childDevice = rawChildDevice.ifEmpty { webRtcManager.getLocalDeviceId() }

        when (intent?.action) {
            ACTION_STOP, ACTION_REVOKE -> {
                webRtcManager.stopChildScreenMirroring(permanent = true)
                stopProjection()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                webRtcManager.pauseChildScreenMirroring()
                updateNotification(isLive = false)
            }
            ACTION_RESUME -> {
                startForegroundProjection(isLive = true)
                webRtcManager.resumeChildScreenMirroring(sessionId, targetParent, childDevice)
            }
            ACTION_START -> {
                // 1. Enter foreground FIRST to satisfy Android 14 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForegroundProjection(isLive = true)
                if (webRtcManager.hasActiveScreenCapturer()) {
                    webRtcManager.resumeChildScreenMirroring(sessionId, targetParent, childDevice)
                } else {
                    @Suppress("DEPRECATION")
                    val projectionData = webRtcManager.pendingProjectionResultData
                        ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                        } else {
                            intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                        }
                    if (projectionData != null) {
                        // Post to main looper so WebRTC surface setup and observer are thread-safe
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            webRtcManager.startChildScreenMirroring(
                                mediaProjectionResultData = projectionData,
                                sessionId = sessionId,
                                targetParentDeviceId = targetParent,
                                childDeviceId = childDevice
                            )
                        }
                    } else {
                        Log.e(TAG, "No projectionData found to start screen capture!")
                    }
                }
            }
            else -> {
                startForegroundProjection(isLive = webRtcManager.isScreenMirroringActive.value)
            }
        }
        return START_STICKY
    }

    private fun startForegroundProjection(isLive: Boolean = true) {
        val notification = buildNotification(isLive)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                val hasCameraPermission = ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (hasAudioPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (hasCameraPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, types)
                } catch (se: SecurityException) {
                    Log.w(TAG, "SecurityException starting FGS with multiple types, falling back to MEDIA_PROJECTION: ${se.message}")
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _isStreaming.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground media projection service: ${e.message}", e)
        }
    }

    private fun updateNotification(isLive: Boolean) {
        val notification = buildNotification(isLive)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopProjection() {
        _isStreaming.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Mirroring Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Persistent notice alerting the child when screen monitoring is active"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isLive: Boolean = true): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (isLive) "Screen Monitoring Active" else "Screen Monitor Standby (Authorized)"
        val text = if (isLive) {
            "Screen monitoring is active. Your screen is currently being shared with your parent."
        } else {
            "One-time permission granted. Ready for parent monitoring."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.example.R.drawable.ic_screen_mirror)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isStreaming.value = false
    }
}
