package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Child Device Notification Listener Service.
 * Used for syncing authorized app notifications with the Parent application.
 */
class ChildNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ChildNotificationSync"
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isRunning.value = true
        Log.d(TAG, "Child Notification Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isRunning.value = false
        Log.d(TAG, "Child Notification Listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        Log.v(TAG, "Notification received from: $pkg")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Notification dismissed
    }
}
