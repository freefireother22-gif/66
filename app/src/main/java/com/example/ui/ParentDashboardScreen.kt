package com.example.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.backend.BackendRepository
import com.example.backend.FirebaseMonitoringRepository
import com.example.permission.PermissionStatusManager
import com.example.service.MediaProjectionService
import com.example.ui.theme.AppBottomNavigationBar
import com.example.ui.theme.AppBottomNavTab
import com.example.ui.theme.AppCard
import com.example.ui.theme.CardBackground
import com.example.ui.theme.ChildProfileCard
import com.example.ui.theme.DarkNavy
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.MainBackground
import com.example.ui.theme.NeutralDivider
import com.example.ui.theme.OverviewMetricCard
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.QuickActionCard
import com.example.ui.theme.RecentActivityItem
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SectionHeader
import com.example.ui.theme.SoftBlueBorder
import com.example.ui.theme.SoftBlueSurface
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.UpgradeToProBanner
import com.example.webrtc.WebRtcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Redesigned Parent Dashboard Screen matching Reference Designs (Screen 2):
 * - Top Greeting:
 *   "Good Morning,"
 *   "Parent 👋"
 *   "Here's what's happening with your child today."
 *   Avatar on right
 * - Child Profile Card (Alex, RMX2030, 96% Battery)
 * - Quick Action Grid:
 *   Row 1: [ Live Screen (Eye) ] [ Location (Pin) ] [ App Usage (Apps) ]
 *   Row 2: [ Everything looks good! (Wide check card) ] [ Settings (Gear) ]
 * - Today's Overview: Screen Time (2h 34m), Apps Used (12), Locations (3)
 * - Recent Activity: YouTube, Instagram, WhatsApp
 * - Bottom Navigation Bar: Home, Devices, Family, Settings
 * - Full WebRTC Live Screen mirror and diagnostics preserved completely
 */
