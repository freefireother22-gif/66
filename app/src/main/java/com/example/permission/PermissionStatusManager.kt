package com.example.permission

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.model.PairingRecord
import com.example.model.ChildDeviceReport
import com.example.model.ChildPermissionsState
import com.example.receiver.ChildDeviceAdminReceiver
import com.example.service.ChildAccessibilityService
import com.example.service.ChildNotificationListenerService
import com.example.service.MediaProjectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Centralized Permission Engine for Child Device.
 * Directly checks REAL Android permission and access states using official OS APIs.
 * Never uses fake enabled states or mock results.
 */
class PermissionStatusManager(private val context: Context) {

    private val _permissionsState = MutableStateFlow(ChildPermissionsState())
    val permissionsState: StateFlow<ChildPermissionsState> = _permissionsState.asStateFlow()

    // Persistent pairing and identification state (strictly separated between Parent and Child)
    private val prefs = context.getSharedPreferences("parental_control_prefs", Context.MODE_PRIVATE)

    var parentAccountId: String = prefs.getString("parent_account_id", null) ?: run {
        val id = "parent_acc_" + UUID.randomUUID().toString().replace("-", "").take(8)
        prefs.edit().putString("parent_account_id", id).apply()
        id
    }
        private set

    var parentDeviceId: String = prefs.getString("parent_device_id", null) ?: run {
        val id = "parent_dev_" + UUID.randomUUID().toString().replace("-", "").take(8)
        prefs.edit().putString("parent_device_id", id).apply()
        id
    }
        private set

    var childDeviceId: String = prefs.getString("child_device_id", null) ?: run {
        val id = "child_dev_" + UUID.randomUUID().toString().replace("-", "").take(8)
        prefs.edit().putString("child_device_id", id).apply()
        id
    }
        private set

    var pairingId: String = prefs.getString("pairing_id", "") ?: ""
        private set

    var isPairingRegistered: Boolean = prefs.getBoolean("is_pairing_registered", false) && pairingId.isNotBlank()
        private set

    var sessionId: String = UUID.randomUUID().toString().take(8)
        private set

    fun updatePairing(
        newPairingId: String,
        newParentAccountId: String,
        newParentDeviceId: String,
        newChildDeviceId: String? = null,
        registered: Boolean = false
    ) {
        pairingId = newPairingId
        parentAccountId = newParentAccountId
        parentDeviceId = newParentDeviceId
        if (!newChildDeviceId.isNullOrBlank()) {
            childDeviceId = newChildDeviceId
            prefs.edit().putString("child_device_id", newChildDeviceId).apply()
        }
        isPairingRegistered = registered && newPairingId.isNotBlank()
        sessionId = UUID.randomUUID().toString().take(8)
        prefs.edit()
            .putString("pairing_id", newPairingId)
            .putString("parent_account_id", newParentAccountId)
            .putString("parent_device_id", newParentDeviceId)
            .putBoolean("is_pairing_registered", isPairingRegistered)
            .apply()
    }

    fun updatePairing(pairing: PairingRecord, registered: Boolean = false) {
        updatePairing(
            newPairingId = pairing.pairingId,
            newParentAccountId = pairing.parentAccountId,
            newParentDeviceId = pairing.parentDeviceId,
            newChildDeviceId = pairing.childDeviceId,
            registered = registered
        )
    }

    fun setPairingRegistered(registered: Boolean) {
        isPairingRegistered = registered && pairingId.isNotBlank()
        prefs.edit().putBoolean("is_pairing_registered", isPairingRegistered).apply()
    }

    fun updateChildDeviceId(newChildDeviceId: String) {
        if (newChildDeviceId.isNotBlank()) {
            childDeviceId = newChildDeviceId
            prefs.edit().putString("child_device_id", newChildDeviceId).apply()
        }
    }

    fun clearPairing() {
        pairingId = ""
        isPairingRegistered = false
        prefs.edit()
            .remove("pairing_id")
            .putBoolean("is_pairing_registered", false)
            .apply()
    }

