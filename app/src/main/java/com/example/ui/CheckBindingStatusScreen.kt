package com.example.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.backend.BackendRepository
import com.example.model.BindingStatusResult
import com.example.model.BindingStatusState
import com.example.model.PairingRecord
import com.example.permission.PermissionStatusManager
import com.example.ui.theme.BorderLight
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Check Binding Status Screen.
 *
 * Allows Parent to query the real status of an OTP or existing pairing with accurate states:
 * - Connected / Online
 * - Paired but Offline
 * - Not Paired
 * - Invalid OTP
 * - Expired OTP
 * - Connection Lost
 * - Checking
 */
@Composable
fun CheckBindingStatusScreen(
    initialCodeOrPairingId: String = "",
    permissionManager: PermissionStatusManager? = null,
    onBack: () -> Unit,
    onOpenDashboard: (PairingRecord) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val actualPermissionManager = remember(context, permissionManager) {
        permissionManager ?: PermissionStatusManager(context)
    }
    val backend = remember { BackendRepository.getInstance() }
    val scope = rememberCoroutineScope()
    val activePairing by backend.currentPairing.collectAsState()

    var searchQuery by remember {
        mutableStateOf(initialCodeOrPairingId.ifEmpty { activePairing?.pairingId ?: "" })
    }
    var isChecking by remember { mutableStateOf(false) }
    var statusResult by remember { mutableStateOf<BindingStatusResult?>(null) }

    fun performCheck() {
        if (searchQuery.isBlank()) return
        isChecking = true
        scope.launch {
            delay(250)
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                backend.checkBindingStatus(searchQuery)
            }
            statusResult = result
            isChecking = false
        }
    }

    LaunchedEffect(Unit) {
        if (searchQuery.isNotEmpty()) {
            performCheck()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        // Top Navigation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("check_binding_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Check Binding Status",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search Input Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Enter Child OTP or Pairing ID",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("e.g. 482915 or pair_...", color = TextMuted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = { performCheck() }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandBlue,
                            unfocusedBorderColor = BorderLight
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("binding_search_input")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { performCheck() },
                        enabled = !isChecking && searchQuery.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("perform_check_button")
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Check", tint = Color.White)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Result Card
        statusResult?.let { result ->
            val (badgeColor, badgeBg, badgeIcon) = when (result.state) {
                BindingStatusState.CONNECTED_ONLINE -> Triple(SuccessGreen, Color(0xFFDCFCE7), Icons.Default.CheckCircle)
                BindingStatusState.PAIRED_OFFLINE -> Triple(WarningAmber, Color(0xFFFEF3C7), Icons.Default.Warning)
                BindingStatusState.NOT_PAIRED -> Triple(BrandBlue, Color(0xFFE0F2FE), Icons.Default.Info)
                BindingStatusState.INVALID_OTP,
                BindingStatusState.EXPIRED_OTP,
                BindingStatusState.CONNECTION_LOST -> Triple(ErrorRed, Color(0xFFFEE2E2), Icons.Default.Warning)
                BindingStatusState.CHECKING -> Triple(BrandBlue, Color(0xFFE0F2FE), Icons.Default.Refresh)
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Status Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STATUS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(badgeBg)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = badgeIcon,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = when (result.state) {
                                        BindingStatusState.CONNECTED_ONLINE -> "Connected / Online"
                                        BindingStatusState.PAIRED_OFFLINE -> "Paired but Offline"
                                        BindingStatusState.NOT_PAIRED -> "Not Paired"
                                        BindingStatusState.INVALID_OTP -> "Invalid OTP"
                                        BindingStatusState.EXPIRED_OTP -> "Expired OTP"
                                        BindingStatusState.CONNECTION_LOST -> "Connection Lost"
                                        BindingStatusState.CHECKING -> "Checking..."
                                    },
                                    color = badgeColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = result.message,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )

                    // Paired Device Details
                    result.pairing?.let { pairing ->
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = BorderLight, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(16.dp))

                        DetailRow(label = "Child Device Name", value = pairing.childDeviceName)
                        DetailRow(label = "Device Model", value = pairing.childModel)
                        DetailRow(
                            label = "Online / Offline",
                            value = if (pairing.isOnline) "Active (Connected to Backend)" else "Offline"
                        )
                        DetailRow(label = "Battery %", value = "${pairing.batteryPct}%")
                        DetailRow(
                            label = "Charging Status",
                            value = if (pairing.isCharging) "Charging (Plugged in)" else "Discharging (Battery)"
                        )
                        val lastSeenFormatted = remember(pairing.lastSeen) {
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(pairing.lastSeen))
                        }
                        DetailRow(label = "Last Seen", value = lastSeenFormatted)
                        DetailRow(label = "Pairing ID", value = pairing.pairingId)

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = {
                                actualPermissionManager.updatePairing(pairing, registered = actualPermissionManager.isPairingRegistered)
                                backend.updateCurrentPairing(pairing)
                                onOpenDashboard(pairing)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("open_dashboard_from_status_button")
                        ) {
                            Text(
                                text = "Open Monitoring Dashboard",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
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
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextSecondary
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary
        )
    }
}
