package com.example.model

data class ChildLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val recordedAt: Long = 0L
)

data class AppUsageItem(
    val packageName: String = "",
    val appName: String = "",
    val foregroundMillis: Long = 0L,
    val lastUsedAt: Long = 0L
)

data class SmsItem(
    val id: String = "",
    val address: String = "Unknown",
    val body: String = "",
    val timestamp: Long = 0L,
    val direction: String = "received"
)

data class MonitoringSnapshot(
    val childDeviceId: String = "",
    val location: ChildLocation? = null,
    val usage: List<AppUsageItem> = emptyList(),
    val messages: List<SmsItem> = emptyList(),
    val updatedAt: Long = 0L,
    val error: String? = null,
    val authErrorCode: String? = null,
    val firestoreErrorCode: String? = null,
    val setupInstruction: String? = null,
    val maskedUid: String? = null
)

data class RoleRegistrationResult(
    val isSuccess: Boolean = false,
    val message: String = "",
    val errorCode: String? = null,
    val isSparkPlanOrFunctionMissing: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
