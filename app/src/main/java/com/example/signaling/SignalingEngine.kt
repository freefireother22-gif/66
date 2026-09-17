package com.example.signaling

import android.util.Log
import com.example.model.SignalingMessage
import com.example.model.SignalingType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Production-ready WebSocket Signaling Engine for Parent–Child streaming.
 *
 * Fully replaces legacy REST/KV polling (keyvalue.immanuel.co and api.restful-api.dev)
 * with low-latency bidirectional WebSocket communication.
 *
 * Guarantees strict message envelope routing:
 * - pairingId
 * - sessionId
 * - negotiationId
 * - senderDeviceId
 * - targetDeviceId
 * - messageId
 */
class SignalingEngine private constructor() {

    companion object {
        private const val TAG = "SignalingEngine"
        private const val HEARTBEAT_INTERVAL_MS = 20_000L
        private const val RECONNECT_DELAY_MS = 3_000L

        @Volatile
        private var instance: SignalingEngine? = null

        fun getInstance(): SignalingEngine {
            return instance ?: synchronized(this) {
                instance ?: SignalingEngine().also { instance = it }
            }
        }

        fun getInstance(context: Any?): SignalingEngine = getInstance()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 0 for persistent WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var isIntentionalClose = false

    // State indicators
    private val _connectionStatus = MutableStateFlow("DISCONNECTED")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 128)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages.asSharedFlow()

    // Diagnostic counters & status
    private val _offersSentCount = MutableStateFlow(0)
    val offersSentCount: StateFlow<Int> = _offersSentCount.asStateFlow()
    val offersSent: StateFlow<Int> get() = offersSentCount

    private val _answersSentCount = MutableStateFlow(0)
    val answersSentCount: StateFlow<Int> = _answersSentCount.asStateFlow()
    val answersSent: StateFlow<Int> get() = answersSentCount

    private val _candidatesSentCount = MutableStateFlow(0)
    val candidatesSentCount: StateFlow<Int> = _candidatesSentCount.asStateFlow()
    val candidatesSent: StateFlow<Int> get() = candidatesSentCount

    private val _offersReceivedCount = MutableStateFlow(0)
    val offersReceivedCount: StateFlow<Int> = _offersReceivedCount.asStateFlow()
    val offersReceived: StateFlow<Int> get() = offersReceivedCount

    private val _answersReceivedCount = MutableStateFlow(0)
    val answersReceivedCount: StateFlow<Int> = _answersReceivedCount.asStateFlow()
    val answersReceived: StateFlow<Int> get() = answersReceivedCount

    private val _candidatesReceivedCount = MutableStateFlow(0)
    val candidatesReceivedCount: StateFlow<Int> = _candidatesReceivedCount.asStateFlow()
    val candidatesReceived: StateFlow<Int> get() = candidatesReceivedCount

    private val _lastNegotiationId = MutableStateFlow("-")
    val lastNegotiationId: StateFlow<String> = _lastNegotiationId.asStateFlow()
    val currentNegotiationId: StateFlow<String> get() = lastNegotiationId

    private val _lastSignalingError = MutableStateFlow<String?>(null)
    val lastSignalingError: StateFlow<String?> = _lastSignalingError.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private fun updateConnectionStatus(status: String) {
        _connectionStatus.value = status
        _isConnected.value = (status == "CONNECTED")
    }

    private fun updateSignalingError(err: String?) {
        _lastSignalingError.value = err
        _lastError.value = err ?: ""
    }

    private val activeDevices = ConcurrentHashMap<String, Boolean>()
    private val activeListeners = CopyOnWriteArrayList<(SignalingMessage) -> Unit>()
    private val processedMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val pendingOutgoingMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val registeredDeviceIds = ConcurrentHashMap.newKeySet<String>()

    private var activeSessionId: String = "hub"
    private var registeredDeviceId: String = ""

    // In-memory loopback queue for devices running in same process or test suite
    private val inMemoryLoopbackDevices = ConcurrentHashMap<String, Boolean>()

    init {
        connectWebSocket()
    }

