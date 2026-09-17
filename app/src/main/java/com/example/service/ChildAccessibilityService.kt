package com.example.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Child Device Accessibility Service.
 * Used exclusively for legitimate parental-control functions:
 * - Enforcing configured app/time usage limits
 * - Website URL filtering and access management
 * - Safe keyword detection in authorized apps
 */
class ChildAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChildAccessibility"
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isRunning.value = true
        Log.d(TAG, "Child Accessibility Service connected and operational")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Used legitimately for app usage limits & website restrictions as authorized
        val packageName = event.packageName?.toString() ?: return
        Log.v(TAG, "Event from package: $packageName")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Child Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        Log.d(TAG, "Child Accessibility Service destroyed")
    }
}
