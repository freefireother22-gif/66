package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.backend.BackendRepository
import com.example.model.ChildTelemetry
import com.example.model.OtpRecord
import com.example.model.PairingRecord
import com.example.signaling.SignalingEngine
import com.example.ui.theme.BorderLight
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandBlueLight
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CHILD APP: Generates a secure 6-digit OTP on the Child device and registers it with the backend.
 * Displays the 6-digit OTP clearly with expiration countdown, registers childDeviceId,
 * and automatically proceeds when the Parent enters this OTP on the Parent App.
 */
@Composable
fun ChildBindingScreen(
    childDeviceId: String,
    onBindingSuccess: (PairingRecord) -> Unit,
    onBackToRoleSelection: () -> Unit,
    onProceedToPermissions: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val backend = remember { BackendRepository.getInstance() }
    val signaling = remember { SignalingEngine.getInstance() }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBackToRoleSelection)

    val deviceName = remember { "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}" }
    val deviceModel = remember { Build.MODEL }

    var otpRecord by remember { mutableStateOf<OtpRecord?>(null) }
    var timeRemainingSeconds by remember { mutableLongStateOf(0L) }
    var isPairingSucceeded by remember { mutableStateOf(false) }
    var pairedRecord by remember { mutableStateOf<PairingRecord?>(null) }
    var isRegenerating by remember { mutableStateOf(false) }

    fun generateAndRegisterOtp() {
        isRegenerating = true
        scope.launch {
            val record = backend.registerChildOtp(
                childDeviceId = childDeviceId,
                childDeviceName = deviceName,
                childModel = deviceModel
            )
            otpRecord = record
            val seconds = ((record.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            timeRemainingSeconds = seconds
            isRegenerating = false

            // Send initial child telemetry so backend knows device status
            val initialTelemetry = ChildTelemetry(
                childDeviceId = childDeviceId,
                deviceModel = deviceModel,
                manufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.RELEASE,
                batteryPercentage = 85,
                isCharging = false,
                isOnline = true,
                lastSeen = System.currentTimeMillis()
            )
            backend.recordChildHeartbeat(initialTelemetry)

            // Start listening for Parent to verify and pair
            backend.listenForChildPairingCompletion(
                childDeviceId = childDeviceId,
                otpCode = record.code
            ) { pairing ->
                signaling.registerDevice(childDeviceId)
                signaling.joinSession(pairing.pairingId)
                pairedRecord = pairing
                isPairingSucceeded = true
            }
        }
    }

    // Initial OTP generation on mount
    LaunchedEffect(Unit) {
        generateAndRegisterOtp()
    }

    // Periodic heartbeat while ChildBindingScreen is active
    LaunchedEffect(childDeviceId) {
        while (isActive && !isPairingSucceeded) {
            val telemetry = ChildTelemetry(
                childDeviceId = childDeviceId,
                deviceModel = deviceModel,
                manufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.RELEASE,
                batteryPercentage = 85,
                isCharging = false,
                isOnline = true,
                lastSeen = System.currentTimeMillis()
            )
            backend.recordChildHeartbeat(telemetry)
            delay(8000)
        }
    }

    // Countdown timer for OTP expiration
    LaunchedEffect(otpRecord?.code) {
        while (timeRemainingSeconds > 0 && !isPairingSucceeded) {
            delay(1000)
            val currentOtp = otpRecord
            if (currentOtp != null) {
                val remaining = ((currentOtp.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                timeRemainingSeconds = remaining
            }
        }
    }

    // When pairing succeeds, wait a moment to show success message, then navigate
    LaunchedEffect(isPairingSucceeded) {
        if (isPairingSucceeded && pairedRecord != null) {
            delay(1200)
            onBindingSuccess(pairedRecord!!)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackToRoleSelection,
                modifier = Modifier.testTag("child_binding_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Text(
                text = "Child Device Pairing",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hero Icon
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isPairingSucceeded) SuccessGreenLight else BrandBlueLight)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPairingSucceeded) Icons.Default.CheckCircle else Icons.Default.VpnKey,
                contentDescription = "Key Icon",
                tint = if (isPairingSucceeded) SuccessGreen else BrandBlue,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Title and Description
        Text(
            text = if (isPairingSucceeded) "Pairing Successful!" else "Pair with Parent App",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPairingSucceeded) SuccessGreen else TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isPairingSucceeded)
                "This Child device is now securely paired to the Parent administrator."
            else
                "Enter this 6-digit OTP code on the Parent device to connect and protect this phone.",
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 6-Digit OTP Card
        val code = otpRecord?.code ?: "------"
        val isExpired = timeRemainingSeconds <= 0 && otpRecord != null

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isPairingSucceeded) SuccessGreen else if (isExpired) ErrorRed else BorderLight
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "CHILD PAIRING OTP",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Formatted 6-digit OTP display: "123  456"
                val displayOtp = if (code.length == 6) "${code.take(3)}  ${code.drop(3)}" else code

                Text(
                    text = displayOtp,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color = if (isExpired) ErrorRed else BrandBlue,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("child_otp_display")
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Expiration Countdown
                if (!isPairingSucceeded) {
                    if (isExpired) {
                        Text(
                            text = "OTP Expired. Please generate a new code.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ErrorRed
                        )
                    } else {
                        val minutes = timeRemainingSeconds / 60
                        val seconds = timeRemainingSeconds % 60
                        val timeStr = String.format("%02d:%02d", minutes, seconds)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Expires in: ",
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                            Text(
                                text = timeStr,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (timeRemainingSeconds < 60) ErrorRed else TextPrimary
                            )
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Connected",
                            tint = SuccessGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Connected with Parent",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons: Copy & Refresh
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("Child OTP", code)
                            clipboard?.setPrimaryClip(clip)
                            Toast.makeText(context, "OTP copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_child_otp_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp),
                            tint = BrandBlue
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Code", fontSize = 13.sp, color = BrandBlue)
                    }

                    Button(
                        onClick = { generateAndRegisterOtp() },
                        enabled = !isRegenerating && !isPairingSucceeded,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("refresh_child_otp_button")
                    ) {
                        if (isRegenerating) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("New Code", fontSize = 13.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Live Pairing Status Indicator
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isPairingSucceeded) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SuccessGreenLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Paired",
                            tint = SuccessGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = BrandBlue,
                        strokeWidth = 2.dp
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = if (isPairingSucceeded) "Parent Connected!" else "Waiting for Parent to enter OTP...",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPairingSucceeded) SuccessGreen else TextPrimary
                    )
                    Text(
                        text = if (isPairingSucceeded)
                            "Pairing ID: ${pairedRecord?.pairingId ?: ""}"
                        else
                            "Keep this screen open on the Child device",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Child Device Identity Card (Confirms separate childDeviceId)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("child_device_id_badge")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "Child Device",
                        tint = BrandBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "$deviceName (Child Device)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "childDeviceId: $childDeviceId",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted
                    )
                }
            }
        }

        if (onProceedToPermissions != null) {
            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onProceedToPermissions,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("proceed_to_permissions_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Continue to Child App Permissions",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You can configure child device protection permissions while waiting for parent pairing.",
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
