package com.example.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.permission.PermissionStatusManager
import com.example.ui.theme.AutoStartBg
import com.example.ui.theme.AutoStartColor
import com.example.ui.theme.BatteryBg
import com.example.ui.theme.BatteryColor
import com.example.ui.theme.BorderLight
import com.example.ui.theme.BrandBlue
import com.example.ui.theme.BrandBlueLight
import com.example.ui.theme.DeviceAdminBg
import com.example.ui.theme.DeviceAdminColor
import com.example.ui.theme.LocationBg
import com.example.ui.theme.LocationColor
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenLight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun ChildBackgroundPermissionScreen(
    permissionManager: PermissionStatusManager,
    onBack: () -> Unit,
    onFinishSetup: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionsState by permissionManager.permissionsState.collectAsState()

    var showHelpSheet by remember { mutableStateOf(false) }
    var showAutoStartDialog by remember { mutableStateOf(false) }

    // Re-evaluate on resume
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
                .testTag("background_permissions_list"),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Top Navigation Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("back_button_step2")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Background Permissions",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            // Header Illustration Area
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(BrandBlueLight)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_completed_settings),
                        contentDescription = "Setup completed illustration",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // Headings and Descriptions
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "You've completed basic settings",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Just one more step to go",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandBlue
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Grant the required permissions to keep the Child App running reliably in the background so that authorized parental-control features continue working.",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = TextSecondary
                    )
                }
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }

            // Android 10+ background location is granted separately in system App Settings.
            item {
                BackgroundPermissionItem(
                    icon = Icons.Default.LocationOn,
                    iconBg = LocationBg,
                    iconTint = LocationColor,
                    title = "Background Location",
                    permissionLabel = "Allow all the time",
                    description = "Required for location updates while the child app is not open. Android shows a persistent monitoring notification.",
                    isGranted = permissionsState.isBackgroundLocationGranted,
                    isRequired = true,
                    onToggle = { context.startActivity(permissionManager.getAppSettingsIntent()) }
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // 1. Device Admin Permission
            item {
                BackgroundPermissionItem(
                    icon = Icons.Default.AdminPanelSettings,
                    iconBg = DeviceAdminBg,
                    iconTint = DeviceAdminColor,
                    title = "Device Admin Permission",
                    permissionLabel = "Device Admin",
                    description = "Protects family supervision by preventing unauthorized removal while active.",
                    isGranted = permissionsState.isDeviceAdminGranted,
                    isRequired = false,
                    onToggle = {
                        if (!permissionsState.isDeviceAdminGranted) {
                            context.startActivity(permissionManager.getDeviceAdminIntent())
                        } else {
                            Toast.makeText(context, "Device Admin is active", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // 2. Ignore Battery Optimization
            item {
                BackgroundPermissionItem(
                    icon = Icons.Default.BatteryChargingFull,
                    iconBg = BatteryBg,
                    iconTint = BatteryColor,
                    title = "Ignore Battery Optimization",
                    permissionLabel = "Power Management Exemption",
                    description = "Allows parental controls to run seamlessly without OS sleep interruption.",
                    isGranted = permissionsState.isBatteryOptimizationIgnored,
                    isRequired = true,
                    onToggle = {
                        context.startActivity(permissionManager.getIgnoreBatteryOptimizationIntent())
                    }
                )
                HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(horizontal = 20.dp))
            }

            // 3. Allow auto-start
            item {
                BackgroundPermissionItem(
                    icon = Icons.Default.Autorenew,
                    iconBg = AutoStartBg,
                    iconTint = AutoStartColor,
                    title = "Allow auto-start",
                    permissionLabel = "Manufacturer Auto-Start",
                    description = "Ensures protection resumes after device restart. (${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }})",
                    isGranted = permissionsState.isAutoStartConfigured,
                    isRequired = false,
                    onToggle = {
                        showAutoStartDialog = true
                    }
                )
            }
        }

        // Floating Help Pill above bottom bar
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onFinishSetup,
                    modifier = Modifier.testTag("skip_finish_button")
                ) {
                    Text(
                        text = "Finish Later",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                }

                Button(
                    onClick = {
                        onFinishSetup()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .width(170.dp)
                        .testTag("finish_setup_button")
                ) {
                    Text(
                        text = "Complete Setup",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }

    // Auto-Start Explanation / Intent Launcher Dialog
    if (showAutoStartDialog) {
        AlertDialog(
            onDismissRequest = { showAutoStartDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Autorenew,
                    contentDescription = "Auto-Start",
                    tint = BrandBlue,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Auto-Start Permission",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Android does not have a single unified auto-start API across all brands. On ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} devices:",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Enable 'Auto-start' or 'Background Activity' for Parental Control.\n" +
                                "2. In Battery Saver, select 'No restrictions'.\n" +
                                "3. Lock the app in the recent apps tray if needed.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = TextPrimary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAutoStartDialog = false
                        val intent = permissionManager.getAutoStartSettingsIntent()
                        if (intent != null) {
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                context.startActivity(permissionManager.getAppSettingsIntent())
                            }
                        } else {
                            context.startActivity(permissionManager.getAppSettingsIntent())
                        }
                        permissionManager.markAutoStartConfigured(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAutoStartDialog = false
                        permissionManager.markAutoStartConfigured(!permissionsState.isAutoStartConfigured)
                    }
                ) {
                    Text(
                        text = if (permissionsState.isAutoStartConfigured) "Mark Unset" else "Mark Enabled",
                        color = TextSecondary
                    )
                }
            }
        )
    }

    // Help Sheet
    if (showHelpSheet) {
        HelpBottomSheet(
            onDismiss = { showHelpSheet = false },
            onOpenAppSettings = {
                showHelpSheet = false
                context.startActivity(permissionManager.getAppSettingsIntent())
            }
        )
    }
}

@Composable
fun BackgroundPermissionItem(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    permissionLabel: String,
    description: String,
    isGranted: Boolean,
    isRequired: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
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
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (isRequired) {
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
                text = permissionLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = BrandBlue
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = isGranted,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFCBD5E1),
                uncheckedBorderColor = Color.Transparent
            ),
            modifier = Modifier.testTag("switch_${title.replace(" ", "_").lowercase()}")
        )
    }
}
