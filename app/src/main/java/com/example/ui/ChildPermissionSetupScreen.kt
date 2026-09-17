package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.ChildPermissionsState
import com.example.permission.PermissionStatusManager
import com.example.service.MediaProjectionService
import com.example.ui.theme.BorderLight
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandBlueDark
import com.example.ui.theme.BrandBlueLight
import com.example.ui.theme.DeviceActivityBg
import com.example.ui.theme.DeviceActivityColor
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LocationBg
import com.example.ui.theme.LocationColor
import com.example.ui.theme.ScreenMirrorBg
import com.example.ui.theme.ScreenMirrorColor
import com.example.ui.theme.SocialContentBg
import com.example.ui.theme.SocialContentColor
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenLight
import com.example.ui.theme.SyncNotificationBg
import com.example.ui.theme.SyncNotificationColor
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.UsageLimitsBg
import com.example.ui.theme.UsageLimitsColor
import com.example.ui.theme.WebsiteRestrictionBg
import com.example.ui.theme.WebsiteRestrictionColor

@Composable
fun ChildPermissionSetupScreen(
    permissionManager: PermissionStatusManager,
    onProceedToBackgroundPermissions: () -> Unit,
    onOpenParentMode: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionsState by permissionManager.permissionsState.collectAsState()

    var showHelpSheet by remember { mutableStateOf(false) }
    var showMissingRequiredDialog by remember { mutableStateOf(false) }

    // Auto refresh permissions when returning from Settings screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionManager.refreshAllPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // MediaProjection screen capture consent launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            permissionManager.setMediaProjectionConsent(true)
            // Cache the consent token in WebRtcManager so it is ready when the parent starts a stream
            val webRtc = com.example.webrtc.WebRtcManager.getInstance(context)
            webRtc.pendingProjectionResultData = result.data
            Toast.makeText(context, "Screen capture permission granted and ready", Toast.LENGTH_SHORT).show()
        } else {
            permissionManager.setMediaProjectionConsent(false)
            Toast.makeText(context, "Screen capture was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { permissionManager.refreshAllPermissions() }

    // Runtime location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.any { it }
        permissionManager.refreshAllPermissions()
        if (granted) {
            Toast.makeText(context, "Location permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Location permission required for geofencing", Toast.LENGTH_SHORT).show()
        }
    }

    val handleBack = onBack ?: onOpenParentMode
    BackHandler(onBack = handleBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("permission_setup_list"),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Top App Bar / Mode Switcher
            item {
                TopSetupHeader(
                    onBackClick = handleBack,
                    onParentModeClick = onOpenParentMode
                )
            }

            // Section 1: Usage Limits
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.HourglassEmpty,
                    iconBg = UsageLimitsBg,
                    iconTint = UsageLimitsColor,
                    title = "Usage Limits",
                    description = "Limit app usage and screen time on this device",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "Accessibility Permission",
                            detail = "Enforce configured app limits and scheduled downtime",
                            isGranted = permissionsState.isAccessibilityGranted,
                            isRequired = true,
                            onToggle = {
                                context.startActivity(permissionManager.getAccessibilitySettingsIntent())
                            }
                        ),
                        PermissionSubRowData(
                            permissionName = "Allow Display Over Other Apps",
                            detail = "Show block screens and warning overlays when limits are reached",
                            isGranted = permissionsState.isOverlayGranted,
                            isRequired = true,
                            onToggle = {
                                context.startActivity(permissionManager.getOverlaySettingsIntent())
                            }
                        )
                    )
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Section 2: Screen Mirroring
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.Cast,
                    iconBg = ScreenMirrorBg,
                    iconTint = ScreenMirrorColor,
                    title = "Screen Mirroring",
                    description = "Remotely view the screen of this device",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "Screen capture / MediaProjection permission",
                            detail = if (permissionsState.isMediaProjectionGranted) "Live screen streaming ready (Child -> Parent)" else "Tap to trigger official Android screen capture consent",
                            isGranted = permissionsState.isMediaProjectionGranted,
                            isRequired = false,
                            onToggle = {
                                if (permissionsState.isMediaProjectionGranted) {
                                    // Stop streaming
                                    val stopIntent = Intent(context, MediaProjectionService::class.java).apply {
                                        action = MediaProjectionService.ACTION_STOP
                                    }
                                    context.startService(stopIntent)
                                    permissionManager.setMediaProjectionConsent(false)
                                } else {
                                    // Launch real MediaProjection prompt
                                    val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                    mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                                }
                            }
                        )
                    )
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Section 3: Device Activity
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.Insights,
                    iconBg = DeviceActivityBg,
                    iconTint = DeviceActivityColor,
                    title = "Device Activity",
                    description = "Gain insights into your child's app usage and screen time",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "User Usage Permission",
                            detail = "Grants UsageStats access to monitor screen time and app usage",
                            isGranted = permissionsState.isUsageAccessGranted,
                            isRequired = true,
                            onToggle = {
                                context.startActivity(permissionManager.getUsageAccessSettingsIntent())
                            }
                        )
                    )
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Section 4: Sync Notifications
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.Notifications,
                    iconBg = SyncNotificationBg,
                    iconTint = SyncNotificationColor,
                    title = "Sync Notifications",
                    description = "View the app notification on your child's device in real-time",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "Access to Notifications",
                            detail = "Allows syncing safety alerts with parent in real-time",
                            isGranted = permissionsState.isNotificationAccessGranted,
                            isRequired = true,
                            onToggle = {
                                context.startActivity(permissionManager.getNotificationAccessSettingsIntent())
                            }
                        )
                    )
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Section 5: Location Tracker
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.LocationOn,
                    iconBg = LocationBg,
                    iconTint = LocationColor,
                    title = "Location Tracker",
                    description = "Find this device and set a geofence on it",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "Location Permission",
                            detail = when {
                                !permissionsState.isLocationPermissionGranted -> "Tap to grant fine & coarse location access"
                                !permissionsState.isLocationServicesEnabled -> "Permission granted, but GPS/Location is turned OFF"
                                else -> "High-accuracy device locating and geofencing enabled"
                            },
                            isGranted = permissionsState.isLocationPermissionGranted && permissionsState.isLocationServicesEnabled,
                            isRequired = true,
                            onToggle = {
                                if (!permissionsState.isLocationPermissionGranted) {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else if (!permissionsState.isLocationServicesEnabled) {
                                    context.startActivity(permissionManager.getLocationSettingsIntent())
                                } else {
                                    // Open app settings if user wishes to change
                                    context.startActivity(permissionManager.getAppSettingsIntent())
                                }
                            }
                        )
                    )
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Consent-based SMS history (private/managed distribution or default SMS role only)
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.Sms,
                    iconBg = BrandBlueLight,
                    iconTint = BrandBlue,
                    title = "SMS History",
                    description = "Sync normal SMS with the paired parent after explicit consent",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "Read SMS permission",
                            detail = "Restricted by Google Play; intended for private/managed distribution unless this app is the default SMS handler",
                            isGranted = permissionsState.isSmsReadGranted,
                            isRequired = false,
                            onToggle = { smsPermissionLauncher.launch(android.Manifest.permission.READ_SMS) }
                        )
                    )
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Section 6: Social Content Detection
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.Chat,
                    iconBg = SocialContentBg,
                    iconTint = SocialContentColor,
                    title = "Social Content Detection",
                    description = "Monitor social media apps for specific keywords",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "Accessibility Permission",
                            detail = "Shared with parental safeguard to detect configured alert keywords",
                            isGranted = permissionsState.isAccessibilityGranted,
                            isRequired = false,
                            onToggle = {
                                context.startActivity(permissionManager.getAccessibilitySettingsIntent())
                            }
                        )
                    )
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // Section 7: Website Restrictions
            item {
                PermissionFeatureSection(
                    icon = Icons.Default.Public,
                    iconBg = WebsiteRestrictionBg,
                    iconTint = WebsiteRestrictionColor,
                    title = "Website Restrictions",
                    description = "Manage website access",
                    rows = listOf(
                        PermissionSubRowData(
                            permissionName = "Accessibility Permission",
                            detail = "Shared with parental safeguard to filter harmful browser URLs",
                            isGranted = permissionsState.isAccessibilityGranted,
                            isRequired = false,
                            onToggle = {
                                context.startActivity(permissionManager.getAccessibilitySettingsIntent())
                            }
                        )
                    )
                )
            }
        }

        // Floating Help Button & Avatar Area above bottom area
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 92.dp, end = 20.dp)
        ) {
            FloatingHelpPill(
                onClick = { showHelpSheet = true }
            )
        }

        // Bottom Action Bar
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(elevation = 16.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip option for optional features
                    TextButton(
                        onClick = {
                            onProceedToBackgroundPermissions()
                        },
                        modifier = Modifier.testTag("skip_button")
                    ) {
                        Text(
                            text = "Skip Optional",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                    }

                    // Main "Done" / "Next" Button
                    Button(
                        onClick = {
                            if (permissionsState.hasCorePermissions) {
                                onProceedToBackgroundPermissions()
                            } else {
                                showMissingRequiredDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .width(160.dp)
                            .testTag("done_button")
                    ) {
                        Text(
                            text = "Done",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    // Help Bottom Sheet
    if (showHelpSheet) {
        HelpBottomSheet(
            onDismiss = { showHelpSheet = false },
            onOpenAppSettings = {
                showHelpSheet = false
                context.startActivity(permissionManager.getAppSettingsIntent())
            }
        )
    }

    // Required Permission Validation Dialog
    if (showMissingRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showMissingRequiredDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = ErrorRed,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Required Permissions Missing",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "To keep child supervision active, please grant the essential permissions:\n",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                    if (!permissionsState.isAccessibilityGranted) {
                        Text("• Accessibility Permission (Usage Limits & Safeguard)", fontSize = 13.sp, color = ErrorRed)
                    }
                    if (!permissionsState.isOverlayGranted) {
                        Text("• Display Over Other Apps (Overlay)", fontSize = 13.sp, color = ErrorRed)
                    }
                    if (!permissionsState.isUsageAccessGranted) {
                        Text("• User Usage Permission (Device Activity)", fontSize = 13.sp, color = ErrorRed)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "You can tap each switch to open the appropriate Android Settings screen.",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showMissingRequiredDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Got It")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMissingRequiredDialog = false
                        onProceedToBackgroundPermissions()
                    }
                ) {
                    Text("Continue Anyway", color = TextSecondary)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// Component: Top Setup Header
// -------------------------------------------------------------
@Composable
fun TopSetupHeader(
    onBackClick: () -> Unit,
    onParentModeClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(36.dp).testTag("child_permissions_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Child App Permissions",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Step 1 of 2: Essential Permissions",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = BrandBlue
                    )
                }
            }

            // Parent Mode Switcher button
            OutlinedButton(
                onClick = onParentModeClick,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("switch_to_parent_mode_button")
            ) {
                Text(
                    text = "Parent View",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Enable permissions to allow the parent device to monitor activity, enforce limits, and protect this phone.",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = TextSecondary,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

// -------------------------------------------------------------
// Component: Feature Section with Icon, Title, and Sub-rows
// -------------------------------------------------------------
data class PermissionSubRowData(
    val permissionName: String,
    val detail: String,
    val isGranted: Boolean,
    val isRequired: Boolean,
    val onToggle: () -> Unit
)

@Composable
fun PermissionFeatureSection(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    description: String,
    rows: List<PermissionSubRowData>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Section Header: Colorful Icon + Title + Description
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Permission Rows
        rows.forEach { row ->
            PermissionSubRow(row = row)
        }
    }
}

@Composable
fun PermissionSubRow(
    row: PermissionSubRowData
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { row.onToggle() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.permissionName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                if (row.isRequired) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Required",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BrandBlueLight)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = row.detail,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Real Permission Switch
        Switch(
            checked = row.isGranted,
            onCheckedChange = { row.onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFCBD5E1),
                uncheckedBorderColor = Color.Transparent
            ),
            modifier = Modifier.testTag("switch_${row.permissionName.replace(" ", "_").lowercase()}")
        )
    }
}

// -------------------------------------------------------------
// Component: Floating Help Pill with Avatar
// -------------------------------------------------------------
@Composable
fun FloatingHelpPill(
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color.White,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
        modifier = Modifier.testTag("floating_help_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(BrandBlueLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SupportAgent,
                    contentDescription = "Support",
                    tint = BrandBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Need more help?",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }
    }
}