    fun resetAllState() {
        prefs.edit().clear().apply()
        pairingId = ""
        isPairingRegistered = false
        parentAccountId = "parent_acc_" + UUID.randomUUID().toString().replace("-", "").take(8)
        parentDeviceId = "parent_dev_" + UUID.randomUUID().toString().replace("-", "").take(8)
        childDeviceId = "child_dev_" + UUID.randomUUID().toString().replace("-", "").take(8)
        sessionId = UUID.randomUUID().toString().take(8)
        prefs.edit()
            .putString("parent_account_id", parentAccountId)
            .putString("parent_device_id", parentDeviceId)
            .putString("child_device_id", childDeviceId)
            .putString("pairing_id", "")
            .putBoolean("is_pairing_registered", false)
            .apply()
    }

    // In-memory token state for active MediaProjection consent
    private var mediaProjectionConsentActive: Boolean = false

    init {
        refreshAllPermissions()
    }

    /**
     * Re-evaluates every permission directly against Android OS APIs.
     * Invoked on app launch and whenever user returns from Android Settings.
     */
    fun refreshAllPermissions(): ChildPermissionsState {
        val accessibility = isAccessibilityServiceEnabled()
        val overlay = isOverlayPermissionGranted()
        val usageAccess = isUsageAccessGranted()
        val notificationAccess = isNotificationListenerGranted()
        val (locationPerm, locationServices) = checkLocationState()
        val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val deviceAdmin = isDeviceAdminActive()
        val batteryIgnored = isBatteryOptimizationIgnored()
        val autoStartConfigured = checkAutoStartStatus()
        val mediaProjection = mediaProjectionConsentActive || MediaProjectionService.isStreaming.value

        val newState = ChildPermissionsState(
            isAccessibilityGranted = accessibility,
            isOverlayGranted = overlay,
            isMediaProjectionGranted = mediaProjection,
            isUsageAccessGranted = usageAccess,
            isNotificationAccessGranted = notificationAccess,
            isLocationPermissionGranted = locationPerm,
            isLocationServicesEnabled = locationServices,
            isSmsReadGranted = smsGranted,
            isBackgroundLocationGranted = backgroundLocation,
            isSocialContentProtectionEnabled = accessibility,
            isWebsiteRestrictionsEnabled = accessibility,
            isDeviceAdminGranted = deviceAdmin,
            isBatteryOptimizationIgnored = batteryIgnored,
            isAutoStartConfigured = autoStartConfigured
        )

        _permissionsState.value = newState
        return newState
    }

    fun setMediaProjectionConsent(granted: Boolean) {
        mediaProjectionConsentActive = granted
        refreshAllPermissions()
    }

    // -------------------------------------------------------------
    // 1. Accessibility Service Verification
    // -------------------------------------------------------------
    fun isAccessibilityServiceEnabled(): Boolean {
        // Method 1: Check Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES string
        val expectedComponentName = ComponentName(context, ChildAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (!enabledServicesSetting.isNullOrEmpty()) {
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
        }

        // Method 2: Check AccessibilityManager enabled services list
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (enabledServices != null) {
            for (service in enabledServices) {
                val serviceInfo = service.resolveInfo?.serviceInfo
                if (serviceInfo != null &&
                    serviceInfo.packageName == context.packageName &&
                    serviceInfo.name == ChildAccessibilityService::class.java.name
                ) {
                    return true
                }
            }
        }

        // Also check runtime service singleton state
        return ChildAccessibilityService.isRunning.value
    }

    // -------------------------------------------------------------
    // 2. Overlay Permission (Display over other apps)
    // -------------------------------------------------------------
    fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    // -------------------------------------------------------------
    // 3. Usage Stats Access (PACKAGE_USAGE_STATS)
    // -------------------------------------------------------------
    fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // -------------------------------------------------------------
    // 4. Notification Listener Access
    // -------------------------------------------------------------
    fun isNotificationListenerGranted(): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        val hasPackage = enabledListeners.contains(context.packageName)
        return hasPackage || ChildNotificationListenerService.isRunning.value
    }

