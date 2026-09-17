package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.backend.FirebaseMonitoringRepository
import com.example.permission.PermissionStatusManager
import com.example.ui.CheckBindingStatusScreen
import com.example.ui.ChildActiveScreen
import com.example.ui.ChildBackgroundPermissionScreen
import com.example.ui.ChildBindingScreen
import com.example.ui.ChildPermissionSetupScreen
import com.example.ui.ConnectChildOtpScreen
import com.example.ui.DeviceRoleSelectionScreen
import com.example.ui.ParentDashboardScreen
import com.example.ui.ParentMonitoringScreen
import com.example.ui.theme.MyApplicationTheme

enum class AppNavDestination {
    RoleSelection,
    ChildBinding,
    ChildSetupStep1,
    ChildSetupStep2,
    ChildActive,
    ParentDashboard,
    ParentMonitoring,
    ConnectChildOtp,
    CheckBindingStatus
}

class MainActivity : ComponentActivity() {

    private lateinit var permissionManager: PermissionStatusManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFormat(android.graphics.PixelFormat.OPAQUE)
        enableEdgeToEdge()

        permissionManager = PermissionStatusManager(applicationContext)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    ParentalControlApp(permissionManager = permissionManager)
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        if (::permissionManager.isInitialized) {
            permissionManager.refreshAllPermissions()
        }
    }
}

