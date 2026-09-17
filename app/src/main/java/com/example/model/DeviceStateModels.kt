package com.example.model

/**
 * Real permission states evaluated directly via Android APIs.
 * NO fake enabled states or local mock booleans.
 */
data class ChildPermissionsState(
    // Section 1: Usage Limits
    val isAccessibilityGranted: Boolean = false,
    val isOverlayGranted: Boolean = false,

    // Section 2: Screen Mirroring
    val isMediaProjectionGranted: Boolean = false,

    // Section 3: Device Activity
    val isUsageAccessGranted: Boolean = false,

    // Section 4: Sync Notifications
    val isNotificationAccessGranted: Boolean = false,

    // Section 5: Location Tracker
    val isLocationPermissionGranted: Boolean = false,
    val isLocationServicesEnabled: Boolean = false,
    val isSmsReadGranted: Boolean = false,
    val isBackgroundLocationGranted: Boolean = false,

    // Section 6 & 7: Social Content Detection & Website Restrictions
    // Both utilize AccessibilityService as requested
    val isSocialContentProtectionEnabled: Boolean = false,
    val isWebsiteRestrictionsEnabled: Boolean = false,

    // Final Background / Running Permissions
    val isDeviceAdminGranted: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isAutoStartConfigured: Boolean = false
) {
    /**
     * Checks if the core required permissions for basic protection are granted.
     * Core required: Accessibility, Overlay, Usage Access, Notification Access, Location.
     */
    val hasCorePermissions: Boolean
        get() = isAccessibilityGranted && isOverlayGranted && isUsageAccessGranted

    val allEssentialGranted: Boolean
        get() = isAccessibilityGranted && isOverlayGranted && isUsageAccessGranted &&
                isNotificationAccessGranted && isLocationPermissionGranted
}

/**
 * Child device info for reporting to parent.
 * Strictly maintains separation of identifiers.
 */
data class ChildDeviceReport(
    val parentAccountId: String,
    val parentDeviceId: String,
    val childDeviceId: String,
    val pairingId: String,
    val sessionId: String,
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val batteryPercentage: Int,
    val isCharging: Boolean,
    val isOnline: Boolean,
    val permissionsState: ChildPermissionsState
)