    // -------------------------------------------------------------
    // 5. Location Permission & Services
    // -------------------------------------------------------------
    fun checkLocationState(): Pair<Boolean, Boolean> {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val isPermissionGranted = hasFine || hasCoarse

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isGps = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val isNetwork = lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        val isServicesEnabled = isGps || isNetwork

        return Pair(isPermissionGranted, isServicesEnabled)
    }

    // -------------------------------------------------------------
    // 6. Device Admin Permission
    // -------------------------------------------------------------
    fun isDeviceAdminActive(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val adminComponent = ComponentName(context, ChildDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    // -------------------------------------------------------------
    // 7. Battery Optimization Exemption
    // -------------------------------------------------------------
    fun isBatteryOptimizationIgnored(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    // -------------------------------------------------------------
    // 8. Auto-start Status
    // -------------------------------------------------------------
    private fun checkAutoStartStatus(): Boolean {
        val prefs = context.getSharedPreferences("child_setup_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("oem_autostart_configured", false)
    }

    fun markAutoStartConfigured(configured: Boolean) {
        val prefs = context.getSharedPreferences("child_setup_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("oem_autostart_configured", configured).apply()
        refreshAllPermissions()
    }

    // -------------------------------------------------------------
    // Intent Builders for Android Settings Screens
    // -------------------------------------------------------------

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun getOverlaySettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            getAppSettingsIntent()
        }
    }

    fun getUsageAccessSettingsIntent(): Intent {
        return try {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } catch (_: Exception) {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    fun getNotificationAccessSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val component = ComponentName(context, ChildNotificationListenerService::class.java).flattenToString()
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    fun getLocationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun getDeviceAdminIntent(): Intent {
        val adminComponent = ComponentName(context, ChildDeviceAdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Authorized parental control administration to protect family supervision rules from unauthorized bypass."
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun getIgnoreBatteryOptimizationIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
        } else {
            getAppSettingsIntent()
        }
    }

    fun getAutoStartSettingsIntent(): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = listOf(
            // Xiaomi / Poco / Redmi
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // Huawei / Honor
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.bootstart.BootStartActivity")),
            // Oppo / Realme
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            // Vivo / iQOO
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            // Samsung
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"))
        )

        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved.isNotEmpty()) {
                return intent
            }
        }
        return null
    }

    fun getAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    // -------------------------------------------------------------
    // Real Device Status Report
    // -------------------------------------------------------------
    fun generateRealDeviceReport(): ChildDeviceReport {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 85
        val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bm?.isCharging == true
        } else {
            false
        }

        return ChildDeviceReport(
            parentAccountId = parentAccountId,
            parentDeviceId = parentDeviceId,
            childDeviceId = childDeviceId,
            pairingId = pairingId,
            sessionId = sessionId,
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            batteryPercentage = batteryPct,
            isCharging = isCharging,
            isOnline = true,
            permissionsState = refreshAllPermissions()
        )
    }

    fun sendHeartbeatToBackend() {
        val report = generateRealDeviceReport()
        val webRtc = com.example.webrtc.WebRtcManager.getInstance(context)
        val isScreenAuth = webRtc.hasActiveScreenCapturer() || mediaProjectionConsentActive
        val telemetry = com.example.model.ChildTelemetry(
            childDeviceId = childDeviceId,
            deviceModel = report.deviceModel,
            manufacturer = report.manufacturer,
            androidVersion = report.androidVersion,
            batteryPercentage = report.batteryPercentage,
            isCharging = report.isCharging,
            isOnline = true,
            lastSeen = System.currentTimeMillis(),
            isScreenAuthorized = isScreenAuth
        )
        com.example.backend.BackendRepository.getInstance().recordChildHeartbeat(telemetry)
    }
}