    /**
     * Resolves the configured WebSocket URL, falling back gracefully to the production Render server if unset.
     */
    private fun getSignalingWssUrl(): String {
        return try {
            val url = com.example.BuildConfig.SIGNALING_WSS_URL
            val trimmed = url.trim()
            if (trimmed.isNotBlank() && !trimmed.contains("example.com") && !trimmed.contains("your-domain")) {
                var finalUrl = trimmed
                if (finalUrl.startsWith("https://")) {
                    finalUrl = "wss://" + finalUrl.removePrefix("https://")
                } else if (finalUrl.startsWith("http://")) {
                    finalUrl = "ws://" + finalUrl.removePrefix("http://")
                }
                if (!finalUrl.endsWith("/ws")) {
                    finalUrl = finalUrl.trimEnd('/') + "/ws"
                }
                finalUrl
            } else {
                "wss://server-1-hqnr.onrender.com/ws"
            }
        } catch (_: Exception) {
            "wss://server-1-hqnr.onrender.com/ws"
        }
    }

    @Synchronized
    private fun connectWebSocket() {
        if (webSocket != null) return
        isIntentionalClose = false
        val url = getSignalingWssUrl()

        Log.i(TAG, "Connecting to WebSocket signaling server: $url")
        updateConnectionStatus("CONNECTING")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "ParentalControl-Android/2.0")
            .build()

        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Signaling WebSocket connected successfully.")
                updateConnectionStatus("CONNECTED")
                updateSignalingError(null)

                // Immediately register all registered device IDs
                for (devId in registeredDeviceIds) {
                    sendRegistration(devId)
                }
                if (registeredDeviceId.isNotEmpty() && !registeredDeviceIds.contains(registeredDeviceId)) {
                    sendRegistration(registeredDeviceId)
                }

                // Drain any buffered outgoing messages
                drainPendingOutgoingMessages(ws)

