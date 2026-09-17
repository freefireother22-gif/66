package com.example.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppUsageItem
import com.example.ui.theme.AppCard
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DarkNavy
import com.example.ui.theme.MainBackground
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SectionHeader
import com.example.ui.theme.SoftBlueSurface
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WeeklyUsageChart

/**
 * App Usage Screen matching Reference Image (Screen 5):
 * - Tabs: Today / 7 Days / 30 Days
 * - Total Screen Time card with bar chart and % change
 * - Top Apps list with icons, progress bars, percentages
 */
@Composable
fun AppUsageDetailScreen(
    onBack: () -> Unit,
    usageList: List<AppUsageItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    var selectedTimeTab by remember { mutableIntStateOf(0) }
    val timeTabs = listOf("Today", "7 Days", "30 Days")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MainBackground)
    ) {
        // App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("app_usage_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DarkNavy
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "App Usage",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = DarkNavy
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Time Selector Tabs: Today / 7 Days / 30 Days
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoftBlueSurface)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                timeTabs.forEachIndexed { index, title ->
                    val isSelected = selectedTimeTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isSelected) Color.White else Color.Transparent)
                            .clickable { selectedTimeTab = index }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PrimaryBlue else SecondaryText
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Total Screen Time Card
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CardBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text(
                        text = "Total Screen Time",
                        fontSize = 13.sp,
                        color = SecondaryText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "2h 34m",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkNavy
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "↓ 18%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "vs. last week",
                                fontSize = 12.sp,
                                color = SecondaryText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    WeeklyUsageChart()
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Top Apps List
            SectionHeader(
                title = "Top Apps",
                actionText = "View all",
                onActionClick = {}
            )

            AppCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CardBackground
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    AppUsageProgressItem(
                        name = "YouTube",
                        duration = "1h 12m",
                        percentage = 45,
                        color = Color(0xFFFF0000)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    AppUsageProgressItem(
                        name = "Instagram",
                        duration = "42m",
                        percentage = 28,
                        color = Color(0xFFE1306C)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    AppUsageProgressItem(
                        name = "WhatsApp",
                        duration = "18m",
                        percentage = 15,
                        color = Color(0xFF25D366)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    AppUsageProgressItem(
                        name = "Chrome",
                        duration = "12m",
                        percentage = 8,
                        color = Color(0xFF4285F4)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppUsageProgressItem(
    name: String,
    duration: String,
    percentage: Int,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = name,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DarkNavy
                )
                Text(
                    text = "$percentage%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SecondaryText
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = Color(0xFFEDF2F7)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = duration,
            fontSize = 12.sp,
            color = SecondaryText
        )
    }
}
