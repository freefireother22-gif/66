package com.example.backend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.model.AppUsageItem
import com.example.model.ChildLocation
import com.example.model.MonitoringSnapshot
import com.example.model.RoleRegistrationResult
import com.example.model.SmsItem
import com.example.monitoring.SmsCollector
import com.example.monitoring.UsageStatsCollector
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** Firebase monitoring transport. Requires app/google-services.json and Anonymous Auth.
 * Production authorization is enforced by firestore.rules and callable pairing functions.
 */
class FirebaseMonitoringRepository private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: FirebaseMonitoringRepository? = null
        fun getInstance(context: Context): FirebaseMonitoringRepository = instance ?: synchronized(this) {
            instance ?: FirebaseMonitoringRepository(context.applicationContext).also { instance = it }
        }
    }

    private val _snapshot = MutableStateFlow(MonitoringSnapshot())
    val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()
    private var listener: com.google.firebase.firestore.ListenerRegistration? = null

    val isConfigured: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()
    val projectId: String get() = runCatching { FirebaseApp.getInstance().options.projectId.orEmpty() }
        .getOrDefault("parental-app-2b2f9").ifBlank { "parental-app-2b2f9" }
    val packageName: String get() = context.packageName

    val fullUid: String?
        get() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    val maskedUid: String?
        get() {
            val uid = fullUid
            if (uid.isNullOrBlank()) return null
            return if (uid.length > 8) "${uid.take(4)}...${uid.takeLast(4)}" else uid
        }

    private val _registrationResult = MutableStateFlow<RoleRegistrationResult?>(null)
    val registrationResult: StateFlow<RoleRegistrationResult?> = _registrationResult.asStateFlow()

    private val _isFirebasePairingVerified = MutableStateFlow<Boolean>(false)
    val isFirebasePairingVerified: StateFlow<Boolean> = _isFirebasePairingVerified.asStateFlow()

    private val _isFirebasePairingDocMissing = MutableStateFlow<Boolean>(false)
    val isFirebasePairingDocMissing: StateFlow<Boolean> = _isFirebasePairingDocMissing.asStateFlow()

    var lastAuthError: String? = null
        private set
    var lastAuthErrorCode: String? = null
        private set
    var lastFirestoreErrorCode: String? = null
        private set
    var lastFunctionsErrorCode: String? = null
        private set
    var lastFunctionsError: String? = null
        private set

    fun extractAuthErrorCode(throwable: Throwable?): String? {
        if (throwable == null) return null
        if (throwable is FirebaseAuthException) {
            return throwable.errorCode
        }
        val message = throwable.message.orEmpty()
        return when {
            message.contains("ERROR_ADMIN_RESTRICTED_OPERATION", ignoreCase = true) ||
            message.contains("ADMIN_ONLY_OPERATION", ignoreCase = true) ||
            message.contains("restricted to administrators", ignoreCase = true) ->
                "ERROR_ADMIN_RESTRICTED_OPERATION"
            message.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) ->
                "CONFIGURATION_NOT_FOUND"
            message.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                "ERROR_OPERATION_NOT_ALLOWED"
            message.contains("API_KEY_SERVICE_BLOCKED", ignoreCase = true) ->
                "API_KEY_SERVICE_BLOCKED"
            else -> throwable::class.java.simpleName
        }
    }

    fun extractFirestoreErrorCode(throwable: Throwable?): String? {
        if (throwable == null) return null
        if (throwable is FirebaseFirestoreException) {
            return throwable.code.name
        }
        val message = throwable.message.orEmpty()
        return when {
            message.contains("PERMISSION_DENIED", ignoreCase = true) -> "PERMISSION_DENIED"
            message.contains("UNAVAILABLE", ignoreCase = true) -> "UNAVAILABLE"
            message.contains("UNAUTHENTICATED", ignoreCase = true) -> "UNAUTHENTICATED"
            else -> null
        }
    }

    fun getSetupInstructionForCode(errorCode: String?): String {
        return when (errorCode) {
            "ERROR_ADMIN_RESTRICTED_OPERATION" ->
                "Anonymous sign-in or end-user sign-up is disabled in Firebase project '$projectId':\n1. Go to Firebase Console > Build > Authentication > Sign-in method.\n2. Click 'Anonymous', toggle to 'Enabled', and click 'Save'.\n3. Under Authentication > Settings > User actions, verify 'Enable create (sign-up)' is checked.\n4. Tap Retry."
            "CONFIGURATION_NOT_FOUND" ->
                "Authentication is not configured for project '$projectId':\n1. In Firebase Console, go to Build > Authentication.\n2. Click 'Get started' and enable 'Anonymous' in Sign-in method."
            "ERROR_OPERATION_NOT_ALLOWED" ->
                "Anonymous sign-in is disabled in project '$projectId':\n1. In Firebase Console, go to Authentication > Sign-in method.\n2. Enable 'Anonymous' provider."
            "API_KEY_SERVICE_BLOCKED" ->
                "The Firebase API key for project '$projectId' is blocked by API restrictions in Google Cloud Console."
            else ->
                "Ensure Anonymous Authentication is enabled for Firebase project '$projectId' and end-user sign-up is permitted."
        }
    }

    fun getSetupInstructionForFirestoreCode(errorCode: String?): String {
        return when (errorCode) {
            "PERMISSION_DENIED" ->
                "Pair a child device to enable Family Activity."
            "NOT_PAIRED" ->
                "Pair a child device to enable Family Activity."
            "UNAVAILABLE" ->
                "Firestore service is currently unavailable. Check internet connectivity."
            else ->
                "Verify Firestore database and security rules in project '$projectId'."
        }
    }

    fun mapFirebaseError(throwable: Throwable?): String {
        val authCode = extractAuthErrorCode(throwable)
        val firestoreCode = extractFirestoreErrorCode(throwable)
        return when {
            firestoreCode == "PERMISSION_DENIED" ->
                "Pair a child device to enable Family Activity."
            authCode == "ERROR_ADMIN_RESTRICTED_OPERATION" ->
                "Firebase Auth returned ERROR_ADMIN_RESTRICTED_OPERATION: Anonymous sign-in or end-user account creation is disabled in project '$projectId'."
            authCode == "CONFIGURATION_NOT_FOUND" ->
                "Firebase Authentication (Anonymous) is not enabled for project '$projectId'."
            authCode == "ERROR_OPERATION_NOT_ALLOWED" ->
                "Anonymous sign-in provider is disabled in project '$projectId'."
            !throwable?.message.isNullOrBlank() ->
                throwable?.message.orEmpty()
            else -> "Firebase service encountered an unexpected error."
        }
    }

    suspend fun ensureAnonymousAuth(forceFresh: Boolean = false): String? {
        if (!isConfigured) return null
        return runCatching {
            val auth = FirebaseAuth.getInstance()
            if (forceFresh && auth.currentUser != null) {
                auth.signOut()
            }
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
            val user = auth.currentUser
            if (user != null && user.uid.isNotBlank()) {
                lastAuthError = null
                lastAuthErrorCode = "OK"
                val masked = maskedUid
                _snapshot.value = _snapshot.value.copy(
                    maskedUid = masked,
                    authErrorCode = "OK"
                )
                masked
            } else {
                val code = "NO_USER_SESSION"
                val errorMsg = "No authenticated user session created."
                lastAuthErrorCode = code
                lastAuthError = errorMsg
                _snapshot.value = _snapshot.value.copy(
                    error = errorMsg,
                    authErrorCode = code,
                    setupInstruction = getSetupInstructionForCode(code)
                )
                null
            }
        }.getOrElse { e ->
            val authCode = extractAuthErrorCode(e)
            val mapped = mapFirebaseError(e)
            android.util.Log.w("FirebaseMonitoring", "ensureAnonymousAuth failed [$authCode]: $mapped", e)
            lastAuthErrorCode = authCode
            lastAuthError = mapped
            _snapshot.value = _snapshot.value.copy(
                error = mapped,
                authErrorCode = authCode,
                setupInstruction = getSetupInstructionForCode(authCode)
            )
            null
        }
    }

    suspend fun performFreshReset() {
        stopObserving()
        runCatching {
            if (isConfigured) {
                val auth = FirebaseAuth.getInstance()
                auth.signOut()
                lastAuthError = null
                lastAuthErrorCode = null
                lastFirestoreErrorCode = null
                _snapshot.value = MonitoringSnapshot(
                    error = "Pair a child device to enable Family Activity.",
                    maskedUid = null
                )
                ensureAnonymousAuth(forceFresh = false)
            }
        }
    }

    private suspend fun ensureAuth(): Boolean {
        return ensureAnonymousAuth() != null
    }

    suspend fun syncChild(pairingId: String, childDeviceId: String) {
        if (pairingId.isBlank()) return
        if (!ensureAuth()) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (user.uid.isBlank()) return
        runCatching {
            val usage = UsageStatsCollector(context).collectToday()
            val messages = SmsCollector(context).collectRecent(limit = 50).map { it.copy(body = it.body.take(1000)) }
            val location = readLastLocation()
            val latest = hashMapOf<String, Any?>(
                "childDeviceId" to childDeviceId,
                "updatedAt" to System.currentTimeMillis(),
                "location" to location?.let { mapOf(
                    "latitude" to it.latitude, "longitude" to it.longitude,
                    "accuracyMeters" to it.accuracyMeters, "recordedAt" to it.recordedAt
                ) },
                "usage" to usage.map { mapOf(
                    "packageName" to it.packageName, "appName" to it.appName,
                    "foregroundMillis" to it.foregroundMillis, "lastUsedAt" to it.lastUsedAt
                ) },
                "messages" to messages.map { mapOf(
                    "id" to it.id, "address" to it.address, "body" to it.body,
                    "timestamp" to it.timestamp, "direction" to it.direction
                ) }
            )
            FirebaseFirestore.getInstance().collection("pairings").document(pairingId)
                .collection("monitoring").document("latest").set(latest).await()
        }.onFailure { e ->
            android.util.Log.e("FirebaseMonitoring", "syncChild Firestore upload failed: ${e.message}", e)
        }
    }

    suspend fun registerPairingRole(
        pairingId: String,
        role: String,
        childDeviceId: String,
        parentDeviceId: String
    ): Boolean {
        if (pairingId.isBlank() || role !in setOf("parent", "child")) {
            _registrationResult.value = RoleRegistrationResult(isSuccess = false, message = "Invalid pairing ID or role")
            return false
        }
        val userUid = ensureAnonymousAuth()
        if (userUid.isNullOrBlank()) {
            _registrationResult.value = RoleRegistrationResult(isSuccess = false, message = "Firebase Authentication not completed", errorCode = lastAuthErrorCode)
            return false
        }
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null || user.uid.isBlank()) {
            _registrationResult.value = RoleRegistrationResult(isSuccess = false, message = "Active user UID is missing")
            return false
        }

        var registrationSuccess = false
        try {
            val pairingDocRef = FirebaseFirestore.getInstance().collection("pairings").document(pairingId)
            val data = if (role == "parent") {
                mapOf(
                    "pairingId" to pairingId,
                    "parentUid" to user.uid,
                    "parentDeviceId" to parentDeviceId,
                    "childDeviceId" to childDeviceId,
                    "updatedAt" to System.currentTimeMillis()
                )
            } else {
                mapOf(
                    "pairingId" to pairingId,
                    "childUid" to user.uid,
                    "childDeviceId" to childDeviceId,
                    "parentDeviceId" to parentDeviceId,
                    "updatedAt" to System.currentTimeMillis()
                )
            }
            pairingDocRef.set(data, SetOptions.merge()).await()
            registrationSuccess = true
            lastFunctionsError = null
            lastFunctionsErrorCode = "OK"
            _registrationResult.value = RoleRegistrationResult(
                isSuccess = true,
                message = "Role '$role' registered successfully in Firebase"
            )
        } catch (e: Exception) {
            val errCode = if (e is FirebaseFirestoreException) e.code.name else e::class.java.simpleName
            lastFunctionsError = e.message
            lastFunctionsErrorCode = errCode
            _registrationResult.value = RoleRegistrationResult(
                isSuccess = false,
                message = e.message ?: "Firestore role registration error",
                errorCode = errCode,
                isSparkPlanOrFunctionMissing = false
            )
            android.util.Log.w("FirebaseMonitoring", "registerPairingRole Firestore write failed [$errCode]: ${e.message}")
        }

        val docVerified = checkFirebasePairingDoc(pairingId)
        return registrationSuccess || docVerified
    }

    suspend fun checkFirebasePairingDoc(pairingId: String): Boolean {
        if (pairingId.isBlank()) {
            _isFirebasePairingVerified.value = false
            _isFirebasePairingDocMissing.value = false
            return false
        }
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid.isBlank()) {
            _isFirebasePairingVerified.value = false
            _isFirebasePairingDocMissing.value = true
            return false
        }
        return try {
            val doc = FirebaseFirestore.getInstance().collection("pairings").document(pairingId).get().await()
            val parentUid = doc.getString("parentUid")
            val matches = doc.exists() && (parentUid == user.uid)
            _isFirebasePairingVerified.value = matches
            _isFirebasePairingDocMissing.value = !matches
            matches
        } catch (e: Exception) {
            android.util.Log.w("FirebaseMonitoring", "checkFirebasePairingDoc: ${e.message}")
            _isFirebasePairingVerified.value = false
            _isFirebasePairingDocMissing.value = true
            false
        }
    }

    suspend fun verifyAndStartParentMonitoring(
        pairingId: String,
        childDeviceId: String,
        parentDeviceId: String
    ): Boolean {
        stopObserving()
        if (pairingId.isBlank()) {
            _snapshot.value = MonitoringSnapshot(
                error = "Pair a child device to enable Family Activity.",
                maskedUid = maskedUid,
                authErrorCode = if (FirebaseAuth.getInstance().currentUser != null) "OK" else null,
                firestoreErrorCode = "NOT_PAIRED"
            )
            return false
        }

        val uid = ensureAnonymousAuth()
        if (uid.isNullOrBlank()) {
            _snapshot.value = _snapshot.value.copy(
                error = lastAuthError ?: "Firebase Authentication required",
                authErrorCode = lastAuthErrorCode ?: "ERROR"
            )
            return false
        }

        // Register Parent role after Firebase Auth succeeds
        registerPairingRole(pairingId, "parent", childDeviceId, parentDeviceId)

        // Check if pairing document contains parentUid == current user
        val isVerified = checkFirebasePairingDoc(pairingId)
        if (isVerified) {
            observeForParent(pairingId, isPairingRegistered = true)
            return true
        } else {
            _snapshot.value = MonitoringSnapshot(
                error = "Local pairing connected — Firebase role registration pending.",
                maskedUid = maskedUid,
                authErrorCode = "OK",
                firestoreErrorCode = "REGISTRATION_PENDING"
            )
            return false
        }
    }

    fun observeForParent(pairingId: String, isPairingRegistered: Boolean = false) {
        listener?.remove()
        listener = null
        if (pairingId.isBlank()) {
            _snapshot.value = MonitoringSnapshot(
                error = "Pair a child device to enable Family Activity.",
                maskedUid = maskedUid,
                authErrorCode = if (FirebaseAuth.getInstance().currentUser != null) "OK" else null,
                firestoreErrorCode = "NOT_PAIRED"
            )
            return
        }
        if (!isPairingRegistered) {
            _snapshot.value = MonitoringSnapshot(
                error = "Local pairing connected — Firebase role registration pending.",
                maskedUid = maskedUid,
                authErrorCode = if (FirebaseAuth.getInstance().currentUser != null) "OK" else null,
                firestoreErrorCode = "REGISTRATION_PENDING"
            )
            return
        }
        if (!isConfigured) {
            _snapshot.value = MonitoringSnapshot(
                error = "Firebase is not configured",
                maskedUid = maskedUid
            )
            return
        }
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        if (auth == null) {
            _snapshot.value = MonitoringSnapshot(
                error = "Firebase Auth instance unavailable",
                maskedUid = maskedUid
            )
            return
        }
        if (auth.currentUser == null) {
            runCatching {
                auth.signInAnonymously()
                    .addOnSuccessListener {
                        lastAuthError = null
                        lastAuthErrorCode = "OK"
                        // Strictly verify authenticated user exists before running Firestore listeners
                        if (auth.currentUser != null && auth.currentUser?.uid?.isNotBlank() == true) {
                            observeForParent(pairingId, isPairingRegistered)
                        } else {
                            val code = "NO_USER_SESSION"
                            _snapshot.value = MonitoringSnapshot(
                                error = "Authentication succeeded but no active user session was found",
                                authErrorCode = code,
                                setupInstruction = getSetupInstructionForCode(code),
                                maskedUid = maskedUid
                            )
                        }
                    }
                    .addOnFailureListener {
                        val authCode = extractAuthErrorCode(it)
                        val mapped = mapFirebaseError(it)
                        lastAuthErrorCode = authCode
                        lastAuthError = mapped
                        _snapshot.value = MonitoringSnapshot(
                            error = mapped,
                            authErrorCode = authCode,
                            setupInstruction = getSetupInstructionForCode(authCode),
                            maskedUid = maskedUid
                        )
                    }
            }.onFailure {
                val authCode = extractAuthErrorCode(it)
                val mapped = mapFirebaseError(it)
                lastAuthErrorCode = authCode
                lastAuthError = mapped
                _snapshot.value = MonitoringSnapshot(
                    error = mapped,
                    authErrorCode = authCode,
                    setupInstruction = getSetupInstructionForCode(authCode),
                    maskedUid = maskedUid
                )
            }
            return
        }

        // Guaranteed: authenticated user exists
        val currentUser = auth.currentUser
        if (currentUser == null || currentUser.uid.isBlank()) {
            _snapshot.value = MonitoringSnapshot(
                error = "User not authenticated before listening to Firestore",
                maskedUid = maskedUid
            )
            return
        }

        runCatching {
            listener = FirebaseFirestore.getInstance().collection("pairings").document(pairingId)
                .collection("monitoring").document("latest")
                .addSnapshotListener { doc, error ->
                    if (error != null) {
                        val firestoreCode = extractFirestoreErrorCode(error)
                        lastFirestoreErrorCode = firestoreCode
                        val mapped = if (firestoreCode == "PERMISSION_DENIED") {
                            _isFirebasePairingVerified.value = false
                            _isFirebasePairingDocMissing.value = true
                            "Local pairing connected — Firebase role registration pending."
                        } else {
                            mapFirebaseError(error)
                        }
                        _snapshot.value = _snapshot.value.copy(
                            error = mapped,
                            firestoreErrorCode = firestoreCode,
                            setupInstruction = getSetupInstructionForFirestoreCode(firestoreCode),
                            maskedUid = maskedUid
                        )
                        return@addSnapshotListener
                    }
                    lastFirestoreErrorCode = null
                    if (doc == null || !doc.exists()) {
                        _snapshot.value = MonitoringSnapshot(
                            error = "Waiting for child sync",
                            authErrorCode = "OK",
                            firestoreErrorCode = null,
                            maskedUid = maskedUid
                        )
                        return@addSnapshotListener
                    }
                    val loc = (doc.get("location") as? Map<*, *>)?.let {
                        ChildLocation(
                            (it["latitude"] as? Number)?.toDouble() ?: 0.0,
                            (it["longitude"] as? Number)?.toDouble() ?: 0.0,
                            (it["accuracyMeters"] as? Number)?.toFloat() ?: 0f,
                            (it["recordedAt"] as? Number)?.toLong() ?: 0L
                        )
                    }
                    val usage = (doc.get("usage") as? List<*>)?.mapNotNull { raw ->
                        val m = raw as? Map<*, *> ?: return@mapNotNull null
                        AppUsageItem(m["packageName"] as? String ?: "", m["appName"] as? String ?: "",
                            (m["foregroundMillis"] as? Number)?.toLong() ?: 0L,
                            (m["lastUsedAt"] as? Number)?.toLong() ?: 0L)
                    }.orEmpty()
                    val sms = (doc.get("messages") as? List<*>)?.mapNotNull { raw ->
                        val m = raw as? Map<*, *> ?: return@mapNotNull null
                        SmsItem(m["id"] as? String ?: "", m["address"] as? String ?: "Unknown",
                            m["body"] as? String ?: "", (m["timestamp"] as? Number)?.toLong() ?: 0L,
                            m["direction"] as? String ?: "received")
                    }.orEmpty()
                    _snapshot.value = MonitoringSnapshot(
                        childDeviceId = doc.getString("childDeviceId") ?: "",
                        location = loc,
                        usage = usage,
                        messages = sms,
                        updatedAt = doc.getLong("updatedAt") ?: 0L,
                        error = null,
                        authErrorCode = "OK",
                        firestoreErrorCode = null,
                        maskedUid = maskedUid
                    )
                }
        }.onFailure { e ->
            val mapped = mapFirebaseError(e)
            _snapshot.value = MonitoringSnapshot(
                error = mapped,
                maskedUid = maskedUid
            )
        }
    }

    fun stopObserving() { listener?.remove(); listener = null }

    private suspend fun readLastLocation(): ChildLocation? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val token = CancellationTokenSource()
            val fresh = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token).await()
                ?: client.lastLocation.await()
            fresh?.let { ChildLocation(it.latitude, it.longitude, it.accuracy, it.time) }
        }.getOrNull()
    }
}
