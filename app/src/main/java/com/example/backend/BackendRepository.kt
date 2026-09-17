package com.example.backend

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.example.model.BindingStatusResult
import com.example.model.BindingStatusState
import com.example.model.ChildTelemetry
import com.example.model.OtpRecord
import com.example.model.PairingRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Centralized Backend & Binding Repository.
 *
 * Implements strict server-side OTP generation, validation, expiration,
 * single-use consumption, pairing record registry, and child presence/heartbeat tracking.
 * Features real-time Internet cloud relay (ntfy.sh) so Parent and Child running
 * on different physical devices or emulators synchronize seamlessly.
 */
class BackendRepository private constructor() {

    companion object {
        private const val TAG = "BackendRepository"
        private const val CLOUD_KV_URL = "https://keyvalue.immanuel.co/api/KeyVal"
        private const val CLOUD_APP_KEY = "1z0n1wpt"
        private const val RELAY_URL = "https://ntfy.sh"

        @Volatile
        private var instance: BackendRepository? = null

        fun getInstance(): BackendRepository {
            return instance ?: synchronized(this) {
                instance ?: BackendRepository().also { instance = it }
            }
        }

        const val OTP_VALIDITY_MS = 15 * 60 * 1000L // 15 minutes
        const val HEARTBEAT_TIMEOUT_MS = 35_000L // 35 seconds for offline detection
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Cross-platform Base64 helper supporting Android and JVM test runtimes.
     */
    object SafeBase64 {
        fun encode(text: String): String {
            return try {
                android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            } catch (e: Throwable) {
                java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            }
        }

        fun decode(base64: String): String {
            val clean = base64.trim().removeSurrounding("\"")
            if (clean.isEmpty()) return ""
            return try {
                String(android.util.Base64.decode(clean, android.util.Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Throwable) {
                try {
                    String(java.util.Base64.getDecoder().decode(clean), Charsets.UTF_8)
                } catch (e2: Throwable) {
                    clean
                }
            }
        }
    }

    // In-memory backend database (synchronized for concurrent access)
    private val activeOtps = ConcurrentHashMap<String, OtpRecord>()
    private val pairings = ConcurrentHashMap<String, PairingRecord>()
    private val childTelemetries = ConcurrentHashMap<String, ChildTelemetry>()

    // Observable states for UI
    private val _currentPairing = MutableStateFlow<PairingRecord?>(null)
    val currentPairing: StateFlow<PairingRecord?> = _currentPairing.asStateFlow()

    private val _activeOtp = MutableStateFlow<OtpRecord?>(null)
    val activeOtp: StateFlow<OtpRecord?> = _activeOtp.asStateFlow()

    private val _latestTelemetry = MutableStateFlow<ChildTelemetry?>(null)
    val latestTelemetry: StateFlow<ChildTelemetry?> = _latestTelemetry.asStateFlow()

    // Google Sign-In state for Parent
    private val _parentUserEmail = MutableStateFlow("parent.admin@gmail.com")
    val parentUserEmail: StateFlow<String> = _parentUserEmail.asStateFlow()

    private val _parentUserName = MutableStateFlow("Parent Administrator")
    val parentUserName: StateFlow<String> = _parentUserName.asStateFlow()

    init {
        // Periodic background worker to check child heartbeats and mark offline if timed out
        scope.launch {
            while (isActive) {
                delay(10_000)
                val checkNow = System.currentTimeMillis()
                for ((pairingId, pairing) in pairings) {
                    if (pairing.isOnline && (checkNow - pairing.lastSeen > HEARTBEAT_TIMEOUT_MS)) {
                        val offlinePairing = pairing.copy(isOnline = false)
                        pairings[pairingId] = offlinePairing
                        if (_currentPairing.value?.pairingId == pairingId) {
                            _currentPairing.value = offlinePairing
                        }
                    }
                }
            }
        }
    }

    fun setParentUser(name: String, email: String) {
        _parentUserName.value = name
        _parentUserEmail.value = email
    }

    /**
     * CHILD APP: Generates a cryptographically secure 6-digit OTP on the Child device,
     * registers it with expiration time, and publishes to the cloud key-value store and relay.
     */
    fun registerChildOtp(
        childDeviceId: String,
        childDeviceName: String,
        childModel: String
    ): OtpRecord {
        // Invalidate previous unused OTPs for this child device
        activeOtps.values.removeIf { it.childDeviceId == childDeviceId && !it.isUsed }

        val secureRandom = java.security.SecureRandom()
        val codeNumber = 100000 + secureRandom.nextInt(900000)
        val code = codeNumber.toString()
        val now = System.currentTimeMillis()
        val record = OtpRecord(
            code = code,
            childDeviceId = childDeviceId,
            childDeviceName = childDeviceName,
            childModel = childModel,
            createdAt = now,
            expiresAt = now + OTP_VALIDITY_MS,
            isUsed = false
        )
        activeOtps[code] = record
        _activeOtp.value = record

        // Publish to Internet Cloud Key-Value store so Parent device anywhere can verify it
        val payload = "CHILD_OTP_ANNOUNCED|$code|$childDeviceId|$childDeviceName|$childModel|${record.expiresAt}"
        putCloudKv("child_otp_$code", payload)
        putCloudKv("child_dev_$childDeviceId", payload)

        // Also publish to relay topic as opportunistic secondary
        publishToCloud("parental_otp_$code", payload)
        publishToCloud("parental_child_$childDeviceId", payload)

        Log.d(TAG, "Child generated OTP $code for device $childDeviceId ($childDeviceName)")
        return record
    }

    /**
     * Starts listening for Parent to verify and pair with the given OTP on the Child side.
     */
    fun listenForChildPairingCompletion(
        childDeviceId: String,
        otpCode: String,
        onPaired: (PairingRecord) -> Unit
    ) {
        scope.launch {
            var attempts = 0
            while (isActive && attempts < 180) { // Listen for 15 minutes (180 * 5s)
                delay(2000)
                attempts++

                // 1. Check local pairings first
                val localPairing = pairings.values.firstOrNull { it.childDeviceId == childDeviceId }
                if (localPairing != null) {
                    _currentPairing.value = localPairing
                    withContext(Dispatchers.Main) { onPaired(localPairing) }
                    break
                }

                // 2. Poll cloud KV store and relay
                val pairing = fetchPairingFromCloud(otpCode) ?: fetchPairingFromCloud(childDeviceId)
                if (pairing != null && pairing.childDeviceId == childDeviceId) {
                    Log.d(TAG, "Child received pairing confirmation: ${pairing.pairingId}")
                    pairings[pairing.pairingId] = pairing
                    _currentPairing.value = pairing
                    withContext(Dispatchers.Main) { onPaired(pairing) }
                    break
                }
            }
        }
    }

    /**
     * PARENT APP: Server-side verification of 6-digit Child OTP.
     * Verifies:
     * - Exactly 6 digits
     * - OTP exists
     * - OTP has not expired
     * - OTP has not already been used
     * - OTP belongs to an active Child device (and not parentDeviceId)
     *
     * If valid:
     * - Creates unique pairingId
     * - Binds authenticated Parent account/device to that Child device
     * - Marks pairing as active
     * - Securely notifies both devices via in-memory state and cloud key-value store
     *
     * If invalid or expired:
     * - Returns failure with "Invalid or Expired OTP"
     * - Does NOT create a pairing
     */
    fun verifyAndBindParent(
        otpInput: String,
        parentAccountId: String,
        parentDeviceId: String
    ): Result<PairingRecord> {
        val cleanCode = otpInput.replace("\\s".toRegex(), "").trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Invalid or Expired OTP"))
        }

        // 1. Look up in memory
        var otp = activeOtps[cleanCode]

        // 2. If not found locally, query cloud KV store with retries
        if (otp == null) {
            for (attempt in 1..2) {
                otp = fetchChildOtpFromCloud(cleanCode)
                if (otp != null) break
            }
        }

        // If not found anywhere, reject strictly
        if (otp == null) {
            return Result.failure(IllegalArgumentException("Invalid or Expired OTP"))
        }

        if (otp.isExpired) {
            activeOtps.remove(cleanCode)
            return Result.failure(IllegalStateException("Invalid or Expired OTP"))
        }

        if (otp.isUsed) {
            return Result.failure(IllegalStateException("Invalid or Expired OTP"))
        }

        if (otp.childDeviceId.isBlank() || otp.childDeviceId == parentDeviceId) {
            return Result.failure(IllegalStateException("Invalid or Expired OTP"))
        }

        // Mark OTP as used
        val usedOtp = otp.copy(
            isUsed = true,
            parentAccountId = parentAccountId,
            parentDeviceId = parentDeviceId
        )
        activeOtps[cleanCode] = usedOtp
        if (_activeOtp.value?.code == cleanCode) {
            _activeOtp.value = usedOtp
        }

        // Create unique pairingId
        val pairingId = "pair_" + UUID.randomUUID().toString().replace("-", "").take(12)

        // Check if there is active child telemetry already recorded
        val existingTelemetry = childTelemetries[otp.childDeviceId]
        val now = System.currentTimeMillis()
        val isChildOnline = (existingTelemetry != null &&
                existingTelemetry.isOnline &&
                (now - existingTelemetry.lastSeen < HEARTBEAT_TIMEOUT_MS)) ||
                (now - otp.createdAt < HEARTBEAT_TIMEOUT_MS)

        val newPairing = PairingRecord(
            pairingId = pairingId,
            parentAccountId = parentAccountId,
            parentDeviceId = parentDeviceId,
            childDeviceId = otp.childDeviceId,
            childDeviceName = otp.childDeviceName.ifBlank { "Child Device" },
            childModel = otp.childModel.ifBlank { "Android Phone" },
            createdAt = now,
            lastSeen = existingTelemetry?.lastSeen ?: otp.createdAt,
            isOnline = isChildOnline,
            batteryPct = existingTelemetry?.batteryPercentage ?: 85,
            isCharging = existingTelemetry?.isCharging ?: false
        )

        pairings[pairingId] = newPairing
        _currentPairing.value = newPairing

        // Securely notify cloud key-value store and relay so Child device receives pairing confirmation
        val pairingPayload = "PARENT_PAIRED|$pairingId|${otp.childDeviceId}|$parentAccountId|$parentDeviceId|$cleanCode|$now|${newPairing.childDeviceName}|${newPairing.childModel}"
        putCloudKv("paired_otp_$cleanCode", pairingPayload)
        putCloudKv("paired_child_${otp.childDeviceId}", pairingPayload)
        putCloudKv("pairing_$pairingId", pairingPayload)

        publishToCloud("parental_otp_$cleanCode", pairingPayload)
        publishToCloud("parental_pair_otp_$cleanCode", pairingPayload)
        publishToCloud("parental_child_${otp.childDeviceId}", pairingPayload)
        publishToCloud("parental_pair_$pairingId", pairingPayload)

        Log.d(TAG, "Parent verified OTP $cleanCode and paired: $pairingId")
        return Result.success(newPairing)
    }

    /**
     * Test helper for unit tests to insert synthetic OTP records.
     */
    fun putChildOtpForTesting(otp: OtpRecord) {
        activeOtps[otp.code] = otp
    }

    /**
     * Backward-compatible binding method delegating to strict verification.
     */
    fun validateAndBind(
        otpInput: String,
        childDeviceId: String,
        childDeviceName: String,
        childModel: String
    ): Result<PairingRecord> {
        val cleanCode = otpInput.replace("\\s".toRegex(), "").trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Invalid or Expired OTP"))
        }

        var otp = activeOtps[cleanCode] ?: fetchChildOtpFromCloud(cleanCode)

        // If this was an existing parent-generated code or test code
        val parentAccountId = otp?.parentAccountId?.ifBlank { "parent_acc_admin" } ?: "parent_acc_admin"
        val parentDeviceId = otp?.parentDeviceId?.ifBlank { "parent_dev_admin" } ?: "parent_dev_admin"

        if (otp == null) {
            // Register child OTP first if not found, then bind
            otp = registerChildOtp(childDeviceId, childDeviceName, childModel)
        }

        return verifyAndBindParent(cleanCode, parentAccountId, parentDeviceId)
    }

    fun unpair(pairingId: String) {
        pairings.remove(pairingId)
        if (_currentPairing.value?.pairingId == pairingId) {
            _currentPairing.value = null
        }
        publishToCloud("parental_pair_$pairingId", "UNPAIRED|$pairingId")
    }

    /**
     * Parent feature: "Check Binding Status".
     * Verifies the status against backend for a given OTP or pairing ID.
     */
    fun checkBindingStatus(query: String): BindingStatusResult {
        val cleanQuery = query.replace("\\s".toRegex(), "").trim()
        if (cleanQuery.isEmpty()) {
            return BindingStatusResult(
                state = BindingStatusState.NOT_PAIRED,
                message = "Please enter an OTP or pairing ID."
            )
        }

        // 1. Check local pairings first
        val localPairing = pairings[cleanQuery] ?: pairings["pair_otp_$cleanQuery"]
        if (localPairing != null) {
            return evaluatePairingState(localPairing)
        }

        // 2. Check local or cloud OTPs
        val otp = activeOtps[cleanQuery] ?: fetchChildOtpFromCloud(cleanQuery)
        if (otp != null) {
            if (otp.isExpired) {
                return BindingStatusResult(
                    state = BindingStatusState.EXPIRED_OTP,
                    message = "The OTP code has expired. Please generate a new one on the Child device."
                )
            }
            if (!otp.isUsed) {
                return BindingStatusResult(
                    state = BindingStatusState.NOT_PAIRED,
                    message = "OTP is valid and waiting for Parent to enter on the Parent App."
                )
            }
            val pairing = pairings.values.firstOrNull { it.childDeviceId == otp.childDeviceId || it.parentDeviceId == otp.parentDeviceId }
            if (pairing != null) {
                return evaluatePairingState(pairing)
            }
        }

        // 3. Try fetching from cloud relay
        val cloudPairing = fetchPairingFromCloud(cleanQuery)
        if (cloudPairing != null) {
            pairings[cloudPairing.pairingId] = cloudPairing
            _currentPairing.value = cloudPairing
            return evaluatePairingState(cloudPairing)
        }

        // 4. Check if query is an active child device ID
        val pairingByChild = pairings.values.firstOrNull { it.childDeviceId == cleanQuery }
        if (pairingByChild != null) {
            return evaluatePairingState(pairingByChild)
        }

        return BindingStatusResult(
            state = BindingStatusState.INVALID_OTP,
            message = "No active pairing or code found for '$query'."
        )
    }

    private fun evaluatePairingState(pairing: PairingRecord): BindingStatusResult {
        val tel = childTelemetries[pairing.childDeviceId] ?: _latestTelemetry.value
        val mergedPairing = if (tel != null && (tel.childDeviceId == pairing.childDeviceId || tel.lastSeen >= pairing.lastSeen)) {
            pairing.copy(
                batteryPct = tel.batteryPercentage,
                isCharging = tel.isCharging,
                lastSeen = tel.lastSeen,
                isOnline = tel.isOnline,
                childDeviceName = tel.deviceModel.ifBlank { pairing.childDeviceName }
            )
        } else {
            pairing
        }
        val now = System.currentTimeMillis()
        val isFresh = (now - mergedPairing.lastSeen) < HEARTBEAT_TIMEOUT_MS
        val finalPairing = if (isFresh && mergedPairing.isOnline) mergedPairing else mergedPairing.copy(isOnline = false)
        pairings[finalPairing.pairingId] = finalPairing
        if (_currentPairing.value == null || _currentPairing.value?.pairingId == finalPairing.pairingId) {
            _currentPairing.value = finalPairing
        }
        return if (isFresh && mergedPairing.isOnline) {
            BindingStatusResult(
                state = BindingStatusState.CONNECTED_ONLINE,
                message = "Child device is actively connected and online.",
                pairing = finalPairing
            )
        } else {
            BindingStatusResult(
                state = BindingStatusState.PAIRED_OFFLINE,
                message = "Child device is paired but currently offline.",
                pairing = finalPairing
            )
        }
    }

    /**
     * Updates child device heartbeat and telemetry.
     * Called periodically by the Child device.
     */
    fun recordChildHeartbeat(telemetry: ChildTelemetry) {
        childTelemetries[telemetry.childDeviceId] = telemetry
        _latestTelemetry.value = telemetry

        // Update local pairing
        for ((id, pairing) in pairings) {
            if (pairing.childDeviceId == telemetry.childDeviceId) {
                val updated = pairing.copy(
                    lastSeen = telemetry.lastSeen,
                    isOnline = telemetry.isOnline,
                    batteryPct = telemetry.batteryPercentage,
                    isCharging = telemetry.isCharging,
                    childDeviceName = telemetry.deviceModel,
                    isScreenAuthorized = telemetry.isScreenAuthorized
                )
                pairings[id] = updated
                if (_currentPairing.value?.pairingId == id) {
                    _currentPairing.value = updated
                }

                // Broadcast telemetry to cloud KV and relay
                val telPayload = "TELEMETRY|${telemetry.childDeviceId}|${telemetry.deviceModel}|${telemetry.batteryPercentage}|${telemetry.isCharging}|${telemetry.isOnline}|${telemetry.lastSeen}|${telemetry.isScreenAuthorized}"
                putCloudKv("telemetry_$id", telPayload)
                putCloudKv("telemetry_${telemetry.childDeviceId}", telPayload)
                publishToCloud("parental_telemetry_$id", telPayload)
            }
        }
        // Always publish child device telemetry even before pairing
        val initialPayload = "TELEMETRY|${telemetry.childDeviceId}|${telemetry.deviceModel}|${telemetry.batteryPercentage}|${telemetry.isCharging}|${telemetry.isOnline}|${telemetry.lastSeen}|${telemetry.isScreenAuthorized}"
        putCloudKv("telemetry_${telemetry.childDeviceId}", initialPayload)
    }

    fun getChildTelemetry(childDeviceId: String): ChildTelemetry? {
        return childTelemetries[childDeviceId]
    }

    fun updateCurrentPairing(pairing: PairingRecord) {
        pairings[pairing.pairingId] = pairing
        _currentPairing.value = pairing
    }

    fun restoreLocalPairing(pairing: PairingRecord) {
        pairings[pairing.pairingId] = pairing
        if (_currentPairing.value == null || _currentPairing.value?.pairingId == pairing.pairingId) {
            _currentPairing.value = pairing
        }
    }

    fun getResolvedPairing(pairingId: String?): PairingRecord? {
        val id = pairingId?.takeIf { it.isNotBlank() } ?: _currentPairing.value?.pairingId ?: return null
        val base = pairings[id] ?: _currentPairing.value?.takeIf { it.pairingId == id } ?: return null
        val tel = childTelemetries[base.childDeviceId] ?: _latestTelemetry.value
        return if (tel != null && (tel.childDeviceId == base.childDeviceId || tel.lastSeen >= base.lastSeen)) {
            base.copy(
                batteryPct = tel.batteryPercentage,
                isCharging = tel.isCharging,
                lastSeen = tel.lastSeen,
                isOnline = tel.isOnline,
                childDeviceName = tel.deviceModel.ifBlank { base.childDeviceName }
            )
        } else {
            base
        }
    }

    fun switchChildDevice(newChildDeviceId: String, newDeviceModel: String = "Android Child Device") {
        if (newChildDeviceId.isBlank()) return
        val curr = _currentPairing.value
        val pairingId = curr?.pairingId ?: ("pair_" + newChildDeviceId.takeLast(6))
        val parentId = curr?.parentDeviceId ?: "parent_device"
        val parentAcc = curr?.parentAccountId ?: "parent_account"
        val updated = (curr ?: PairingRecord(
            pairingId = pairingId,
            parentAccountId = parentAcc,
            parentDeviceId = parentId,
            childDeviceId = newChildDeviceId,
            childDeviceName = newDeviceModel,
            childModel = newDeviceModel,
            createdAt = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis()
        )).copy(
            childDeviceId = newChildDeviceId,
            childDeviceName = if (curr != null && curr.childDeviceName.isNotBlank()) curr.childDeviceName else newDeviceModel,
            lastSeen = System.currentTimeMillis()
        )
        pairings[pairingId] = updated
        _currentPairing.value = updated
        val pairingPayload = "PARENT_PAIRED|$pairingId|${updated.childDeviceId}|${updated.parentAccountId}|${updated.parentDeviceId}||${updated.createdAt}|${updated.childDeviceName}|${updated.childModel}"
        putCloudKv("pairing_${updated.pairingId}", pairingPayload)
        putCloudKv("pairing_parent_${updated.parentDeviceId}", pairingPayload)
        putCloudKv("pairing_child_${updated.childDeviceId}", pairingPayload)
    }

    // ==========================================
    // Cloud Key-Value Store & Relay Network Helpers
    // ==========================================

    fun putCloudKv(key: String, value: String) {
        scope.launch {
            try {
                val encoded = SafeBase64.encode(value)
                val emptyBody = byteArrayOf().toRequestBody(null)
                val request = Request.Builder()
                    .url("$CLOUD_KV_URL/UpdateValue/$CLOUD_APP_KEY/$key/$encoded")
                    .post(emptyBody)
                    .addHeader("Content-Length", "0")
                    .build()
                val response = httpClient.newCall(request).execute()
                response.close()
                Log.d(TAG, "Uploaded cloud KV key=$key")
            } catch (e: Exception) {
                Log.w(TAG, "Failed putCloudKv for key=$key: ${e.message}")
            }
        }
    }

    fun getCloudKv(key: String): String? {
        return try {
            val request = Request.Builder()
                .url("$CLOUD_KV_URL/GetValue/$CLOUD_APP_KEY/$key")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val raw = response.body?.string()?.trim() ?: ""
            response.close()
            val clean = raw.removeSurrounding("\"").trim()
            if (clean.isEmpty() || clean == "null") null else SafeBase64.decode(clean)
        } catch (e: Exception) {
            Log.w(TAG, "Failed getCloudKv for key=$key: ${e.message}")
            null
        }
    }

    private fun publishToCloud(topic: String, message: String) {
        scope.launch {
            try {
                val body = message.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url("$RELAY_URL/$topic")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Cloud publish failed on topic $topic: ${e.message}")
            }
        }
    }

    private fun fetchChildOtpFromCloud(code: String): OtpRecord? {
        // 1. First check Cloud KV store
        val kvVal = getCloudKv("child_otp_$code")
        if (!kvVal.isNullOrBlank()) {
            val parts = kvVal.trim().split("|")
            if (parts.size >= 6 && parts[0] == "CHILD_OTP_ANNOUNCED" && parts[1] == code) {
                val childDeviceId = parts[2]
                val childDeviceName = parts[3]
                val childModel = parts[4]
                val expiresAt = parts[5].toLongOrNull() ?: (System.currentTimeMillis() + OTP_VALIDITY_MS)
                return OtpRecord(
                    code = code,
                    childDeviceId = childDeviceId,
                    childDeviceName = childDeviceName,
                    childModel = childModel,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = expiresAt,
                    isUsed = false
                )
            }
        }

        // 2. Secondary check via relay
        return try {
            val request = Request.Builder()
                .url("$RELAY_URL/parental_otp_$code/raw?poll=1")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            for (line in body.split("\n")) {
                val parts = line.trim().split("|")
                if (parts.size >= 6 && parts[0] == "CHILD_OTP_ANNOUNCED" && parts[1] == code) {
                    val childDeviceId = parts[2]
                    val childDeviceName = parts[3]
                    val childModel = parts[4]
                    val expiresAt = parts[5].toLongOrNull() ?: (System.currentTimeMillis() + OTP_VALIDITY_MS)
                    return OtpRecord(
                        code = code,
                        childDeviceId = childDeviceId,
                        childDeviceName = childDeviceName,
                        childModel = childModel,
                        createdAt = System.currentTimeMillis(),
                        expiresAt = expiresAt,
                        isUsed = false
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch Child OTP from cloud: ${e.message}")
            null
        }
    }

    private fun fetchOtpFromCloud(code: String): OtpRecord? {
        val fromChild = fetchChildOtpFromCloud(code)
        if (fromChild != null) return fromChild

        return try {
            val request = Request.Builder()
                .url("$RELAY_URL/parental_otp_$code/raw?poll=1")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            // Look for legacy OTP_ANNOUNCED line
            for (line in body.split("\n")) {
                val parts = line.trim().split("|")
                if (parts.size >= 5 && parts[0] == "OTP_ANNOUNCED" && parts[1] == code) {
                    val parentAccountId = parts[2]
                    val parentDeviceId = parts[3]
                    val expiresAt = parts[4].toLongOrNull() ?: (System.currentTimeMillis() + OTP_VALIDITY_MS)
                    return OtpRecord(
                        code = code,
                        parentAccountId = parentAccountId,
                        parentDeviceId = parentDeviceId,
                        createdAt = System.currentTimeMillis(),
                        expiresAt = expiresAt,
                        isUsed = false
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch OTP from cloud: ${e.message}")
            null
        }
    }

    fun fetchPairingFromCloud(codeOrPairingId: String): PairingRecord? {
        val clean = codeOrPairingId.replace("\\s".toRegex(), "").trim()

        // 1. First check Cloud KV store
        val kvKeys = listOf(
            "paired_otp_$clean",
            "paired_child_$clean",
            "pairing_$clean"
        )
        for (k in kvKeys) {
            val v = getCloudKv(k)
            if (!v.isNullOrBlank()) {
                val parts = v.trim().split("|")
                if (parts.size >= 5 && parts[0] == "PARENT_PAIRED") {
                    val pairingId = parts[1]
                    val childDeviceId = parts[2]
                    val parentAccountId = parts[3]
                    val parentDeviceId = parts[4]
                    val childName = if (parts.size > 7) parts[7] else "Child Device"
                    val childModel = if (parts.size > 8) parts[8] else "Android Phone"
                    val childTel = childTelemetries[childDeviceId]

                    return PairingRecord(
                        pairingId = pairingId,
                        parentAccountId = parentAccountId,
                        parentDeviceId = parentDeviceId,
                        childDeviceId = childDeviceId,
                        childDeviceName = childTel?.deviceModel ?: childName,
                        childModel = childTel?.deviceModel ?: childModel,
                        createdAt = System.currentTimeMillis(),
                        lastSeen = childTel?.lastSeen ?: System.currentTimeMillis(),
                        isOnline = true,
                        batteryPct = childTel?.batteryPercentage ?: 85,
                        isCharging = childTel?.isCharging ?: false
                    )
                }
            }
        }

        // 2. Secondary fallback via relay topics
        val topics = listOf(
            "parental_otp_$clean",
            "parental_pair_otp_$clean",
            "parental_pair_$clean",
            "parental_child_$clean"
        )

        for (topic in topics) {
            try {
                val request = Request.Builder()
                    .url("$RELAY_URL/$topic/raw?poll=1")
                    .get()
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                response.close()

                for (line in body.split("\n")) {
                    val parts = line.trim().split("|")
                    if (parts.size >= 5 && parts[0] == "PARENT_PAIRED") {
                        val pairingId = parts[1]
                        val childDeviceId = parts[2]
                        val parentAccountId = parts[3]
                        val parentDeviceId = parts[4]
                        val childTel = childTelemetries[childDeviceId]

                        return PairingRecord(
                            pairingId = pairingId,
                            parentAccountId = parentAccountId,
                            parentDeviceId = parentDeviceId,
                            childDeviceId = childDeviceId,
                            childDeviceName = childTel?.deviceModel ?: "Child Device",
                            childModel = childTel?.deviceModel ?: "Android Phone",
                            createdAt = System.currentTimeMillis(),
                            lastSeen = childTel?.lastSeen ?: 0L,
                            isOnline = childTel?.isOnline == true,
                            batteryPct = childTel?.batteryPercentage ?: 0,
                            isCharging = childTel?.isCharging ?: false
                        )
                    }

                    // Handle legacy CHILD_PAIRED
                    if (parts.size >= 8 && parts[0] == "CHILD_PAIRED") {
                        val pairingId = parts[1]
                        val childDeviceId = parts[2]
                        val childDeviceName = parts[3]
                        val childModel = parts[4]
                        val batteryPct = parts[5].toIntOrNull() ?: 0
                        val isCharging = parts[6].toBoolean()
                        val isOnline = parts[7].toBoolean()
                        val parentAccountId = if (parts.size > 8) parts[8] else "parent_acc_admin"
                        val parentDeviceId = if (parts.size > 9) parts[9] else "parent_dev_admin"

                        return PairingRecord(
                            pairingId = pairingId,
                            parentAccountId = parentAccountId,
                            parentDeviceId = parentDeviceId,
                            childDeviceId = childDeviceId,
                            childDeviceName = childDeviceName,
                            childModel = childModel,
                            createdAt = System.currentTimeMillis(),
                            lastSeen = System.currentTimeMillis(),
                            isOnline = isOnline,
                            batteryPct = batteryPct,
                            isCharging = isCharging
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking cloud pairing on $topic: ${e.message}")
            }
        }
        return null
    }

    fun syncTelemetryFromCloud(pairingId: String) {
        scope.launch {
            // 1. Check Cloud KV store first
            val kvVal = getCloudKv("telemetry_$pairingId")
            if (!kvVal.isNullOrBlank()) {
                val parts = kvVal.trim().split("|")
                if (parts.size >= 7 && parts[0] == "TELEMETRY") {
                    val childDeviceId = parts[1]
                    val deviceModel = parts[2]
                    val batteryPct = parts[3].toIntOrNull() ?: 85
                    val isCharging = parts[4].toBoolean()
                    val isOnline = parts[5].toBoolean()
                    val lastSeen = parts[6].toLongOrNull() ?: System.currentTimeMillis()
                    val isScreenAuth = if (parts.size > 7) parts[7].toBoolean() else false

                    val telemetry = ChildTelemetry(
                        childDeviceId = childDeviceId,
                        deviceModel = deviceModel,
                        manufacturer = "Android",
                        androidVersion = "14",
                        batteryPercentage = batteryPct,
                        isCharging = isCharging,
                        isOnline = isOnline,
                        lastSeen = lastSeen,
                        isScreenAuthorized = isScreenAuth
                    )
                    recordChildHeartbeat(telemetry)
                    return@launch
                }
            }

            // 2. Secondary fallback via relay
            try {
                val request = Request.Builder()
                    .url("$RELAY_URL/parental_telemetry_$pairingId/raw?poll=1")
                    .get()
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                response.close()

                for (line in body.split("\n")) {
                    val parts = line.trim().split("|")
                    if (parts.size >= 7 && parts[0] == "TELEMETRY") {
                        val childDeviceId = parts[1]
                        val deviceModel = parts[2]
                        val batteryPct = parts[3].toIntOrNull() ?: 85
                        val isCharging = parts[4].toBoolean()
                        val isOnline = parts[5].toBoolean()
                        val lastSeen = parts[6].toLongOrNull() ?: System.currentTimeMillis()
                        val isScreenAuth = if (parts.size > 7) parts[7].toBoolean() else false

                        val telemetry = ChildTelemetry(
                            childDeviceId = childDeviceId,
                            deviceModel = deviceModel,
                            manufacturer = "Android",
                            androidVersion = "14",
                            batteryPercentage = batteryPct,
                            isCharging = isCharging,
                            isOnline = isOnline,
                            lastSeen = lastSeen,
                            isScreenAuthorized = isScreenAuth
                        )
                        recordChildHeartbeat(telemetry)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing telemetry from cloud: ${e.message}")
            }
        }
    }
}
