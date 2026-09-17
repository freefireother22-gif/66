package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.DarkNavy
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SoftBlueBorder
import com.example.ui.theme.SoftBlueSurface
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * Premium Redesigned Full Screen Live Watching Interface:
 * - Edge-to-edge dark cinematic canvas
 * - Rounded video viewport with correct aspect-ratio handling
 * - Minimal top app bar: Child name, device model, pulsating LIVE green badge
 * - Diagnostics accessible via compact "Diagnostics" button & bottom sheet (not obscuring video)
 * - Floating premium bottom control bar: Audio mute/unmute, Snapshot, Refresh, Rotate, Aspect FIT/FILL, Red Stop button
 * - Confirmation dialog before stopping stream
 * - Redesigned Authorization Modal matching Google Play & Android Safety policies
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMirrorWatchingDialog(
    childName: String,
    childDeviceId: String,
    videoTrack: VideoTrack?,
    eglBase: EglBase,
    isAudioMonitoring: Boolean,
    connectionState: String,
    onToggleAudio: () -> Unit,
    onRefreshStream: () -> Unit,
    onStopMirror: () -> Unit,
    onDismiss: () -> Unit,
    onAuthorizeLocalScreen: (() -> Unit)? = null,
    isChildOnline: Boolean = true,
    isChildScreenAuthorized: Boolean = false,
    onAlertChild: (() -> Unit)? = null,
    isCameraMode: Boolean = false,
    onSwitchCamera: (() -> Unit)? = null,
    onToggleCamera: (() -> Unit)? = null,
    onToggleScreen: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // WebRTC Engine Diagnostics Flows (Preserved exactly)
    val webRtcDiag = remember { com.example.webrtc.WebRtcManager.getInstance(context) }
    val rxFrames by webRtcDiag.remoteFrameCounter.collectAsState()
    val rxSize by webRtcDiag.remoteFrameSize.collectAsState()
    val rxBytes by webRtcDiag.mediaBytesText.collectAsState()
    val transport by webRtcDiag.transportText.collectAsState()
    val iceStateLabel by webRtcDiag.iceStateLabelFlow.collectAsState()
    val dtlsStateLabel by webRtcDiag.dtlsStateLabelFlow.collectAsState()
    val candidatePairLabel by webRtcDiag.candidatePairLabelFlow.collectAsState()
    val isRelayActive by webRtcDiag.isRelayActiveFlow.collectAsState()
    val crossNetworkStatus by webRtcDiag.crossNetworkStatusFlow.collectAsState()
    val isCrossNetworkSuccess by webRtcDiag.isCrossNetworkSuccessFlow.collectAsState()
    val safeDiagnostics by webRtcDiag.safeDiagnostics.collectAsState()
    val forceTurnRelay by webRtcDiag.forceTurnRelay.collectAsState()
    val streamError by webRtcDiag.streamError.collectAsState()
    val isCameraActive by webRtcDiag.isCameraActive.collectAsState()

    // WebSocket Signaling Diagnostics (Preserved exactly)
    val signaling = remember { com.example.signaling.SignalingEngine.getInstance(context) }
    val wsConnected by signaling.isConnected.collectAsState()
    val wsStatus by signaling.connectionStatus.collectAsState()
    val sigOffersSent by signaling.offersSent.collectAsState()
    val sigOffersRecv by signaling.offersReceived.collectAsState()
    val sigAnswersSent by signaling.answersSent.collectAsState()
    val sigAnswersRecv by signaling.answersReceived.collectAsState()
    val sigCandidatesSent by signaling.candidatesSent.collectAsState()
    val sigCandidatesRecv by signaling.candidatesReceived.collectAsState()
    val sigNegotiationId by signaling.currentNegotiationId.collectAsState()
    val sigLastError by signaling.lastError.collectAsState()

    // Controls and Sheet States
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var scalingType by remember { mutableStateOf(RendererCommon.ScalingType.SCALE_ASPECT_FIT) }
    var showControls by remember { mutableStateOf(true) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var showStopConfirmation by remember { mutableStateOf(false) }
    var showAuthModal by remember { mutableStateOf(false) }

    val diagSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    BackHandler {
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler {
            onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D16))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showControls = !showControls
                }
                .testTag("full_screen_mirror_dialog")
        ) {
            // 1. Cinematic Video Viewport
            Box(
                modifier = (if (rotationDegrees != 0f) {
                    Modifier
                        .fillMaxSize()
                        .rotate(rotationDegrees)
                } else {
                    Modifier.fillMaxSize()
                })
                    .padding(if (showControls) 8.dp else 0.dp)
                    .clip(RoundedCornerShape(if (showControls) 20.dp else 0.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (videoTrack != null) {
                    WebRtcVideoView(
                        videoTrack = videoTrack,
                        eglBase = eglBase,
                        scalingType = scalingType,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Waiting for child MediaProjection stream
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = PrimaryBlue,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = if (isCameraMode || isCameraActive) "Connecting Remote Camera..." else "Connecting Live Video Feed...",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Waiting for ${childName.ifBlank { "Child Device" }} screen authorization",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("btn_cancel_mirror_waiting")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Cancel & Return",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (onAuthorizeLocalScreen != null) {
                                Button(
                                    onClick = { showAuthModal = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryBlue,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("btn_screen_auth_modal")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Authorize",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Minimal Top App Bar Overlay
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xD9000000), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: Back button & Child details
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                                    .testTag("mirror_top_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = childName.ifBlank { "Child Device" },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = childDeviceId.take(8).ifBlank { "Live Stream" },
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }

                        // Right: Live Chip & Diagnostics Toggle
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Strict requirement: Display LIVE only when decoded screen/camera frames > 0 or inbound audio RTP bytes > 0
                            val isVideoLive = rxFrames > 0L
                            val isAudioLive = isAudioMonitoring && (safeDiagnostics.audioBytesReceived > 0L || safeDiagnostics.bytesReceived > 0L)
                            val isStreamLive = isVideoLive || isAudioLive

                            val exactFailureError = when {
                                streamError != null -> streamError
                                iceStateLabel == "FAILED" -> "ICE Failed: TURN/STUN relay unreachable"
                                dtlsStateLabel == "FAILED" -> "DTLS Transport Failed: Crypto handshake error"
                                !wsConnected && sigLastError.isNotBlank() -> "Signaling Failed: $sigLastError"
                                !wsConnected -> "Signaling Offline: Disconnected from WSS server"
                                else -> null
                            }

                            val badgeBg = when {
                                exactFailureError != null -> Color(0xFF2A1010)
                                isStreamLive -> Color(0xFF102A1F)
                                else -> Color(0xFF1E293B)
                            }
                            val badgeBorder = when {
                                exactFailureError != null -> Color(0xFFEF4444).copy(alpha = 0.5f)
                                isStreamLive -> SuccessGreen.copy(alpha = 0.5f)
                                else -> WarningOrange.copy(alpha = 0.5f)
                            }
                            val badgeColor = when {
                                exactFailureError != null -> Color(0xFFEF4444)
                                isStreamLive -> SuccessGreen
                                else -> WarningOrange
                            }
                            val badgeText = when {
                                exactFailureError != null -> "ERROR"
                                isStreamLive -> "LIVE"
                                else -> "CONNECTING"
                            }

                            // Pulsing Badge - strictly shows LIVE only when remote media is decoded/received
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(badgeBg)
                                    .border(1.dp, badgeBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PulsingLiveDot(badgeColor = badgeColor)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = badgeText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Diagnostics Sheet Button
                            IconButton(
                                onClick = { showDiagnosticsSheet = true },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color.White.copy(alpha = 0.15f), CircleShape)
                                    .testTag("open_live_diagnostics_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Stream Diagnostics",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Exact Error Banner shown if signaling, permission, TURN, ICE, DTLS or capture fails
                    val currentFailure = when {
                        streamError != null -> streamError
                        iceStateLabel == "FAILED" -> "ICE Failed: Relay/STUN servers unreachable"
                        dtlsStateLabel == "FAILED" -> "DTLS Transport Failed: Handshake error"
                        !wsConnected && sigLastError.isNotBlank() -> "Signaling Error: $sigLastError"
                        !wsConnected -> "Signaling Offline: Disconnected from WSS server"
                        else -> null
                    }
                    if (currentFailure != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xEE450A0A),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("stream_error_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Error",
                                    tint = Color(0xFFFCA5A5),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = currentFailure,
                                    color = Color(0xFFFEE2E2),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // 3. Floating Bottom Control Bar Overlay
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xE6000000))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Floating Pill Control Panel
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.95f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. Audio Mute / Unmute
                            LiveControlIconButton(
                                icon = if (isAudioMonitoring) Icons.Default.Mic else Icons.Default.MicOff,
                                label = if (isAudioMonitoring) "Mute" else "Unmute",
                                tint = if (isAudioMonitoring) SuccessGreen else Color(0xFF94A3B8),
                                activeBackground = if (isAudioMonitoring) Color(0xFF132D24) else Color.Transparent,
                                onClick = onToggleAudio
                            )

                            // 2. Rotate View (0 -> 90 -> 180 -> 270)
                            LiveControlIconButton(
                                icon = Icons.Default.ScreenRotation,
                                label = "${rotationDegrees.toInt()}°",
                                tint = Color.White,
                                onClick = {
                                    rotationDegrees = (rotationDegrees + 90f) % 360f
                                }
                            )

                            // 3. Aspect Ratio (FIT vs FILL)
                            LiveControlIconButton(
                                icon = Icons.Default.AspectRatio,
                                label = if (scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FIT) "Fit" else "Fill",
                                tint = Color.White,
                                onClick = {
                                    scalingType = if (scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FIT) {
                                        RendererCommon.ScalingType.SCALE_ASPECT_FILL
                                    } else {
                                        RendererCommon.ScalingType.SCALE_ASPECT_FIT
                                    }
                                }
                            )

                            // 4. Flip Camera (when camera stream is active or camera mode selected)
                            if (isCameraMode || isCameraActive) {
                                LiveControlIconButton(
                                    icon = Icons.Default.Cameraswitch,
                                    label = "Flip Cam",
                                    tint = Color.White,
                                    onClick = { onSwitchCamera?.invoke() }
                                )
                            }

                            // 5. Refresh / Reconnect Stream
                            LiveControlIconButton(
                                icon = Icons.Default.Refresh,
                                label = "Refresh",
                                tint = Color.White,
                                onClick = onRefreshStream
                            )

                            // 6. Red Stop Mirror Button (Direct exit)
                            Button(
                                onClick = { onStopMirror() },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ErrorRed,
                                    contentColor = Color.White
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 14.dp,
                                    vertical = 8.dp
                                ),
                                modifier = Modifier
                                    .height(42.dp)
                                    .testTag("btn_stop_mirror_action")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Stop",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Stop Mirror Confirmation Dialog
        if (showStopConfirmation) {
            AlertDialog(
                onDismissRequest = { showStopConfirmation = false },
                title = { Text("Stop Screen Mirroring?", fontWeight = FontWeight.Bold) },
                text = { Text("This will end the active live screen session with the child device.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showStopConfirmation = false
                            onStopMirror()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                    ) {
                        Text("Stop Session", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStopConfirmation = false }) {
                        Text("Cancel", color = SecondaryText)
                    }
                }
            )
        }

        // Screen Viewing Authorization Bottom Sheet (Redesigned per Google Play / Android Policy)
        if (showAuthModal && onAuthorizeLocalScreen != null) {
            ModalBottomSheet(
                onDismissRequest = { showAuthModal = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(SoftBlueSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Screen Viewing Authorization",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkNavy,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Android requires visible, voluntary user authorization before a device screen can be shared with a parent. A foreground service notification is permanently shown during active mirroring.",
                        fontSize = 13.sp,
                        color = SecondaryText,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Status Cards
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SoftBlueSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Parent Request:", fontSize = 13.sp, color = SecondaryText)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Authorized by you", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DarkNavy)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "Child Consent:", fontSize = 13.sp, color = SecondaryText)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isChildScreenAuthorized) "Granted via MediaProjection" else "Pending device approval",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isChildScreenAuthorized) SuccessGreen else WarningOrange
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            showAuthModal = false
                            onAuthorizeLocalScreen.invoke()
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            text = "Grant Screen Viewing Request",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(onClick = { showAuthModal = false }) {
                        Text("Cancel", color = SecondaryText)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        // Detailed Stream Diagnostics Bottom Sheet (Collapsible per specification)
        if (showDiagnosticsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showDiagnosticsSheet = false },
                sheetState = diagSheetState,
                containerColor = Color(0xFF0F172A),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Stream & Network Diagnostics",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { showDiagnosticsSheet = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Real WebRTC & Signaling Metrics
                    DiagMetricGroup(
                        title = "Signaling & Transport",
                        items = listOf(
                            "WSS Status" to wsStatus,
                            "Offers (Tx / Rx)" to "$sigOffersSent / $sigOffersRecv",
                            "Answers (Tx / Rx)" to "$sigAnswersSent / $sigAnswersRecv",
                            "Candidates (Tx / Rx)" to "$sigCandidatesSent / $sigCandidatesRecv",
                            "Negotiation ID" to sigNegotiationId.takeLast(8).ifBlank { "none" },
                            "Connection" to connectionState
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    DiagMetricGroup(
                        title = "WebRTC P2P Media Pipeline",
                        items = listOf(
                            "Active Stream" to if (isCameraActive || isCameraMode) "Remote Camera" else "Screen Mirror",
                            "ICE State" to iceStateLabel,
                            "DTLS State" to dtlsStateLabel,
                            "Candidate Pair" to candidatePairLabel,
                            "Relay Active" to if (isRelayActive) "TURN Active" else "Direct P2P",
                            "Decoded Frames" to rxFrames.toString(),
                            "Frame Resolution" to rxSize,
                            "Inbound Audio Bytes" to safeDiagnostics.audioBytesReceived.toString(),
                            "Inbound Audio Packets" to safeDiagnostics.audioPacketsReceived.toString(),
                            "Payload Bytes" to rxBytes,
                            "Network Path" to crossNetworkStatus
                        )
                    )

                    if (sigLastError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF2E1515),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Last Safe Signaling Note: $sigLastError",
                                fontSize = 11.sp,
                                color = Color(0xFFFF8B8B),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveControlIconButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    activeBackground: Color = Color.Transparent,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(activeBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFF94A3B8),
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagMetricGroup(
    title: String,
    items: List<Pair<String, String>>
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF38BDF8)
            )
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items.forEach { (label, value) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = value.ifBlank { "—" },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingLiveDot(badgeColor: Color, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "badge_dot_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(badgeColor.copy(alpha = alpha))
    )
}

