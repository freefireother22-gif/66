package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.DarkNavy
import com.example.ui.theme.SecondaryText

enum class OnboardingFlowStep {
    WELCOME,
    WHOSE_DEVICE
}

/**
 * Onboarding and Device Role Selection Screen
 * Screen 1: Welcome / Get Started (Kid illustration, shield, 3-dot indicators)
 * Screen 2: "Whose device is this?" (Mine > Monitored, My Child's > Monitor Others)
 */
@Composable
fun DeviceRoleSelectionScreen(
    onSelectParentRole: () -> Unit,
    onSelectChildRole: () -> Unit,
    onCheckBindingStatus: () -> Unit,
    onResetAppData: (() -> Unit)? = null,
    onViewBindingCode: (() -> Unit)? = null
) {
    var currentStep by remember { mutableStateOf(OnboardingFlowStep.WELCOME) }

    AnimatedContent(
        targetState = currentStep,
        transitionSpec = {
            if (targetState == OnboardingFlowStep.WHOSE_DEVICE) {
                slideInHorizontally { width -> width } togetherWith slideOutHorizontally { width -> -width }
            } else {
                slideInHorizontally { width -> -width } togetherWith slideOutHorizontally { width -> width }
            }
        },
        label = "OnboardingStepTransition"
    ) { step ->
        when (step) {
            OnboardingFlowStep.WELCOME -> {
                WelcomeGetStartedView(
                    onGetStarted = { currentStep = OnboardingFlowStep.WHOSE_DEVICE },
                    onCheckBindingStatus = onCheckBindingStatus,
                    onResetAppData = onResetAppData
                )
            }
            OnboardingFlowStep.WHOSE_DEVICE -> {
                WhoseDeviceSelectionView(
                    onBack = { currentStep = OnboardingFlowStep.WELCOME },
                    onSelectParent = onSelectParentRole,
                    onSelectChild = onSelectChildRole,
                    onCheckBindingStatus = onCheckBindingStatus,
                    onResetAppData = onResetAppData,
                    onViewBindingCode = onViewBindingCode
                )
            }
        }
    }
}

/**
 * Screen 1: First Page Get Started (Identical to Reference Image)
 */
@Composable
private fun WelcomeGetStartedView(
    onGetStarted: () -> Unit,
    onCheckBindingStatus: () -> Unit,
    onResetAppData: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("device_role_selection_screen")
    ) {
        // Top-Right Options Menu
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = SecondaryText.copy(alpha = 0.5f)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Check Binding Status") },
                    onClick = {
                        showMenu = false
                        onCheckBindingStatus()
                    }
                )
                if (onResetAppData != null) {
                    DropdownMenuItem(
                        text = { Text("Reset Application Data") },
                        onClick = {
                            showMenu = false
                            onResetAppData()
                        }
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(18.dp))

                // 1. Top Shield Emblem matching Reference Image
                Image(
                    painter = painterResource(id = R.drawable.ic_family_shield),
                    contentDescription = "Family Shield",
                    modifier = Modifier
                        .size(72.dp)
                        .testTag("onboarding_logo")
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 2. Headline & Subtitle matching Reference Image
                Text(
                    text = "A safer digital\nworld for your child",
                    fontSize = 27.sp,
                    lineHeight = 35.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkNavy,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Monitor, guide and protect your child's\nonline and offline activities.",
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = SecondaryText,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Central 3D Kid Illustration matching Reference Image
                Image(
                    painter = painterResource(id = R.drawable.ic_onboarding_boy),
                    contentDescription = "Child Protection Illustration",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 4. Uniform Circular Carousel Dots (Active blue, two inactive light blue)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1D61F2))
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDBEAFE))
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDBEAFE))
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 5. Royal Blue Pill "Get Started  →" Button
                Button(
                    onClick = onGetStarted,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1D61F2),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("get_started_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Get Started",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
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

                Spacer(modifier = Modifier.height(16.dp))

                // 6. Bottom "Already have an account? Log In" link
                Row(
                    modifier = Modifier.padding(bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Already have an account? ",
                        fontSize = 14.sp,
                        color = SecondaryText
                    )
                    Text(
                        text = "Log In",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1D61F2),
                        modifier = Modifier
                            .clickable { onGetStarted() }
                            .testTag("login_link")
                    )
                }
            }
        }
    }
}

/**
 * Screen 2: "Whose device is this?" matching 3rd image
 * Card 1: Mine > This device will be monitored
 * Card 2: My Child's > I want to monitor others from this device
 * Bottom: Already installed AirDroid Kids? View the binding code
 */
@Composable
private fun WhoseDeviceSelectionView(
    onBack: () -> Unit,
    onSelectParent: () -> Unit,
    onSelectChild: () -> Unit,
    onCheckBindingStatus: () -> Unit,
    onResetAppData: (() -> Unit)?,
    onViewBindingCode: (() -> Unit)? = null
) {
    BackHandler(onBack = onBack)
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("whose_device_is_this_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Top Navigation Bar (Back Arrow & Options Menu)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = DarkNavy
                        )
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = SecondaryText
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Check Binding Status") },
                                onClick = {
                                    showMenu = false
                                    onCheckBindingStatus()
                                }
                            )
                            if (onResetAppData != null) {
                                DropdownMenuItem(
                                    text = { Text("Reset Application Data") },
                                    onClick = {
                                        showMenu = false
                                        onResetAppData()
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Title matching 3rd Image: "Whose device is this?"
                Text(
                    text = "Whose device is this?",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = DarkNavy,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(30.dp))

                // Card 1: Mine > I want to monitor others from this device (Parent)
                Card(
                    onClick = onSelectParent,
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("select_parent_role_button")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Parents Art Illustration
                        Image(
                            painter = painterResource(id = R.drawable.ic_parents_card_art),
                            contentDescription = "Parents Art",
                            modifier = Modifier.size(96.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Mine",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkNavy
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = SecondaryText,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = buildAnnotatedString {
                                append("I want to ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = DarkNavy)) {
                                    append("monitor")
                                }
                                append(" others from this device")
                            },
                            fontSize = 13.sp,
                            color = SecondaryText,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Card 2: My Child's > This device will be monitored (Child)
                Card(
                    onClick = onSelectChild,
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("select_child_role_button")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Children Art Illustration
                        Image(
                            painter = painterResource(id = R.drawable.ic_children_card_art),
                            contentDescription = "Children Art",
                            modifier = Modifier.size(96.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "My Child's",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = DarkNavy
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = SecondaryText,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = buildAnnotatedString {
                                append("This device will ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = DarkNavy)) {
                                    append("be monitored")
                                }
                            },
                            fontSize = 13.sp,
                            color = SecondaryText,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Bottom Link matching 3rd Image:
            // "Already installed AirDroid Kids? View the binding code"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Already installed AirDroid Kids? ",
                    fontSize = 13.sp,
                    color = SecondaryText
                )
                Text(
                    text = "View the binding code",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1D61F2),
                    modifier = Modifier
                        .clickable {
                            if (onViewBindingCode != null) {
                                onViewBindingCode()
                            } else {
                                onCheckBindingStatus()
                            }
                        }
                        .testTag("view_binding_code_link")
                )
            }
        }
    }
}
