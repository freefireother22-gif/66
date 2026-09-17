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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.NoAdultContent
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AppCard
import com.example.ui.theme.BorderLight
import com.example.ui.theme.CardBackground
import com.example.ui.theme.DarkNavy
import com.example.ui.theme.MainBackground
import com.example.ui.theme.NeutralDivider
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SoftBlueSurface
import com.example.ui.theme.SuccessGreen

/**
 * Content Filter Screen matching Reference Image (Screen 7):
 * - Tabs: Web Filter, YouTube, Apps
 * - Master enable switch: Web & Content Filter
 * - Blocked Categories switches: Adult Content, Violence, Gambling, Drugs, Weapons, Social Media
 * - Explains enforcement depends on authorized Android safeguards
 */
@Composable
fun ContentFilterScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilterTab by remember { mutableIntStateOf(0) }
    val filterTabs = listOf("Web Filter", "YouTube", "Apps")

    var isMasterFilterEnabled by remember { mutableStateOf(true) }
    var blockAdult by remember { mutableStateOf(true) }
    var blockViolence by remember { mutableStateOf(true) }
    var blockGambling by remember { mutableStateOf(true) }
    var blockDrugs by remember { mutableStateOf(true) }
    var blockWeapons by remember { mutableStateOf(true) }
    var blockSocialMedia by remember { mutableStateOf(true) }

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
                modifier = Modifier.testTag("content_filter_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DarkNavy
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Content Filter",
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

            // Tabs: Web Filter, YouTube, Apps
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SoftBlueSurface)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                filterTabs.forEachIndexed { index, title ->
                    val isSelected = selectedFilterTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isSelected) Color.White else Color.Transparent)
                            .clickable { selectedFilterTab = index }
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

            // Master Filter Card
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CardBackground
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SoftBlueSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Web & Content Filter",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = DarkNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Block harmful and inappropriate content across all browsers and apps.",
                            fontSize = 12.sp,
                            color = SecondaryText,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = isMasterFilterEnabled,
                        onCheckedChange = { isMasterFilterEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = PrimaryBlue,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFD0D5DD)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Blocked Categories Title
            Text(
                text = "Blocked Categories",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = DarkNavy
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Categories List Card
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = CardBackground
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CategorySwitchRow(
                        title = "Adult Content",
                        icon = Icons.Default.NoAdultContent,
                        isChecked = blockAdult,
                        onCheckedChange = { blockAdult = it }
                    )
                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)

                    CategorySwitchRow(
                        title = "Violence",
                        icon = Icons.Default.Dangerous,
                        isChecked = blockViolence,
                        onCheckedChange = { blockViolence = it }
                    )
                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)

                    CategorySwitchRow(
                        title = "Gambling",
                        icon = Icons.Default.Casino,
                        isChecked = blockGambling,
                        onCheckedChange = { blockGambling = it }
                    )
                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)

                    CategorySwitchRow(
                        title = "Drugs",
                        icon = Icons.Default.LocalPharmacy,
                        isChecked = blockDrugs,
                        onCheckedChange = { blockDrugs = it }
                    )
                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)

                    CategorySwitchRow(
                        title = "Weapons",
                        icon = Icons.Default.Warning,
                        isChecked = blockWeapons,
                        onCheckedChange = { blockWeapons = it }
                    )
                    HorizontalDivider(color = NeutralDivider, thickness = 1.dp)

                    CategorySwitchRow(
                        title = "Social Media",
                        icon = Icons.Default.Share,
                        isChecked = blockSocialMedia,
                        onCheckedChange = { blockSocialMedia = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Info note at bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "You can customize allowed and blocked categories in advanced settings.",
                    fontSize = 12.sp,
                    color = SecondaryText
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CategorySwitchRow(
    title: String,
    icon: ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SecondaryText,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = DarkNavy,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PrimaryBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFD0D5DD)
            )
        )
    }
}
