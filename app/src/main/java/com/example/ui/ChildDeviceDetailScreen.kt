package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AppCard
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DarkNavy
import com.example.ui.theme.MainBackground
import com.example.ui.theme.NeutralDivider
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SectionHeader
import com.example.ui.theme.SoftBlueSurface
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WeeklyUsageChart

private enum class DeviceTab(val label: String) {
    OVERVIEW("Overview"),
    APPS("Apps"),
    USAGE("Usage"),
    LOCATION("Location")
}

/**
 * Child Device Detail Screen matching Reference Image (Screen 3):
 * - Header: Back arrow, "Child Device", subtitle "RMX2030", 3-dots menu
 * - Device Hero Card: Phone graphic, "RMX2030", "Last seen: Today, 10:24 AM", right chevron
 *   Pill badges: Battery 96% (green), Power Mode Charging (green)
 * - Horizontal Tabs: Overview (active), Apps, Usage, Location
 * - Screen Time Card: "Screen Time", "View details >", "2h 34m", "Today", weekly bar chart
 * - Most Used Apps: "Most Used Apps", "View all >", YouTube (45%), Instagram (28%), WhatsApp (18%)
 * - Control Section: "Control", "Screen Time Limit", "Set daily limits for screen time."
 */
@Composable
fun ChildDeviceDetailScreen(
    childName: String = "Alex",
    deviceModel: String = "RMX2030",
    batteryPercentage: Int = 96,
    isCharging: Boolean = true,
    isOnline: Boolean = true,
    onBack: () -> Unit,
    onLaunchLiveMirror: () -> Unit,
    onViewAppUsage: () -> Unit,
    onViewLocation: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(DeviceTab.OVERVIEW) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MainBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = DarkNavy
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Child Device",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = DarkNavy
                    )
                    Text(
                        text = deviceModel,
                        fontSize = 12.sp,
                        color = SecondaryText
                    )
                }
            }

            IconButton(onClick = onLaunchLiveMirror) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = DarkNavy
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // 1. Device Card with Phone Illustration
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLaunchLiveMirror() },
                backgroundColor = CardBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_device_phone),
                            contentDescription = "Phone Graphic",
                            modifier = Modifier.size(width = 38.dp, height = 58.dp)
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = deviceModel,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkNavy
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Last seen: Today, 10:24 AM",
                                fontSize = 12.sp,
                                color = SecondaryText
                            )
                        }

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color(0xFF98A2B3)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Two status pills in a row: Battery 96% and Power Mode Charging
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Battery pill
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFECFDF5))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = "Battery", fontSize = 11.sp, color = SecondaryText)
                                Text(
                                    text = "$batteryPercentage%",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkNavy
                                )
                            }
                        }

                        // Power Mode pill
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFECFDF5))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = "Power Mode", fontSize = 11.sp, color = SecondaryText)
                                Text(
                                    text = if (isCharging) "Charging" else "Normal",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DarkNavy
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Horizontal Segmented Tabs: Overview, Apps, Usage, Location
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                DeviceTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                selectedTab = tab
                                when (tab) {
                                    DeviceTab.USAGE -> onViewAppUsage()
                                    DeviceTab.LOCATION -> onViewLocation()
                                    else -> {}
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 12.dp)
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PrimaryBlue else SecondaryText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .height(2.5.dp)
                                .width(if (isSelected) 36.dp else 0.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (isSelected) PrimaryBlue else Color.Transparent)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Screen Time Card
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CardBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Screen Time",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkNavy
                        )
                        Text(
                            text = "View details >",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue,
                            modifier = Modifier.clickable { onViewAppUsage() }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            text = "2h 34m",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkNavy
                        )
                    }
                    Text(
                        text = "Today",
                        fontSize = 12.sp,
                        color = SecondaryText
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    WeeklyUsageChart()
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Most Used Apps
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Most Used Apps",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkNavy
                )
                Text(
                    text = "View all >",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrimaryBlue,
                    modifier = Modifier.clickable { onViewAppUsage() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CardBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    DeviceAppRow(name = "YouTube", duration = "1h 12m", percentage = "45%", color = Color(0xFFFF0000))
                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)
                    DeviceAppRow(name = "Instagram", duration = "42m", percentage = "28%", color = Color(0xFFE1306C))
                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)
                    DeviceAppRow(name = "WhatsApp", duration = "28m", percentage = "18%", color = Color(0xFF25D366))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. Control Section
            SectionHeader(title = "Control")

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CardBackground
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onViewAppUsage() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SoftBlueSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Screen Time Limit",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DarkNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Set daily limits for screen time.",
                            fontSize = 12.sp,
                            color = SecondaryText
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color(0xFF98A2B3)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DeviceAppRow(
    name: String,
    duration: String,
    percentage: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = name,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DarkNavy
            )
            Text(
                text = duration,
                fontSize = 12.sp,
                color = SecondaryText
            )
        }

        Text(
            text = percentage,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = SecondaryText
        )
    }
}