@Composable
fun ParentalControlApp(permissionManager: PermissionStatusManager) {
    var currentScreen by remember { mutableStateOf(AppNavDestination.RoleSelection) }
    var initialCheckQuery by remember { mutableStateOf("") }
    var activePairingRecordForMonitoring by remember { mutableStateOf<com.example.model.PairingRecord?>(null) }
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val monitoringRepository = remember { FirebaseMonitoringRepository.getInstance(context) }

    LaunchedEffect(Unit) {
        monitoringRepository.ensureAnonymousAuth()
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
                slideInHorizontally { width -> width } togetherWith slideOutHorizontally { width -> -width }
            } else {
                slideInHorizontally { width -> -width } togetherWith slideOutHorizontally { width -> width }
            }
        },
        label = "ScreenTransition"
    ) { destination ->
        when (destination) {
            AppNavDestination.RoleSelection -> {
                DeviceRoleSelectionScreen(
                    onSelectParentRole = {
                        currentScreen = AppNavDestination.ParentDashboard
                    },
                    onSelectChildRole = {
                        if (permissionManager.pairingId.isNotBlank()) {
                            currentScreen = AppNavDestination.ChildSetupStep1
                        } else {
                            currentScreen = AppNavDestination.ChildBinding
                        }
                    },
                    onCheckBindingStatus = {
                        initialCheckQuery = ""
                        currentScreen = AppNavDestination.CheckBindingStatus
                    },
                    onResetAppData = {
                        permissionManager.resetAllState()
                        appScope.launch {
                            monitoringRepository.performFreshReset()
                        }
                    },
                    onViewBindingCode = {
                        currentScreen = AppNavDestination.ChildBinding
                    }
                )
            }
            AppNavDestination.ChildBinding -> {
                ChildBindingScreen(
                    childDeviceId = permissionManager.childDeviceId,
                    onBindingSuccess = { pairing ->
                        permissionManager.updatePairing(
                            newPairingId = pairing.pairingId,
                            newParentAccountId = pairing.parentAccountId,
                            newParentDeviceId = pairing.parentDeviceId,
                            newChildDeviceId = pairing.childDeviceId
                        )
                        appScope.launch {
                            runCatching {
                                monitoringRepository.registerPairingRole(pairing.pairingId, "child", pairing.childDeviceId, pairing.parentDeviceId)
                            }
                        }
                        currentScreen = AppNavDestination.ChildSetupStep1
                    },
                    onBackToRoleSelection = {
                        currentScreen = AppNavDestination.RoleSelection
                    },
                    onProceedToPermissions = {
                        currentScreen = AppNavDestination.ChildSetupStep1
                    }
                )
            }
            AppNavDestination.ChildSetupStep1 -> {
                ChildPermissionSetupScreen(
                    permissionManager = permissionManager,
                    onProceedToBackgroundPermissions = {
                        currentScreen = AppNavDestination.ChildSetupStep2
                    },
                    onOpenParentMode = {
                        currentScreen = AppNavDestination.RoleSelection
                    },
                    onBack = {
                        if (permissionManager.pairingId.isNotBlank()) {
                            currentScreen = AppNavDestination.RoleSelection
                        } else {
                            currentScreen = AppNavDestination.ChildBinding
                        }
                    }
                )
            }
            AppNavDestination.ChildSetupStep2 -> {
                ChildBackgroundPermissionScreen(
                    permissionManager = permissionManager,
                    onBack = {
                        currentScreen = AppNavDestination.ChildSetupStep1
                    },
                    onFinishSetup = {
                        currentScreen = AppNavDestination.ChildActive
                    }
                )
            }
            AppNavDestination.ChildActive -> {
                ChildActiveScreen(
                    permissionManager = permissionManager,
                    onManagePermissions = {
                        currentScreen = AppNavDestination.ChildSetupStep1
                    },
                    onSwitchToParentMode = {
                        currentScreen = AppNavDestination.RoleSelection
                    }
                )
            }
            AppNavDestination.ParentDashboard -> {
                ParentDashboardScreen(
                    permissionManager = permissionManager,
                    onBackToRoleSelection = {
                        currentScreen = AppNavDestination.RoleSelection
                    },
                    onNavigateToConnectChildOtp = {
                        currentScreen = AppNavDestination.ConnectChildOtp
                    },
                    onNavigateToCheckBinding = { query ->
                        initialCheckQuery = query
                        currentScreen = AppNavDestination.CheckBindingStatus
                    },
                    onNavigateToMonitoring = {
                        activePairingRecordForMonitoring = com.example.backend.BackendRepository.getInstance().currentPairing.value
                        currentScreen = AppNavDestination.ParentMonitoring
                    }
                )
            }
            AppNavDestination.ParentMonitoring -> {
                ParentMonitoringScreen(
                    permissionManager = permissionManager,
                    initialPairing = activePairingRecordForMonitoring,
                    onBack = { currentScreen = AppNavDestination.ParentDashboard },
                    onNavigateToPairing = { currentScreen = AppNavDestination.ConnectChildOtp }
                )
            }
            AppNavDestination.ConnectChildOtp -> {
                ConnectChildOtpScreen(
                    parentAccountId = permissionManager.parentAccountId,
                    parentDeviceId = permissionManager.parentDeviceId,
                    onConnected = { pairing ->
                        permissionManager.updatePairing(
                            newPairingId = pairing.pairingId,
                            newParentAccountId = pairing.parentAccountId,
                            newParentDeviceId = pairing.parentDeviceId,
                            newChildDeviceId = pairing.childDeviceId
                        )
                        appScope.launch {
                            runCatching {
                                monitoringRepository.registerPairingRole(pairing.pairingId, "parent", pairing.childDeviceId, pairing.parentDeviceId)
                            }
                        }
                        currentScreen = AppNavDestination.ParentDashboard
                    },
                    onBack = {
                        currentScreen = AppNavDestination.ParentDashboard
                    },
                    onCheckStatus = {
                        initialCheckQuery = ""
                        currentScreen = AppNavDestination.CheckBindingStatus
                    }
                )
            }
            AppNavDestination.CheckBindingStatus -> {
                CheckBindingStatusScreen(
                    initialCodeOrPairingId = initialCheckQuery,
                    permissionManager = permissionManager,
                    onBack = {
                        currentScreen = AppNavDestination.RoleSelection
                    },
                    onOpenDashboard = { pairing ->
                        activePairingRecordForMonitoring = pairing
                        currentScreen = AppNavDestination.ParentDashboard
                    }
                )
            }
        }
    }
}