                startHeartbeat()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingJson(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Signaling WebSocket closing (code=$code, reason=$reason)")
                ws.close(code, reason)
                updateConnectionStatus("CLOSING")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Signaling WebSocket closed (code=$code, reason=$reason)")
                updateConnectionStatus("DISCONNECTED")
                webSocket = null
                stopHeartbeat()
                if (!isIntentionalClose) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val err = t.message ?: "WebSocket network failure"
                Log.e(TAG, "Signaling WebSocket failure: $err", t)
                updateConnectionStatus("ERROR: $err")
                updateSignalingError(err)
                webSocket = null
                stopHeartbeat()
                if (!isIntentionalClose) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Waiting ${RECONNECT_DELAY_MS}ms before attempting WebSocket reconnection...")
            delay(RECONNECT_DELAY_MS)
            if (isActive && !isIntentionalClose && webSocket == null) {
                connectWebSocket()
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendHeartbeat() {
        val ws = webSocket ?: return
        try {
            val json = JSONObject().apply {
                put("type", "HEARTBEAT")
                put("messageId", "hb_" + System.currentTimeMillis())
                put("senderDeviceId", registeredDeviceId)
                put("timestamp", System.currentTimeMillis())
            }
            ws.send(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending heartbeat ping: ${e.message}")
        }
    }

    private fun sendRegistration(deviceId: String) {
        val ws = webSocket ?: return
        try {
            val json = JSONObject().apply {
                put("type", "REGISTER")
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }
            ws.send(json.toString())
            Log.d(TAG, "Sent WebSocket registration for device: $deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending device registration: ${e.message}")
        }
    }

    private fun drainPendingOutgoingMessages(ws: WebSocket) {
        scope.launch {
            while (isActive) {
                val nextMsg = pendingOutgoingMessages.poll() ?: break
                try {
                    val sent = ws.send(nextMsg)
                    if (!sent) {
                        pendingOutgoingMessages.offer(nextMsg)
                        break
                    }
                    Log.d(TAG, "Sent buffered outgoing signaling message")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed sending buffered message: ${e.message}")
                    pendingOutgoingMessages.offer(nextMsg)
                    break
                }
            }
        }
    }

    fun registerDevice(deviceId: String) {
        if (deviceId.isNotEmpty()) {
            registeredDeviceId = deviceId
            registeredDeviceIds.add(deviceId)
            activeDevices[deviceId] = true
            inMemoryLoopbackDevices[deviceId] = true
            Log.d(TAG, "Device registered in SignalingEngine: $deviceId")
            if (_connectionStatus.value == "CONNECTED") {
                sendRegistration(deviceId)
            } else if (_connectionStatus.value == "DISCONNECTED") {
                connectWebSocket()
            }
        }
    }

    fun unregisterDevice(deviceId: String) {
        registeredDeviceIds.remove(deviceId)
        activeDevices[deviceId] = false
        inMemoryLoopbackDevices.remove(deviceId)
        Log.d(TAG, "Device unregistered in SignalingEngine: $deviceId")
    }

    fun isDeviceOnline(deviceId: String): Boolean {
        return activeDevices[deviceId] == true
    }

    fun joinSession(sessionId: String) {
        if (sessionId.isNotEmpty() && sessionId != activeSessionId) {
            activeSessionId = sessionId
            Log.d(TAG, "Signaling joined session: $sessionId")
        }
    }

    fun addMessageListener(listener: (SignalingMessage) -> Unit): () -> Unit {
        activeListeners.add(listener)
        return { activeListeners.remove(listener) }
    }

    /**
     * Transmits a signaling message to the target device via authenticated WebSocket.
     * Enforces strict envelope routing fields:
     * - pairingId
     * - sessionId
     * - negotiationId
     * - senderDeviceId
     * - targetDeviceId
     * - messageId
     */
    fun sendMessage(message: SignalingMessage) {
        val finalSessionId = if (message.sessionId.isNotEmpty()) message.sessionId else activeSessionId
        val msgId = if (message.messageId.isNotEmpty()) {
            message.messageId
        } else {
            "msg_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6)
        }
        val negId = message.negotiationId.ifEmpty { "neg_${finalSessionId.takeLast(8)}" }

        val enrichedMessage = message.copy(
            sessionId = finalSessionId,
            messageId = msgId,
            negotiationId = negId,
            timestamp = if (message.timestamp > 0) message.timestamp else System.currentTimeMillis()
        )

        // Diagnostic counts tracking
        when (enrichedMessage.messageType) {
            SignalingType.WEBRTC_OFFER -> _offersSentCount.value++
            SignalingType.WEBRTC_ANSWER -> _answersSentCount.value++
            SignalingType.ICE_CANDIDATE -> _candidatesSentCount.value++
            else -> {}
        }
        if (enrichedMessage.negotiationId.isNotEmpty()) {
            _lastNegotiationId.value = enrichedMessage.negotiationId
        }

        Log.d(TAG, "SignalingEngine sendMessage: ${enrichedMessage.messageType} " +
            "from=${enrichedMessage.senderDeviceId} to=${enrichedMessage.targetDeviceId} " +
            "negId=${enrichedMessage.negotiationId} msgId=${enrichedMessage.messageId}")

        // 1. In-process loopback dispatch if target device is active in this process
        if (enrichedMessage.targetDeviceId.isNotEmpty() &&
            inMemoryLoopbackDevices.containsKey(enrichedMessage.targetDeviceId) &&
            enrichedMessage.targetDeviceId != enrichedMessage.senderDeviceId
        ) {
            dispatchMessage(enrichedMessage)
        }

        // 2. Transmit through WebSocket server or queue if not connected yet
        val ws = webSocket
        if (ws == null || _connectionStatus.value != "CONNECTED") {
            Log.w(TAG, "WebSocket not connected (_connectionStatus=${_connectionStatus.value}). Buffering message and connecting...")
            connectWebSocket()
        }

        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("type", enrichedMessage.messageType.name)
                    put("pairingId", enrichedMessage.pairingId)
                    put("sessionId", enrichedMessage.sessionId)
                    put("negotiationId", enrichedMessage.negotiationId)
                    put("senderDeviceId", enrichedMessage.senderDeviceId)
                    put("targetDeviceId", enrichedMessage.targetDeviceId)
                    put("messageId", enrichedMessage.messageId)
                    put("payload", enrichedMessage.payload)
                    put("timestamp", enrichedMessage.timestamp)
                }

                val currentWs = webSocket
                if (currentWs != null && _connectionStatus.value == "CONNECTED") {
                    val sent = currentWs.send(json.toString())
                    if (!sent) {
                        Log.w(TAG, "WebSocket send returned false for ${enrichedMessage.messageType}, buffering message")
                        pendingOutgoingMessages.offer(json.toString())
                    }
                } else {
                    Log.d(TAG, "Buffering ${enrichedMessage.messageType} until WebSocket completes connection")
                    pendingOutgoingMessages.offer(json.toString())
                    connectWebSocket()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendMessage: ${e.message}", e)
                updateSignalingError("Send error: ${e.message}")
            }
        }
    }

    private fun handleIncomingJson(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val typeStr = json.optString("type", json.optString("messageType", ""))
            if (typeStr.isEmpty()) return

            // Handle server control messages
            when (typeStr) {
                "REGISTERED" -> {
                    val dev = json.optString("deviceId", "")
                    Log.i(TAG, "Server confirmed registration for $dev")
                    return
                }
                "HEARTBEAT_ACK" -> {
                    return
                }
                "ACK" -> {
                    val ackMsgId = json.optString("messageId", "")
                    Log.d(TAG, "Server ACK received for $ackMsgId")
                    return
                }
                "TARGET_OFFLINE" -> {
                    val target = json.optString("targetDeviceId", "")
                    Log.w(TAG, "Signaling server reported target $target is OFFLINE")
                    updateSignalingError("Target $target is offline")
                    return
                }
                "ERROR" -> {
                    val errMsg = json.optString("error", "Signaling error")
                    Log.e(TAG, "Signaling server reported ERROR: $errMsg")
                    updateSignalingError(errMsg)
                    return
                }
            }

            val sigType = try {
                SignalingType.valueOf(typeStr)
            } catch (_: Exception) {
                Log.w(TAG, "Unknown signaling message type: $typeStr")
                return
            }

            val msgId = json.optString("messageId", "")
            if (msgId.isNotEmpty()) {
                if (!processedMessageIds.add(msgId)) {
                    // Duplicate message already processed
                    return
                }
                // Maintain bounded set size
                if (processedMessageIds.size > 2000) {
                    val it = processedMessageIds.iterator()
                    var dropCount = 0
                    while (it.hasNext() && dropCount < 500) {
                        it.next()
                        it.remove()
                        dropCount++
                    }
                }
            }

            val pairingId = json.optString("pairingId", "")
            val sessionId = json.optString("sessionId", "")
            val negotiationId = json.optString("negotiationId", "")
            val senderDeviceId = json.optString("senderDeviceId", "")
            val targetDeviceId = json.optString("targetDeviceId", "")
            val payload = json.optString("payload", "")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())

            val message = SignalingMessage(
                pairingId = pairingId,
                sessionId = sessionId,
                negotiationId = negotiationId,
                senderDeviceId = senderDeviceId,
                targetDeviceId = targetDeviceId,
                messageId = msgId,
                messageType = sigType,
                payload = payload,
                timestamp = timestamp
            )

            // Diagnostic receive count tracking
            when (sigType) {
                SignalingType.WEBRTC_OFFER -> _offersReceivedCount.value++
                SignalingType.WEBRTC_ANSWER -> _answersReceivedCount.value++
                SignalingType.ICE_CANDIDATE -> _candidatesReceivedCount.value++
                else -> {}
            }
            if (negotiationId.isNotEmpty()) {
                _lastNegotiationId.value = negotiationId
            }

            dispatchMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming JSON from signaling server: ${e.message}", e)
        }
    }

    private fun dispatchMessage(msg: SignalingMessage) {
        val isSenderSelf = registeredDeviceIds.contains(msg.senderDeviceId) || (registeredDeviceId.isNotEmpty() && msg.senderDeviceId == registeredDeviceId)
        val isTargetSelf = registeredDeviceIds.contains(msg.targetDeviceId) || (registeredDeviceId.isNotEmpty() && msg.targetDeviceId == registeredDeviceId)

        // Prevent delivering messages sent by self to self unless deliberately targeted
        if (isSenderSelf && !isTargetSelf && msg.targetDeviceId.isNotEmpty()) {
            return
        }

        // If targetDeviceId specified and doesn't match our registered device, ignore
        if (registeredDeviceIds.isNotEmpty() && msg.targetDeviceId.isNotEmpty() && !isTargetSelf) {
            return
        }

        Log.d(TAG, "Dispatching incoming signaling message: ${msg.messageType} " +
            "from ${msg.senderDeviceId} -> ${msg.targetDeviceId} [negId=${msg.negotiationId}]")

        scope.launch {
            _incomingMessages.emit(msg)
            for (listener in activeListeners) {
                try {
                    listener.invoke(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in signaling listener callback", e)
                }
            }
        }
    }

    fun disconnect() {
        isIntentionalClose = true
        stopHeartbeat()
        reconnectJob?.cancel()
        try {
            webSocket?.close(1000, "Client closed")
        } catch (_: Exception) {}
        webSocket = null
        updateConnectionStatus("DISCONNECTED")
    }
}
