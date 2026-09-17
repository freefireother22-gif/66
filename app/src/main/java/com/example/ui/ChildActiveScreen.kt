package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.example.backend.BackendRepository
import com.example.permission.PermissionStatusManager
import com.example.service.MediaProjectionService
import com.example.service.MonitoringSyncService
import com.example.signaling.SignalingEngine
import com.example.ui.theme.BorderLight
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandBlueDark
import com.example.ui.theme.BrandBlueLight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.webrtc.WebRtcManager
import kotlinx.coroutines.delay

@Composable
fun ChildActiveScreen(
    permissionManager: PermissionStatusManager,
    onManagePermissions: () -> Unit,
    onSwitchToParentMode: () -> Unit
) {
    val context = LocalContext.current
    val webRtcManager = remember { WebRtcManager.getInstance(context) }
    val backend = remember { BackendRepository.getInstance() }
    val signalingEngine = remember { SignalingEngine.getInstance() }
    val activePairing by backend.currentPairing.collectAsState()

    val currentSessionId = activePairing?.pairingId ?: permissionManager.pairingId
    val currentParentDeviceId = activePairing?.parentDeviceId ?: permissionManager.parentDeviceId
    val currentChildDeviceId = activePairing?.childDeviceId ?: permissionManager.childDeviceId

    LaunchedEffect(currentSessionId, currentChildDeviceId) {
        if (currentChildDeviceId.isNotEmpty()) {
            webRtcManager.setLocalDeviceId(currentChildDeviceId)
            signalingEngine.registerDevice(currentChildDeviceId)
        }
        if (currentSessionId.isNotEmpty()) {
            signalingEngine.joinSession(currentSessionId)
        }
    }

    val permissionsState by permissionManager.permissionsState.collectAsState()
    val isFgStreaming by MediaProjectionService.isStreaming.collectAsState()
    val isRtcScreenActive by webRtcManager.isScreenMirroringActive.collectAsState()
    val isRtcCameraActive by webRtcManager.isCameraActive.collectAsState()
    val isRtcMicActive by webRtcManager.isMicrophoneMonitoringActive.collectAsState()
    val report = remember(permissionsState) { permissionManager.generateRealDeviceReport() }

    // Started from a visible child screen to comply with Android 15 foreground-service rules.
    LaunchedEffect(currentSessionId, permissionsState.isLocationPermissionGranted) {
        if (currentSessionId.isNotBlank() && permissionsState.isLocationPermissionGranted) {
            runCatching { MonitoringSyncService.start(context) }
        }
    }

    // Periodic heartbeat to backend so parent receives live battery % & presence
    LaunchedEffect(Unit) {
        while (isActive) {
            permissionManager.sendHeartbeatToBackend()
            delay(10_000)
        }
    }

    var pendingParentDeviceId by remember { mutableStateOf("") }
    var pendingSessionId by remember { mutableStateOf("") }
    var showScreenRequestDialog by remember { mutableStateOf(false) }

    // MediaProjection screen capture consent launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val targetSession = pendingSessionId.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
        val targetParent = pendingParentDeviceId.ifEmpty { currentParentDeviceId }.ifEmpty { webRtcManager.getRemoteDeviceId() }

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            permissionManager.setMediaProjectionConsent(true)
            showScreenRequestDialog = false

            if (currentChildDeviceId.isNotEmpty()) {
                webRtcManager.setLocalDeviceId(currentChildDeviceId)
                signalingEngine.registerDevice(currentChildDeviceId)
            }
            if (targetParent.isNotEmpty()) {
                webRtcManager.setRemoteDeviceId(targetParent)
            }
            if (targetSession.isNotEmpty()) {
                signalingEngine.joinSession(targetSession)
            }
            
            // Notify parent immediately that permission was granted
            signalingEngine.sendMessage(
                com.example.model.SignalingMessage(
                    sessionId = targetSession,
                    senderDeviceId = currentChildDeviceId,
                    targetDeviceId = targetParent,
                    messageType = com.example.model.SignalingType.SCREEN_PERMISSION_GRANTED,
                    payload = "GRANTED"
                )
            )

            // Send telemetry heartbeat updating cloud status
            permissionManager.sendHeartbeatToBackend()

            MediaProjectionService.start(
                context = context,
                resultData = result.data!!,
                sessionId = targetSession,
                targetParentDeviceId = targetParent,
                childDeviceId = currentChildDeviceId
            )
            Toast.makeText(context, "Real WebRTC screen streaming active", Toast.LENGTH_SHORT).show()
        } else {
            permissionManager.setMediaProjectionConsent(false)
            showScreenRequestDialog = false
            
            // Notify parent that permission was denied or dismissed
            signalingEngine.sendMessage(
                com.example.model.SignalingMessage(
                    sessionId = targetSession,
                    senderDeviceId = currentChildDeviceId,
                    targetDeviceId = targetParent,
                    messageType = com.example.model.SignalingType.SCREEN_PERMISSION_DENIED,
                    payload = "DENIED"
                )
            )
            permissionManager.sendHeartbeatToBackend()
            Toast.makeText(context, "Screen capture was cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Microphone permission launcher for one-way audio streaming
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val targetSession = pendingSessionId.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
        val targetParent = pendingParentDeviceId.ifEmpty { currentParentDeviceId }
        if (isGranted) {
            signalingEngine.sendMessage(
                com.example.model.SignalingMessage(
                    sessionId = targetSession,
                    senderDeviceId = currentChildDeviceId,
                    targetDeviceId = targetParent,
                    messageType = com.example.model.SignalingType.AUDIO_PERMISSION_GRANTED,
                    payload = "GRANTED"
                )
            )
            webRtcManager.startChildMicrophoneStreaming(
                sessionId = targetSession,
                targetParentDeviceId = targetParent,
                childDeviceId = currentChildDeviceId
            )
            Toast.makeText(context, "Microphone audio streaming active", Toast.LENGTH_SHORT).show()
        } else {
            signalingEngine.sendMessage(
                com.example.model.SignalingMessage(
                    sessionId = targetSession,
                    senderDeviceId = currentChildDeviceId,
                    targetDeviceId = targetParent,
                    messageType = com.example.model.SignalingType.AUDIO_PERMISSION_DENIED,
                    payload = "DENIED"
                )
            )
            Toast.makeText(context, "Audio permission is required for microphone streaming", Toast.LENGTH_SHORT).show()
        }
    }

    // Camera permission launcher for remote camera streaming
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val targetSession = pendingSessionId.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
        val targetParent = pendingParentDeviceId.ifEmpty { currentParentDeviceId }
        if (isGranted) {
            signalingEngine.sendMessage(
                com.example.model.SignalingMessage(
                    sessionId = targetSession,
                    senderDeviceId = currentChildDeviceId,
                    targetDeviceId = targetParent,
                    messageType = com.example.model.SignalingType.CAMERA_PERMISSION_GRANTED,
                    payload = "GRANTED"
                )
            )
            webRtcManager.startChildCameraStreaming(
                sessionId = targetSession,
                targetParentDeviceId = targetParent,
                childDeviceId = currentChildDeviceId
            )
            Toast.makeText(context, "Remote camera streaming active", Toast.LENGTH_SHORT).show()
        } else {
            signalingEngine.sendMessage(
                com.example.model.SignalingMessage(
                    sessionId = targetSession,
                    senderDeviceId = currentChildDeviceId,
                    targetDeviceId = targetParent,
                    messageType = com.example.model.SignalingType.CAMERA_PERMISSION_DENIED,
                    payload = "DENIED"
                )
            )
            Toast.makeText(context, "Camera permission is required for remote camera streaming", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(currentSessionId, currentChildDeviceId, currentParentDeviceId) {
        signalingEngine.incomingMessages.collect { msg ->
            if (msg.messageType == com.example.model.SignalingType.START_SCREEN || msg.messageType == com.example.model.SignalingType.ALERT_CHILD_PROMPT) {
                if (msg.senderDeviceId.isNotEmpty()) {
                    pendingParentDeviceId = msg.senderDeviceId
                    webRtcManager.setRemoteDeviceId(msg.senderDeviceId)
                }
                if (msg.sessionId.isNotEmpty()) {
                    pendingSessionId = msg.sessionId
                }
                val targetSession = msg.sessionId.ifEmpty { pendingSessionId }.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
                val targetParent = msg.senderDeviceId.ifEmpty { pendingParentDeviceId }.ifEmpty { currentParentDeviceId }.ifEmpty { webRtcManager.getRemoteDeviceId() }

                // Vibrate device to alert child
                try {
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(500)
                    }
                } catch (_: Exception) {}

                if (webRtcManager.isScreenMirroringActive.value) {
                    webRtcManager.resendOfferForParent(
                        sessionId = targetSession,
                        targetParentDeviceId = targetParent,
                        childDeviceId = currentChildDeviceId
                    )
                } else if (webRtcManager.hasActiveScreenCapturer()) {
                    // ONE-TIME PERMISSION ACTIVE: Resumes instantly without prompt!
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = targetSession,
                            senderDeviceId = currentChildDeviceId,
                            targetDeviceId = targetParent,
                            messageType = com.example.model.SignalingType.SCREEN_PERMISSION_GRANTED,
                            payload = "STANDBY_RESUMED"
                        )
                    )
                    webRtcManager.resumeChildScreenMirroring(
                        sessionId = targetSession,
                        targetParentDeviceId = targetParent,
                        childDeviceId = currentChildDeviceId
                    )
                } else {
                    // Inform parent that approval prompt is active on child screen
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = targetSession,
                            senderDeviceId = currentChildDeviceId,
                            targetDeviceId = targetParent,
                            messageType = com.example.model.SignalingType.SCREEN_PERMISSION_REQUIRED,
                            payload = "PROMPT_SHOWN"
                        )
                    )
                    showScreenRequestDialog = true

                    val mediaProjectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
                    mediaProjectionManager?.let {
                        try {
                            mediaProjectionLauncher.launch(it.createScreenCaptureIntent())
                        } catch (e: Exception) {
                            android.util.Log.w("ChildActiveScreen", "Failed to launch screen capture: ${e.message}")
                        }
                    }
                }
            } else if (msg.messageType == com.example.model.SignalingType.START_CAMERA) {
                if (msg.senderDeviceId.isNotEmpty()) {
                    pendingParentDeviceId = msg.senderDeviceId
                    webRtcManager.setRemoteDeviceId(msg.senderDeviceId)
                }
                if (msg.sessionId.isNotEmpty()) {
                    pendingSessionId = msg.sessionId
                }
                val targetSession = msg.sessionId.ifEmpty { pendingSessionId }.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
                val targetParent = msg.senderDeviceId.ifEmpty { pendingParentDeviceId }.ifEmpty { currentParentDeviceId }.ifEmpty { webRtcManager.getRemoteDeviceId() }

                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = targetSession,
                            senderDeviceId = currentChildDeviceId,
                            targetDeviceId = targetParent,
                            messageType = com.example.model.SignalingType.CAMERA_PERMISSION_GRANTED,
                            payload = "GRANTED"
                        )
                    )
                    webRtcManager.startChildCameraStreaming(
                        sessionId = targetSession,
                        targetParentDeviceId = targetParent,
                        childDeviceId = currentChildDeviceId
                    )
                } else {
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = targetSession,
                            senderDeviceId = currentChildDeviceId,
                            targetDeviceId = targetParent,
                            messageType = com.example.model.SignalingType.CAMERA_PERMISSION_REQUIRED,
                            payload = "PROMPT_SHOWN"
                        )
                    )
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            } else if (msg.messageType == com.example.model.SignalingType.STOP_CAMERA) {
                webRtcManager.stopChildCameraStreaming()
            } else if (msg.messageType == com.example.model.SignalingType.SWITCH_CAMERA) {
                webRtcManager.switchChildCamera()
            } else if (msg.messageType == com.example.model.SignalingType.START_AUDIO) {
                if (msg.senderDeviceId.isNotEmpty()) {
                    pendingParentDeviceId = msg.senderDeviceId
                }
                if (msg.sessionId.isNotEmpty()) {
                    pendingSessionId = msg.sessionId
                }
                val targetSession = msg.sessionId.ifEmpty { pendingSessionId }.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
                val targetParent = msg.senderDeviceId.ifEmpty { pendingParentDeviceId }.ifEmpty { currentParentDeviceId }.ifEmpty { webRtcManager.getRemoteDeviceId() }

                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = targetSession,
                            senderDeviceId = currentChildDeviceId,
                            targetDeviceId = targetParent,
                            messageType = com.example.model.SignalingType.AUDIO_PERMISSION_GRANTED,
                            payload = "GRANTED"
                        )
                    )
                    webRtcManager.startChildMicrophoneStreaming(
                        sessionId = targetSession,
                        targetParentDeviceId = targetParent,
                        childDeviceId = currentChildDeviceId
                    )
                } else {
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = targetSession,
                            senderDeviceId = currentChildDeviceId,
                            targetDeviceId = targetParent,
                            messageType = com.example.model.SignalingType.AUDIO_PERMISSION_REQUIRED,
                            payload = "PROMPT_SHOWN"
                        )
                    )
                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            } else if (msg.messageType == com.example.model.SignalingType.STOP_SCREEN) {
                // Gracefully pause mirroring so MediaProjection remains authorized and cached
                webRtcManager.pauseChildScreenMirroring()
            } else if (msg.messageType == com.example.model.SignalingType.STOP_AUDIO) {
                webRtcManager.stopChildMicrophoneStreaming()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SuccessGreenLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Protection Shield",
                        tint = SuccessGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Child Protection Active",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Supervised Device Mode",
                        fontSize = 12.sp,
                        color = SuccessGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            OutlinedButton(
                onClick = onSwitchToParentMode,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("switch_to_parent_from_active_button")
            ) {
                Text(
                    text = "Parent View",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandBlue
                )
            }
        }

        // Clear, Persistent On-Child Notice that Monitoring is Active
        val isScreenMonitoringActive = isRtcScreenActive || isFgStreaming
        if (isScreenMonitoringActive) {
            Surface(
                color = Color(0xFFFEF2F2),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, ErrorRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .testTag("child_persistent_monitoring_notice")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(ErrorRed)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SCREEN MONITORING ACTIVE",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ErrorRed
                        )
                        Text(
                            text = "Your device screen is actively being monitored and shared with your parent.",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        if (isRtcCameraActive) {
            Surface(
                color = Color(0xFFF0F9FF),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, BrandBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .testTag("child_persistent_camera_notice")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(BrandBlue)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "REMOTE CAMERA ACTIVE",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = BrandBlue
                        )
                        Text(
                            text = "Live camera stream is active and shared with your parent.",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        if (isRtcMicActive) {
            Surface(
                color = Color(0xFFF0FDF4),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, SuccessGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .testTag("child_persistent_audio_notice")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(SuccessGreen)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AUDIO MONITORING ACTIVE",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = SuccessGreen
                        )
                        Text(
                            text = "Microphone stream is active: Audio is currently being shared with your parent.",
                            fontSize = 12.sp,
                            color = TextPrimary,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Real Device Status Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = "Device",
                                    tint = BrandBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${report.manufacturer} ${report.deviceModel}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(SuccessGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Online",
                                    fontSize = 12.sp,
                                    color = SuccessGreen,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Battery", fontSize = 11.sp, color = TextSecondary)
                                Text(
                                    text = "${report.batteryPercentage}% ${if (report.isCharging) "(Charging)" else ""}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                            Column {
                                Text("OS Version", fontSize = 11.sp, color = TextSecondary)
                                Text(
                                    text = report.androidVersion,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                            Column {
                                Text("Child Device ID", fontSize = 11.sp, color = TextSecondary)
                                Text(
                                    text = report.childDeviceId,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BrandBlue
                                )
                            }
                        }
                    }
                }
            }

            // Screen Mirroring Live Status Card with One-Time Permission management
            item {
                Spacer(modifier = Modifier.height(16.dp))
                val isScreenCapturerActive = webRtcManager.hasActiveScreenCapturer()
                val isScreenActive = isRtcScreenActive
                val isStandby = isScreenCapturerActive && !isScreenActive

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isScreenActive -> BrandBlueLight
                            isStandby -> SuccessGreenLight
                            else -> Color.White
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when {
                            isScreenActive -> BrandBlue
                            isStandby -> SuccessGreen
                            else -> BorderLight
                        }
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = "Screen Mirror",
                                    tint = when {
                                        isScreenActive -> BrandBlue
                                        isStandby -> SuccessGreen
                                        else -> TextSecondary
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Screen Mirroring (One-Time Auth)",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = when {
                                            isScreenActive -> "Live WebRTC streaming to parent"
                                            isStandby -> "Authorized (Standby for parent requests)"
                                            else -> "Not authorized - grant once for automatic access"
                                        },
                                        fontSize = 12.sp,
                                        color = when {
                                            isScreenActive -> BrandBlueDark
                                            isStandby -> SuccessGreen
                                            else -> TextSecondary
                                        }
                                    )
                                }
                            }

                            if (!isScreenCapturerActive) {
                                Button(
                                    onClick = {
                                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.testTag("toggle_screen_stream_button")
                                ) {
                                    Text("Grant Once", fontSize = 12.sp, color = Color.White)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isScreenActive) {
                                        Button(
                                            onClick = {
                                                webRtcManager.pauseChildScreenMirroring()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Pause", fontSize = 11.sp, color = Color.White)
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val stopIntent = Intent(context, MediaProjectionService::class.java).apply {
                                                action = MediaProjectionService.ACTION_STOP
                                            }
                                            context.startService(stopIntent)
                                            webRtcManager.stopChildScreenMirroring(permanent = true)
                                            permissionManager.setMediaProjectionConsent(false)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Revoke", fontSize = 11.sp, color = ErrorRed)
                                    }
                                }
                            }
                        }

                        if (isStandby) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "✓ One-time permission active: When the parent taps 'Start Screen Mirror', stream connects instantly without child prompts.",
                                fontSize = 11.sp,
                                color = SuccessGreen,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // One-Way Microphone Streaming Card
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (isRtcMicActive) SuccessGreenLight else Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isRtcMicActive) SuccessGreen else BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isRtcMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                                    contentDescription = "Microphone",
                                    tint = if (isRtcMicActive) SuccessGreen else TextSecondary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "One-Way Microphone",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = if (isRtcMicActive) "Microphone stream transmitting" else "Inactive - RECORD_AUDIO",
                                        fontSize = 12.sp,
                                        color = if (isRtcMicActive) SuccessGreen else TextSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (isRtcMicActive) {
                                        webRtcManager.stopChildMicrophoneStreaming()
                                    } else {
                                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasAudioPerm) {
                                            webRtcManager.startChildMicrophoneStreaming(
                                                sessionId = permissionManager.sessionId,
                                                targetParentDeviceId = permissionManager.parentDeviceId,
                                                childDeviceId = permissionManager.childDeviceId
                                            )
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRtcMicActive) ErrorRed else SuccessGreen
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("toggle_mic_stream_button")
                            ) {
                                Text(
                                    text = if (isRtcMicActive) "Stop" else "Start Mic",
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Real Permission Health Checklist
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Permission Health Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        PermissionCheckRow("Accessibility Service (Safeguard)", permissionsState.isAccessibilityGranted)
                        PermissionCheckRow("Display Over Other Apps (Overlay)", permissionsState.isOverlayGranted)
                        PermissionCheckRow("Device Activity (UsageStats)", permissionsState.isUsageAccessGranted)
                        PermissionCheckRow("Notification Listener Access", permissionsState.isNotificationAccessGranted)
                        PermissionCheckRow("Location Tracking & GPS", permissionsState.isLocationPermissionGranted && permissionsState.isLocationServicesEnabled)
                        PermissionCheckRow("Background Location", permissionsState.isBackgroundLocationGranted)
                        PermissionCheckRow("SMS History (explicit consent)", permissionsState.isSmsReadGranted)
                        PermissionCheckRow("Device Admin Active", permissionsState.isDeviceAdminGranted)
                        PermissionCheckRow("Battery Optimization Exemption", permissionsState.isBatteryOptimizationIgnored)
                        PermissionCheckRow("Auto-Start Configured", permissionsState.isAutoStartConfigured)
                    }
                }
            }

            // Manage Permissions Button
            item {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onManagePermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, BrandBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(top = 8.dp)
                        .testTag("reopen_permission_setup_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = BrandBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Modify Permission Setup",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandBlue
                    )
                }
            }
        }

        // Parent Screen View Request Approval Dialog
        if (showScreenRequestDialog && !webRtcManager.hasActiveScreenCapturer()) {
            AlertDialog(
                onDismissRequest = {
                    showScreenRequestDialog = false
                    val targetSession = pendingSessionId.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
                    val targetParent = pendingParentDeviceId.ifEmpty { currentParentDeviceId }
                    signalingEngine.sendMessage(
                        com.example.model.SignalingMessage(
                            sessionId = targetSession,
                            senderDeviceId = currentChildDeviceId,
                            targetDeviceId = targetParent,
                            messageType = com.example.model.SignalingType.SCREEN_PERMISSION_DENIED,
                            payload = "DECLINED_BY_CHILD"
                        )
                    )
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cast, contentDescription = null, tint = BrandBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Parent Screen View Request", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                },
                text = {
                    Text(
                        text = "Your parent is requesting to view this phone's screen live for safety monitoring. Tap 'Allow & Start' to grant screen capture.",
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showScreenRequestDialog = false
                            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                            mediaProjectionManager?.let {
                                try {
                                    mediaProjectionLauncher.launch(it.createScreenCaptureIntent())
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not start capture: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                    ) {
                        Text("Allow & Start Sharing")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showScreenRequestDialog = false
                            val targetSession = pendingSessionId.ifEmpty { currentSessionId }.ifEmpty { "pair_" + currentChildDeviceId.takeLast(6) }
                            val targetParent = pendingParentDeviceId.ifEmpty { currentParentDeviceId }
                            signalingEngine.sendMessage(
                                com.example.model.SignalingMessage(
                                    sessionId = targetSession,
                                    senderDeviceId = currentChildDeviceId,
                                    targetDeviceId = targetParent,
                                    messageType = com.example.model.SignalingType.SCREEN_PERMISSION_DENIED,
                                    payload = "DECLINED_BY_CHILD"
                                )
                            )
                        }
                    ) {
                        Text("Decline")
                    }
                }
            )
        }
    }
}

@Composable
fun PermissionCheckRow(
    label: String,
    isGranted: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextPrimary
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (isGranted) "Granted" else "Missing",
                tint = if (isGranted) SuccessGreen else WarningAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isGranted) "Active" else "Action needed",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (isGranted) SuccessGreen else WarningAmber
            )
        }
    }
}