@Composable
fun ParentDashboardScreen(
    permissionManager: PermissionStatusManager,
    onBackToRoleSelection: () -> Unit,
    onNavigateToConnectChildOtp: () -> Unit = {},
    onNavigateToCheckBinding: (String) -> Unit,
    onNavigateToMonitoring: () -> Unit = {}
) {
    val context = LocalContext.current
    val backend = remember { BackendRepository.getInstance() }
    val webRtcManager = remember { WebRtcManager.getInstance(context) }
    val signalingEngine = remember { com.example.signaling.SignalingEngine.getInstance() }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val permissionsState by permissionManager.permissionsState.collectAsState()
    val activePairing by backend.currentPairing.collectAsState()
    val latestTelemetry by backend.latestTelemetry.collectAsState()
    val parentEmail by backend.parentUserEmail.collectAsState()
    val parentName by backend.parentUserName.collectAsState()

    // WebRTC remote stream states
    val remoteVideoTrack by webRtcManager.activeVideoTrack.collectAsState()
    val isScreenStreaming by webRtcManager.isScreenMirroringActive.collectAsState()
    val isMicMonitoring by webRtcManager.isMicrophoneMonitoringActive.collectAsState()
    val rtcConnectionState by webRtcManager.connectionStateDescription.collectAsState()

    val childDeviceId = activePairing?.childDeviceId ?: permissionManager.childDeviceId
    val parentDeviceId = permissionManager.parentDeviceId
    val parentAccountId = permissionManager.parentAccountId

    // Navigation & Sheet States
    var currentTab by remember { mutableStateOf(AppBottomNavTab.HOME) }
    var isFullScreenMirrorOpen by remember { mutableStateOf(false) }
    var showProSubscriptionSheet by remember { mutableStateOf(false) }
    var showAppUsageDetail by remember { mutableStateOf(false) }
    var showContentFilterDetail by remember { mutableStateOf(false) }
    var showOtpDialog by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    // OTP Input State
    var otpInput by remember { mutableStateOf("") }
    var isVerifyingOtp by remember { mutableStateOf(false) }
    var otpErrorMessage by remember { mutableStateOf<String?>(null) }

    // Resolve Child Name and Device info
    val childDisplayName = activePairing?.childDeviceName?.ifBlank {
        activePairing?.childModel?.ifBlank { "Alex" }
    } ?: "Alex"

    val childDeviceModel = activePairing?.childModel?.ifBlank {
        latestTelemetry?.deviceModel?.ifBlank { "RMX2030" }
    } ?: "RMX2030"

    val isOnline = latestTelemetry?.isOnline ?: activePairing?.isOnline ?: true
    val batteryPct = latestTelemetry?.batteryPercentage ?: activePairing?.batteryPct ?: 96
    val isCharging = latestTelemetry?.isCharging ?: activePairing?.isCharging ?: true

    // Initialize signaling connection
    LaunchedEffect(parentDeviceId, activePairing?.pairingId) {
        if (parentDeviceId.isNotEmpty()) {
            webRtcManager.setLocalDeviceId(parentDeviceId)
            signalingEngine.registerDevice(parentDeviceId)
            val sessId = activePairing?.pairingId ?: permissionManager.pairingId
            if (sessId.isNotEmpty()) {
                signalingEngine.joinSession(sessId)
            }
        }
    }

    // Telemetry polling loop
    LaunchedEffect(activePairing?.pairingId) {
        val pairingId = activePairing?.pairingId ?: return@LaunchedEffect
        signalingEngine.registerDevice(parentDeviceId)
        signalingEngine.joinSession(pairingId)
        while (isActive) {
            withContext(Dispatchers.IO) {
                backend.syncTelemetryFromCloud(pairingId)
            }
            delay(4000)
        }
    }

    // Synchronize pairing state
    LaunchedEffect(activePairing) {
        val pairing = activePairing ?: return@LaunchedEffect
        if (permissionManager.pairingId != pairing.pairingId) {
            permissionManager.updatePairing(pairing, registered = permissionManager.isPairingRegistered)
        }
    }

    // Restore pairing if saved
    LaunchedEffect(permissionManager.pairingId) {
        if (activePairing == null && permissionManager.pairingId.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val restored = backend.getResolvedPairing(permissionManager.pairingId)
                    ?: backend.checkBindingStatus(permissionManager.pairingId).pairing
                    ?: backend.fetchPairingFromCloud(permissionManager.pairingId)
                if (restored != null) {
                    backend.updateCurrentPairing(restored)
                }
            }
        }
    }

    // Local screen capture launcher for testing
    val parentMediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val pairingId = activePairing?.pairingId ?: permissionManager.pairingId
            val sessId = pairingId.ifEmpty { "pair_" + childDeviceId.takeLast(6) }
            MediaProjectionService.start(
                context = context,
                resultData = result.data!!,
                sessionId = sessId,
                targetParentDeviceId = parentDeviceId,
                childDeviceId = childDeviceId
            )
            Toast.makeText(context, "Local screen capture active and streaming", Toast.LENGTH_SHORT).show()
        }
    }

    var isCameraModeActive by remember { mutableStateOf(false) }

    fun startLiveScreenMirror() {
        isCameraModeActive = false
        val pairingId = activePairing?.pairingId ?: permissionManager.pairingId
        val sessId = pairingId.ifEmpty { "pair_" + childDeviceId.takeLast(6) }
        webRtcManager.startParentScreenSession(
            sessionId = sessId,
            targetChildDeviceId = childDeviceId,
            parentDeviceId = parentDeviceId
        )
        isFullScreenMirrorOpen = true
    }

    fun startLiveRemoteCamera() {
        isCameraModeActive = true
        val pairingId = activePairing?.pairingId ?: permissionManager.pairingId
        val sessId = pairingId.ifEmpty { "pair_" + childDeviceId.takeLast(6) }
        webRtcManager.startParentCameraSession(
            sessionId = sessId,
            targetChildDeviceId = childDeviceId,
            parentDeviceId = parentDeviceId
        )
        isFullScreenMirrorOpen = true
    }

    fun verifyChildOtp(code: String) {
        val clean = code.replace("\\s".toRegex(), "").trim()
        if (clean.length != 6 || !clean.all { it.isDigit() }) {
            otpErrorMessage = "Please enter all 6 digits of the Child OTP."
            return
        }
        isVerifyingOtp = true
        otpErrorMessage = null
        keyboardController?.hide()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                backend.verifyAndBindParent(
                    otpInput = clean,
                    parentAccountId = parentAccountId,
                    parentDeviceId = parentDeviceId
                )
            }
            result.fold(
                onSuccess = { pairing ->
                    isVerifyingOtp = false
                    otpInput = ""
                    showOtpDialog = false
                    permissionManager.updatePairing(
                        newPairingId = pairing.pairingId,
                        newParentAccountId = pairing.parentAccountId,
                        newParentDeviceId = pairing.parentDeviceId,
                        newChildDeviceId = pairing.childDeviceId
                    )
                    signalingEngine.registerDevice(parentDeviceId)
                    signalingEngine.joinSession(pairing.pairingId)
                    scope.launch {
                        runCatching {
                            val ok = FirebaseMonitoringRepository.getInstance(context).registerPairingRole(
                                pairing.pairingId, "parent", pairing.childDeviceId, pairing.parentDeviceId
                            )
                            if (ok) {
                                permissionManager.setPairingRegistered(true)
                            }
                        }
                    }
                    Toast.makeText(context, "Child device connected successfully!", Toast.LENGTH_SHORT).show()
                },
                onFailure = { error ->
                    isVerifyingOtp = false
                    otpErrorMessage = error.message ?: "Invalid or Expired OTP"
                }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MainBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Main Content Area based on Tab / Sub-screen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    showAppUsageDetail -> {
                        AppUsageDetailScreen(
                            onBack = { showAppUsageDetail = false }
                        )
                    }
                    showContentFilterDetail -> {
                        ContentFilterScreen(
                            onBack = { showContentFilterDetail = false }
                        )
                    }
                    currentTab == AppBottomNavTab.DEVICES -> {
                        ChildDeviceDetailScreen(
                            childName = childDisplayName,
                            deviceModel = childDeviceModel,
                            batteryPercentage = batteryPct,
                            isCharging = isCharging,
                            isOnline = isOnline,
                            onBack = { currentTab = AppBottomNavTab.HOME },
                            onLaunchLiveMirror = { startLiveScreenMirror() },
                            onViewAppUsage = { showAppUsageDetail = true },
                            onViewLocation = { currentTab = AppBottomNavTab.FAMILY }
                        )
                    }
                    currentTab == AppBottomNavTab.FAMILY -> {
                        ParentMonitoringScreen(
                            permissionManager = permissionManager,
                            initialPairing = activePairing,
                            onBack = { currentTab = AppBottomNavTab.HOME }
                        )
                    }
                    currentTab == AppBottomNavTab.SETTINGS -> {
                        ParentSettingsScreen(
                            childName = childDisplayName,
                            deviceModel = childDeviceModel,
                            onBack = { currentTab = AppBottomNavTab.HOME },
                            onOpenContentFilter = { showContentFilterDetail = true },
                            onOpenLocation = { currentTab = AppBottomNavTab.FAMILY },
                            onOpenAppUsage = { showAppUsageDetail = true },
                            onPairNewChild = { showOtpDialog = true },
                            onSwitchRole = onBackToRoleSelection
                        )
                    }
                    else -> {
                        // HOME TAB: Dashboard matching Screen 2 in Reference Image
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Spacer(modifier = Modifier.height(14.dp))

                            // 1. Top Greeting Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Good Morning,",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DarkNavy
                                    )
                                    Text(
                                        text = "Parent 👋",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DarkNavy
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Here's what's happening with your child today.",
                                        fontSize = 12.sp,
                                        color = SecondaryText
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Parent Profile Avatar Icon
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(SoftBlueSurface)
                                        .border(1.5.dp, SoftBlueBorder, CircleShape)
                                        .clickable { showOptionsMenu = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Parent Profile",
                                        tint = PrimaryBlue,
                                        modifier = Modifier.size(22.dp)
                                    )

                                    DropdownMenu(
                                        expanded = showOptionsMenu,
                                        onDismissRequest = { showOptionsMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Pair Child via OTP") },
                                            onClick = {
                                                showOptionsMenu = false
                                                showOtpDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Check Binding Status") },
                                            onClick = {
                                                showOptionsMenu = false
                                                onNavigateToCheckBinding(activePairing?.pairingId ?: "")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Switch to Child Mode") },
                                            onClick = {
                                                showOptionsMenu = false
                                                onBackToRoleSelection()
                                            }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // 2. Child Profile Card (Alex, RMX2030, 96% Battery 🔋)
                            ChildProfileCard(
                                childName = childDisplayName,
                                deviceModel = childDeviceModel,
                                isOnline = isOnline,
                                batteryPercentage = batteryPct,
                                isCharging = isCharging,
                                onClick = { currentTab = AppBottomNavTab.DEVICES },
                                modifier = Modifier.testTag("dashboard_child_profile_card")
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            // Upgrade to Pro Subscription Banner (matching user request)
                            UpgradeToProBanner(
                                onUpgradeClick = { showProSubscriptionSheet = true },
                                modifier = Modifier.testTag("dashboard_upgrade_to_pro_banner")
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            // 3. Quick Monitoring Tools (Screen 2: Remote Camera, Screen Mirroring, One-Way Audio)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                QuickActionCard(
                                    title = "Remote Camera",
                                    subtitle = "Live View",
                                    painter = painterResource(id = R.drawable.ic_quick_camera),
                                    onClick = { startLiveRemoteCamera() },
                                    modifier = Modifier.weight(1f).testTag("action_remote_camera")
                                )

                                QuickActionCard(
                                    title = "Screen Mirroring",
                                    subtitle = "Watch Screen",
                                    painter = painterResource(id = R.drawable.ic_quick_screen_mirror),
                                    onClick = { startLiveScreenMirror() },
                                    modifier = Modifier.weight(1f).testTag("action_screen_mirroring")
                                )

                                QuickActionCard(
                                    title = "One-Way Audio",
                                    subtitle = "Listen In",
                                    painter = painterResource(id = R.drawable.ic_quick_one_way_audio),
                                    onClick = {
                                        val pairingId = activePairing?.pairingId ?: permissionManager.pairingId
                                        val sessId = pairingId.ifEmpty { "pair_" + childDeviceId.takeLast(6) }
                                        webRtcManager.startParentAudioSession(
                                            sessionId = sessId,
                                            targetChildDeviceId = childDeviceId,
                                            parentDeviceId = parentDeviceId
                                        )
                                        isFullScreenMirrorOpen = true
                                    },
                                    modifier = Modifier.weight(1f).testTag("action_one_way_audio")
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // 4. Activity & Location Grid (Location, App Usage, Web Filter)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                QuickActionCard(
                                    title = "Location",
                                    subtitle = "Live GPS",
                                    icon = Icons.Default.LocationOn,
                                    iconColor = SuccessGreen,
                                    iconBackground = Color(0xFFECFDF5),
                                    onClick = { currentTab = AppBottomNavTab.FAMILY },
                                    modifier = Modifier.weight(1f).testTag("action_location")
                                )

                                QuickActionCard(
                                    title = "App Usage",
                                    subtitle = "Daily Limits",
                                    icon = Icons.Default.Apps,
                                    iconColor = Color(0xFF8B5CF6),
                                    iconBackground = Color(0xFFF5F3FF),
                                    onClick = { showAppUsageDetail = true },
                                    modifier = Modifier.weight(1f).testTag("action_app_usage")
                                )

                                QuickActionCard(
                                    title = "Web Filter",
                                    subtitle = "Safe Search",
                                    icon = Icons.Default.Security,
                                    iconColor = PrimaryBlue,
                                    iconBackground = Color(0xFFEFF6FF),
                                    onClick = { showContentFilterDetail = true },
                                    modifier = Modifier.weight(1f).testTag("action_web_filter")
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Row 2: Everything looks good! (2fr) + Settings (1fr)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // "Everything looks good!" wide card (2 columns wide)
                                AppCard(
                                    modifier = Modifier.weight(2f),
                                    backgroundColor = CardBackground
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFECFDF5)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = SuccessGreen,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Everything looks good!",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = DarkNavy
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "No risky activity detected.",
                                                fontSize = 11.sp,
                                                color = SecondaryText
                                            )
                                        }
                                    }
                                }

                                // Settings action card (1 column wide)
                                QuickActionCard(
                                    title = "Settings",
                                    subtitle = "Manage",
                                    icon = Icons.Default.Settings,
                                    iconColor = DarkNavy,
                                    iconBackground = Color(0xFFF1F5F9),
                                    onClick = { currentTab = AppBottomNavTab.SETTINGS },
                                    modifier = Modifier.weight(1f).testTag("action_settings")
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // 4. Today's Overview Section (Screen 2: Screen Time, Apps Used, Locations)
                            SectionHeader(
                                title = "Today's Overview",
                                actionText = "View all",
                                onActionClick = { showAppUsageDetail = true }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OverviewMetricCard(
                                    label = "Screen Time",
                                    value = "2h 34m",
                                    trend = "↓ 18%",
                                    trendPositive = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OverviewMetricCard(
                                    label = "Apps Used",
                                    value = "12",
                                    trend = "↓ 25%",
                                    trendPositive = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OverviewMetricCard(
                                    label = "Locations",
                                    value = "3",
                                    trend = "Safe",
                                    trendPositive = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(22.dp))

                            // 5. Recent Activity Section (Screen 2: YouTube, Instagram, WhatsApp)
                            SectionHeader(
                                title = "Recent Activity"
                            )

                            AppCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = CardBackground
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    RecentActivityItem(
                                        appName = "YouTube",
                                        categoryOrAction = "Browsing",
                                        durationText = "1h 12m",
                                        timeText = "10:24 AM",
                                        iconColor = Color(0xFFFF0000),
                                        iconVector = Icons.Default.PlayArrow
                                    )
                                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)

                                    RecentActivityItem(
                                        appName = "Instagram",
                                        categoryOrAction = "Chatting",
                                        durationText = "24m",
                                        timeText = "09:45 AM",
                                        iconColor = Color(0xFFE1306C),
                                        iconVector = Icons.Default.CameraAlt
                                    )
                                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)

                                    RecentActivityItem(
                                        appName = "WhatsApp",
                                        categoryOrAction = "Social",
                                        durationText = "18m",
                                        timeText = "08:32 AM",
                                        iconColor = Color(0xFF25D366),
                                        iconVector = Icons.Default.PlayArrow
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(28.dp))
                        }
                    }
                }
            }

            // Bottom Navigation Bar matching reference
            AppBottomNavigationBar(
                currentTab = currentTab,
                onTabSelected = { tab ->
                    showAppUsageDetail = false
                    showContentFilterDetail = false
                    currentTab = tab
                }
            )
        }

        // Connect Child OTP Dialog
        if (showOtpDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!isVerifyingOtp) showOtpDialog = false
                },
                title = {
                    Text(
                        text = "Connect Child Device",
                        fontWeight = FontWeight.Bold,
                        color = DarkNavy
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Enter the 6-digit OTP displayed on the child's app to establish connection.",
                            fontSize = 13.sp,
                            color = SecondaryText
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = otpInput,
                            onValueChange = {
                                if (it.length <= 6) {
                                    otpInput = it
                                    otpErrorMessage = null
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("parent_otp_input_field"),
                            textStyle = TextStyle(
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                letterSpacing = 4.sp
                            ),
                            placeholder = {
                                Text(
                                    text = "123456",
                                    fontSize = 22.sp,
                                    color = Color(0xFFCBD5E1),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { verifyChildOtp(otpInput) }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = SoftBlueBorder
                            )
                        )

                        if (otpErrorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = otpErrorMessage ?: "",
                                color = ErrorRed,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { verifyChildOtp(otpInput) },
                        enabled = !isVerifyingOtp && otpInput.trim().length == 6,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        if (isVerifyingOtp) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text("Connect Device", fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showOtpDialog = false },
                        enabled = !isVerifyingOtp
                    ) {
                        Text("Cancel", color = SecondaryText)
                    }
                }
            )
        }

        // Pro Subscription Bottom Sheet
        if (showProSubscriptionSheet) {
            SubscriptionProSheet(
                onDismiss = { showProSubscriptionSheet = false }
            )
        }

        // Full Screen Live Mirror Watching Dialog (WebRTC)
        if (isFullScreenMirrorOpen) {
            FullScreenMirrorWatchingDialog(
                childName = childDisplayName,
                childDeviceId = childDeviceId,
                videoTrack = remoteVideoTrack,
                eglBase = webRtcManager.rootEglBase,
                isAudioMonitoring = isMicMonitoring,
                connectionState = rtcConnectionState,
                isCameraMode = isCameraModeActive,
                onSwitchCamera = {
                    webRtcManager.switchParentCamera()
                },
                onToggleAudio = {
                    val pairingId = activePairing?.pairingId ?: permissionManager.pairingId
                    val sessId = pairingId.ifEmpty { "pair_" + childDeviceId.takeLast(6) }
                    if (isMicMonitoring) {
                        webRtcManager.stopParentAudioSession()
                    } else {
                        webRtcManager.startParentAudioSession(
                            sessionId = sessId,
                            targetChildDeviceId = childDeviceId,
                            parentDeviceId = parentDeviceId
                        )
                    }
                },
                onRefreshStream = {
                    val pairingId = activePairing?.pairingId ?: permissionManager.pairingId
                    val sessId = pairingId.ifEmpty { "pair_" + childDeviceId.takeLast(6) }
                    if (isCameraModeActive) {
                        webRtcManager.startParentCameraSession(
                            sessionId = sessId,
                            targetChildDeviceId = childDeviceId,
                            parentDeviceId = parentDeviceId
                        )
                    } else {
                        webRtcManager.startParentScreenSession(
                            sessionId = sessId,
                            targetChildDeviceId = childDeviceId,
                            parentDeviceId = parentDeviceId
                        )
                    }
                },
                onStopMirror = {
                    webRtcManager.stopParentSession()
                    isFullScreenMirrorOpen = false
                },
                onDismiss = {
                    webRtcManager.stopParentSession()
                    isFullScreenMirrorOpen = false
                },
                onAuthorizeLocalScreen = {
                    val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                    mpManager?.let {
                        try {
                            parentMediaProjectionLauncher.launch(it.createScreenCaptureIntent())
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not launch screen capture: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                isChildOnline = activePairing?.isOnline ?: false,
                isChildScreenAuthorized = activePairing?.isScreenAuthorized ?: false,
                onAlertChild = {
                    val pairingId = activePairing?.pairingId ?: permissionManager.pairingId
                    val sessId = pairingId.ifEmpty { "pair_" + childDeviceId.takeLast(6) }
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = sessId,
                            senderDeviceId = parentDeviceId,
                            targetDeviceId = childDeviceId,
                            messageType = com.example.model.SignalingType.ALERT_CHILD_PROMPT,
                            payload = "ALERT_BUZZ"
                        )
                    )
                    Toast.makeText(context, "Sent screen share alert to child device", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
