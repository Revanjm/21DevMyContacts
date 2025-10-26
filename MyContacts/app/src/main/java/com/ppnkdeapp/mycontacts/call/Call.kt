//package com.ppnkdeapp.mycontacts.call
//
//import android.content.Context
//import android.util.Log
//import io.socket.client.IO
//import io.socket.client.Socket
//import org.json.JSONObject
//import org.webrtc.*
//import org.webrtc.audio.JavaAudioDeviceModule
//import java.util.*
//
//class WebRTCClient(
//    private val context: Context,
//    private val serverUrl: String,
//    private val userId: String,
//    private val listener: WebRTCListener
//) {
//    private var socket: Socket? = null
//    private var peerConnection: PeerConnection? = null
//    private var peerConnectionFactory: PeerConnectionFactory? = null
//    private var localAudioTrack: AudioTrack? = null
//    private var localVideoTrack: VideoTrack? = null
//    private var remoteAudioTrack: AudioTrack? = null
//    private var remoteVideoTrack: VideoTrack? = null
//    private var audioSource: AudioSource? = null
//    private var videoSource: VideoSource? = null
//    private var targetUserId: String? = null
//    private var isCalling = false
//    private var isIncomingCall = false
//    private var callRetryCount = 0
//    private val callTimeout = 30000L // 30 секунд ожидания
//    private val callCheckInterval = 5000L // Проверка каждые 5 секунд
//    private var callRetryHandler: android.os.Handler? = null
//    private var callRetryRunnable: Runnable? = null
//    private var currentCallId: String? = null
//
//    // Новые переменные для управления подключением
//    private var connectionRetryCount = 0
//    private val maxConnectionRetries = 5
//    private val connectionRetryInterval = 3000L // 3 секунды
//    private var connectionRetryHandler: android.os.Handler? = null
//    private var connectionRetryRunnable: Runnable? = null
//    private var isConnecting = false
//
//    // Улучшенные ICE серверы
//    private val iceServers = listOf(
//        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
//        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
//    )
//
//    interface WebRTCListener {
//        fun onRegistrationSuccess(connectedUsers: List<String>)
//        fun onUserConnected(userId: String)
//        fun onUserDisconnected(userId: String)
//        fun onIncomingCall(fromUserId: String, callId: String)
//        fun onCallAccepted(toUserId: String)
//        fun onCallRejected(fromUserId: String)
//        fun onCallEnded(fromUserId: String)
//        fun onCallFailed(error: String)
//        fun onRemoteStreamAdded(stream: MediaStream)
//        fun onLocalStreamAdded(stream: MediaStream)
//        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
//        fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState)
//        // Новые callback'и для управления подключением
//        fun onSocketConnected()
//        fun onSocketDisconnected()
//        fun onSocketError(error: String)
//    }
//
//    private var isInitialized = false
//
//    init {
//        initialize()
//    }
//
//    private fun initialize() {
//        if (!initializePeerConnectionFactory()) {
//            listener.onCallFailed("WebRTC initialization failed")
//            return
//        }
//        initializeSocket()
//        isInitialized = true
//    }
//
//    private fun initializePeerConnectionFactory(): Boolean {
//        return try {
//            try {
//                val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
//                    .setEnableInternalTracer(true)
//                    .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
//                    .createInitializationOptions()
//                PeerConnectionFactory.initialize(initializationOptions)
//                Log.d(TAG, "✅ PeerConnectionFactory initialized")
//            } catch (e: IllegalStateException) {
//                Log.d(TAG, "✅ PeerConnectionFactory already initialized")
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ PeerConnectionFactory initialization error: ${e.message}")
//                return false
//            }
//
//            val options = PeerConnectionFactory.Options()
//
//            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
//                .setUseHardwareAcousticEchoCanceler(false)
//                .setUseHardwareNoiseSuppressor(false)
//                .setUseStereoInput(true)
//                .setUseStereoOutput(true)
//                .createAudioDeviceModule()
//
//            peerConnectionFactory = PeerConnectionFactory.builder()
//                .setOptions(options)
//                .setAudioDeviceModule(audioDeviceModule)
//                .createPeerConnectionFactory()
//
//            if (peerConnectionFactory == null) {
//                Log.e(TAG, "❌ Failed to create PeerConnectionFactory")
//                return false
//            }
//
//            Log.d(TAG, "✅ PeerConnectionFactory created successfully")
//            true
//
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ PeerConnectionFactory creation failed: ${e.message}")
//            false
//        }
//    }
//
//    private fun initializeSocket() {
//        try {
//            if (isConnecting) {
//                Log.d(TAG, "⚠️ Already connecting to socket, skipping")
//                return
//            }
//
//            isConnecting = true
//            stopConnectionRetryProcess()
//
//            val opts = IO.Options().apply {
//                forceNew = true
//                reconnection = false // Управляем переподключением вручную
//                timeout = 10000
//            }
//
//            socket = IO.socket(serverUrl, opts)
//            setupSocketListeners()
//
//            Log.d(TAG, "🔄 Connecting to socket: $serverUrl")
//            socket?.connect()
//
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Socket initialization error: ${e.message}")
//            handleSocketError("Socket initialization failed: ${e.message}")
//        }
//    }
//
//    // НОВЫЙ МЕТОД: Повторное подключение
//    private fun startConnectionRetryProcess() {
//        connectionRetryHandler = android.os.Handler(context.mainLooper)
//        connectionRetryRunnable = object : Runnable {
//            override fun run() {
//                if (connectionRetryCount < maxConnectionRetries) {
//                    Log.d(TAG, "🔄 Connection retry ${connectionRetryCount + 1}/$maxConnectionRetries")
//                    connectionRetryCount++
//                    initializeSocket()
//                } else {
//                    Log.e(TAG, "❌ Max connection retries reached")
//                    listener.onSocketError("Unable to connect to server after $maxConnectionRetries attempts")
//                }
//            }
//        }
//        connectionRetryHandler?.postDelayed(connectionRetryRunnable!!, connectionRetryInterval)
//    }
//
//    private fun stopConnectionRetryProcess() {
//        connectionRetryHandler?.removeCallbacksAndMessages(null)
//        connectionRetryRunnable = null
//        connectionRetryCount = 0
//    }
//
//    private fun setupSocketListeners() {
//        socket?.on(Socket.EVENT_CONNECT) {
//            Log.d(TAG, "✅ Socket connected")
//            isConnecting = false
//            stopConnectionRetryProcess()
//            listener.onSocketConnected()
//            registerUser()
//        }
//
//        socket?.on(Socket.EVENT_DISCONNECT) {
//            Log.d(TAG, "🔴 Socket disconnected")
//            isConnecting = false
//            listener.onSocketDisconnected()
//
//            // Автоматическое переподключение
//            if (!isCalling) {
//                startConnectionRetryProcess()
//            }
//        }
//
//        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
//            val errorMessage = args.joinToString(", ")
//            Log.e(TAG, "❌ Socket connection error: $errorMessage")
//            handleSocketError("Connection error: $errorMessage")
//        }
//
//        // Исправлено: используем правильное имя события для ошибок
//        socket?.on("error") { args ->
//            val errorMessage = args.joinToString(", ")
//            Log.e(TAG, "❌ Socket error: $errorMessage")
//            handleSocketError("Socket error: $errorMessage")
//        }
//
//        // Остальные обработчики событий...
//        socket?.on("user_available") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val availableUserId = it.optString("userId")
//                    val callId = it.optString("callId")
//
//                    if (availableUserId == targetUserId && callId == currentCallId && isCalling) {
//                        Log.d(TAG, "✅ User $availableUserId is now available, creating offer")
//                        stopCallTimeoutProcess()
//                        createOffer()
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing user_available: ${e.message}")
//            }
//        }
//
//        socket?.on("registration_success") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val connectedUsersList = mutableListOf<String>()
//                    val connectedUsersArray = it.optJSONArray("connectedUsers")
//                    if (connectedUsersArray != null) {
//                        for (i in 0 until connectedUsersArray.length()) {
//                            connectedUsersList.add(connectedUsersArray.getString(i))
//                        }
//                    }
//                    Log.d(TAG, "✅ Registered successfully: $userId")
//                    listener.onRegistrationSuccess(connectedUsersList)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing registration_success: ${e.message}")
//            }
//        }
//
//        socket?.on("incoming_call") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val fromUserId = it.optString("fromUserId")
//                    val callId = it.optString("callId")
//                    if (fromUserId.isNotEmpty() && callId.isNotEmpty()) {
//                        Log.d(TAG, "📞 Incoming call from: $fromUserId")
//                        showIncomingCallToast(fromUserId)
//                        isIncomingCall = true
//                        targetUserId = fromUserId
//                        currentCallId = callId
//                        listener.onIncomingCall(fromUserId, callId)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing incoming_call: ${e.message}")
//            }
//        }
//
//        socket?.on("call_accepted") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val toUserId = it.optString("toUserId")
//                    val callId = it.optString("callId")
//
//                    Log.d(TAG, "✅ Call accepted by: $toUserId for callId: $callId")
//
//                    if (toUserId == targetUserId && callId == currentCallId) {
//                        stopCallTimeoutProcess()
//                        listener.onCallAccepted(toUserId)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing call_accepted: ${e.message}")
//            }
//        }
//
//        socket?.on("call_rejected") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val fromUserId = it.optString("fromUserId")
//                    Log.d(TAG, "❌ Call rejected by: $fromUserId")
//                    cleanupCall()
//                    listener.onCallRejected(fromUserId)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing call_rejected: ${e.message}")
//            }
//        }
//
//        socket?.on("call_ended") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val fromUserId = it.optString("fromUserId")
//                    Log.d(TAG, "📞 Call ended by: $fromUserId")
//                    cleanupCall()
//                    listener.onCallEnded(fromUserId)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing call_ended: ${e.message}")
//            }
//        }
//
//        socket?.on("call_failed") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val error = it.optString("error", "Unknown error")
//                    Log.e(TAG, "❌ Call failed: $error")
//                    cleanupCall()
//                    listener.onCallFailed(error)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing call_failed: ${e.message}")
//            }
//        }
//
//        socket?.on("webrtc_offer") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val fromUserId = it.optString("fromUserId")
//                    val offer = it.optJSONObject("offer")
//                    if (fromUserId.isNotEmpty() && offer != null) {
//                        Log.d(TAG, "📨 Received offer from: $fromUserId")
//                        handleOffer(fromUserId, offer)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing webrtc_offer: ${e.message}")
//            }
//        }
//
//        socket?.on("webrtc_answer") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val fromUserId = it.optString("fromUserId")
//                    val answer = it.optJSONObject("answer")
//                    if (fromUserId.isNotEmpty() && answer != null) {
//                        Log.d(TAG, "📨 Received answer from: $fromUserId")
//                        handleAnswer(answer)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing webrtc_answer: ${e.message}")
//            }
//        }
//
//        socket?.on("webrtc_ice_candidate") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val fromUserId = it.optString("fromUserId")
//                    val candidate = it.optJSONObject("candidate")
//                    if (fromUserId.isNotEmpty() && candidate != null) {
//                        Log.d(TAG, "❄️ Received ICE candidate from: $fromUserId")
//                        handleIceCandidate(candidate)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing webrtc_ice_candidate: ${e.message}")
//            }
//        }
//
//        socket?.on("user_connected") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val connectedUserId = it.optString("userId")
//                    if (connectedUserId.isNotEmpty()) {
//                        Log.d(TAG, "👤 User connected: $connectedUserId")
//                        listener.onUserConnected(connectedUserId)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing user_connected: ${e.message}")
//            }
//        }
//
//        socket?.on("user_disconnected") { args ->
//            try {
//                val data = args[0] as? JSONObject
//                data?.let {
//                    val disconnectedUserId = it.optString("userId")
//                    if (disconnectedUserId.isNotEmpty()) {
//                        Log.d(TAG, "👤 User disconnected: $disconnectedUserId")
//                        listener.onUserDisconnected(disconnectedUserId)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error parsing user_disconnected: ${e.message}")
//            }
//        }
//    }
//
//    // НОВЫЙ МЕТОД: Обработка ошибок сокета
//    private fun handleSocketError(error: String) {
//        isConnecting = false
//        listener.onSocketError(error)
//
//        // Переподключаемся только если не в звонке
//        if (!isCalling) {
//            startConnectionRetryProcess()
//        }
//    }
//
//    // ОБНОВЛЕННЫЙ МЕТОД: Проверка состояния сокета перед отправкой
//    private fun emitSafe(event: String, data: JSONObject) {
//        try {
//            if (socket?.connected() == true) {
//                socket?.emit(event, data)
//                Log.d(TAG, "📤 Emitted $event: ${data.toString().take(100)}...")
//            } else {
//                Log.w(TAG, "⚠️ Socket not connected, cannot emit $event")
//                // Можно добавить логику для буферизации событий
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error emitting $event: ${e.message}")
//        }
//    }
//
//    private fun showIncomingCallToast(fromUserId: String) {
//        android.os.Handler(context.mainLooper).post {
//            android.widget.Toast.makeText(
//                context,
//                "📞 Входящий звонок от: $fromUserId",
//                android.widget.Toast.LENGTH_LONG
//            ).show()
//        }
//    }
//
//    private fun registerUser() {
//        try {
//            if (socket?.connected() == true) {
//                socket?.emit("register", userId)
//                Log.d(TAG, "📤 Registered user: $userId")
//            } else {
//                Log.w(TAG, "⚠️ Cannot register - socket not connected")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error registering user: ${e.message}")
//        }
//    }
//
//    fun callUser(targetUserId: String) {
//        if (isCalling) {
//            Log.w(TAG, "Already in call, ignoring new call request")
//            return
//        }
//
//        if (!isInitialized) {
//            Log.e(TAG, "WebRTC client not initialized")
//            listener.onCallFailed("WebRTC not initialized")
//            return
//        }
//
//        // Проверяем подключение к сокету
//        if (socket?.connected() != true) {
//            Log.e(TAG, "❌ Cannot call - socket not connected")
//            listener.onCallFailed("No connection to server")
//            return
//        }
//
//        this.targetUserId = targetUserId
//        this.isCalling = true
//        this.callRetryCount = 0
//        this.currentCallId = UUID.randomUUID().toString()
//
//        Log.d(TAG, "📞 Calling $targetUserId with callId: ${currentCallId} (30s timeout)")
//
//        startSingleCallWithTimeout()
//    }
//
//    // ОБНОВЛЕННЫЙ МЕТОД: Запуск одного длинного звонка с таймаутом 30 секунд
//    private fun startSingleCallWithTimeout() {
//        callRetryHandler = android.os.Handler(context.mainLooper)
//
//        // Основной таймаут звонка - 30 секунд
//        callRetryHandler?.postDelayed({
//            if (isCalling && targetUserId != null) {
//                Log.d(TAG, "❌ Call timeout (30s) reached for $targetUserId")
//                cleanupCall()
//                listener.onCallFailed("User not available after 30 seconds")
//            }
//        }, callTimeout)
//
//        // Запускаем процесс звонка
//        attemptSingleCall()
//    }
//
//    // НОВЫЙ МЕТОД: Одна попытка звонка с длительным ожиданием
//    private fun attemptSingleCall() {
//        try {
//            Log.d(TAG, "🔄 Starting single call attempt to $targetUserId (waiting 30s for response)")
//
//            if (!createPeerConnection() || !createLocalMediaStream()) {
//                Log.e(TAG, "Failed to initialize media for call")
//                cleanupCall()
//                listener.onCallFailed("Failed to initialize call media")
//                return
//            }
//
//            // Отправляем один запрос на звонок
//            val callData = JSONObject().apply {
//                put("fromUserId", userId)
//                put("toUserId", targetUserId)
//                put("callId", currentCallId)
//                put("timeout", callTimeout)
//            }
//
//            emitSafe("initiate_call", callData)
//
//            // Дополнительная проверка состояния каждые 5 секунд
//            startCallStatusCheck()
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error in call attempt: ${e.message}")
//            cleanupCall()
//            listener.onCallFailed("Call setup error: ${e.message}")
//        }
//    }
//
//    // НОВЫЙ МЕТОД: Проверка статуса звонка
//    private fun startCallStatusCheck() {
//        val statusCheckHandler = android.os.Handler(context.mainLooper)
//        var checkCount = 0
//        val maxChecks = (callTimeout / callCheckInterval).toInt() // Проверяем каждые 5 секунд
//
//        val statusCheckRunnable = object : Runnable {
//            override fun run() {
//                if (!isCalling || targetUserId == null) {
//                    return
//                }
//
//                checkCount++
//                if (checkCount <= maxChecks) {
//                    Log.d(TAG, "⏳ Call status check $checkCount/$maxChecks - waiting for user response")
//
//                    // Можно отправить статус проверки на сервер (опционально)
//                    val statusData = JSONObject().apply {
//                        put("callId", currentCallId)
//                        put("fromUserId", userId)
//                        put("toUserId", targetUserId)
//                        put("checkCount", checkCount)
//                    }
//                    emitSafe("call_status_check", statusData)
//
//                    statusCheckHandler.postDelayed(this, callCheckInterval)
//                }
//            }
//        }
//
//        statusCheckHandler.postDelayed(statusCheckRunnable, callCheckInterval)
//    }
//
//    // НОВЫЙ МЕТОД: Остановка процесса таймаута звонка
//    private fun stopCallTimeoutProcess() {
//        callRetryHandler?.removeCallbacksAndMessages(null)
//        callRetryHandler = null
//        callRetryCount = 0
//    }
//
//    fun acceptCall(callerId: String, callId: String) {
//        if (!isInitialized) {
//            Log.e(TAG, "WebRTC client not initialized")
//            listener.onCallFailed("WebRTC not initialized")
//            return
//        }
//
//        // Проверяем подключение к сокету
//        if (socket?.connected() != true) {
//            Log.e(TAG, "❌ Cannot accept call - socket not connected")
//            listener.onCallFailed("No connection to server")
//            return
//        }
//
//        targetUserId = callerId
//        isIncomingCall = false
//        isCalling = true
//        currentCallId = callId
//
//        Log.d(TAG, "✅ Accepting call from: $callerId")
//
//        if (!createPeerConnection() || !createLocalMediaStream()) {
//            Log.e(TAG, "Failed to initialize media for accepting call")
//            listener.onCallFailed("Failed to initialize call")
//            return
//        }
//
//        val acceptData = JSONObject().apply {
//            put("fromUserId", callerId)
//            put("callId", callId)
//        }
//
//        emitSafe("accept_call", acceptData)
//    }
//
//    fun rejectCall(callerId: String) {
//        Log.d(TAG, "❌ Rejecting call from: $callerId")
//
//        val rejectData = JSONObject().apply {
//            put("fromUserId", callerId)
//            put("callId", currentCallId)
//        }
//
//        emitSafe("reject_call", rejectData)
//        cleanupCall()
//    }
//
//    fun endCall() {
//        Log.d(TAG, "📞 Ending call")
//
//        targetUserId?.let { target ->
//            val endCallData = JSONObject().apply {
//                put("toUserId", target)
//                put("callId", currentCallId)
//            }
//            emitSafe("end_call", endCallData)
//        }
//
//        cleanupCall()
//    }
//
//    // ДОБАВЛЕННЫЕ МЕТОДЫ:
//
//    private fun createPeerConnection(): Boolean {
//        return try {
//            if (peerConnectionFactory == null) {
//                Log.e(TAG, "PeerConnectionFactory is null")
//                return false
//            }
//
//            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
//                keyType = PeerConnection.KeyType.ECDSA
//                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
//                iceTransportsType = PeerConnection.IceTransportsType.ALL
//                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
//                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
//                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
//            }
//
//            peerConnection = peerConnectionFactory?.createPeerConnection(
//                rtcConfig,
//                object : PeerConnection.Observer {
//                    override fun onSignalingChange(state: PeerConnection.SignalingState) {
//                        Log.d(TAG, "Signaling state: $state")
//                    }
//
//                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
//                        Log.d(TAG, "ICE connection state: $state")
//                        listener.onIceConnectionStateChanged(state)
//
//                        when (state) {
//                            PeerConnection.IceConnectionState.DISCONNECTED,
//                            PeerConnection.IceConnectionState.FAILED,
//                            PeerConnection.IceConnectionState.CLOSED -> {
//                                Log.d(TAG, "ICE connection closed, cleaning up")
//                                cleanupCall()
//                            }
//                            else -> {}
//                        }
//                    }
//
//                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
//
//                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
//                        Log.d(TAG, "ICE gathering state: $state")
//                    }
//
//                    override fun onIceCandidate(candidate: IceCandidate) {
//                        Log.d(TAG, "❄️ New ICE candidate: ${candidate.sdpMid}")
//
//                        targetUserId?.let { target ->
//                            val candidateData = JSONObject().apply {
//                                put("sdpMid", candidate.sdpMid)
//                                put("sdpMLineIndex", candidate.sdpMLineIndex)
//                                put("sdp", candidate.sdp)
//                            }
//
//                            val iceData = JSONObject().apply {
//                                put("toUserId", target)
//                                put("candidate", candidateData)
//                            }
//                            emitSafe("webrtc_ice_candidate", iceData)
//                        }
//                    }
//
//                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
//                        Log.d(TAG, "ICE candidates removed")
//                    }
//
//                    override fun onAddStream(stream: MediaStream) {
//                        remoteAudioTrack = stream.audioTracks.firstOrNull()
//                        remoteVideoTrack = stream.videoTracks.firstOrNull()
//                        listener.onRemoteStreamAdded(stream)
//                    }
//
//                    override fun onRemoveStream(stream: MediaStream) {}
//
//                    override fun onDataChannel(dataChannel: DataChannel) {
//                        Log.d(TAG, "Data channel: ${dataChannel.label()}")
//                    }
//
//                    override fun onRenegotiationNeeded() {
//                        Log.d(TAG, "Renegotiation needed")
//                    }
//
//                    override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
//                        Log.d(TAG, "Add track: ${rtpReceiver.id()}")
//                    }
//
//                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
//                        Log.d(TAG, "Peer connection state: $newState")
//                        listener.onConnectionStateChanged(newState)
//                    }
//
//                    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
//                        Log.d(TAG, "Selected candidate pair changed")
//                    }
//
//                    override fun onTrack(transceiver: RtpTransceiver) {
//                        Log.d(TAG, "Track added: ${transceiver.mediaType}")
//                    }
//
//                    override fun onRemoveTrack(receiver: RtpReceiver) {
//                        Log.d(TAG, "Remove track: ${receiver.id()}")
//                    }
//                }
//            )
//
//            peerConnection != null
//        } catch (e: Exception) {
//            Log.e(TAG, "Error creating PeerConnection: ${e.message}")
//            false
//        }
//    }
//
//    private fun createLocalMediaStream(): Boolean {
//        return try {
//            val audioConstraints = MediaConstraints().apply {
//                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
//                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
//                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
//                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
//            }
//
//            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
//            if (audioSource == null) {
//                Log.e(TAG, "Failed to create audio source")
//                return false
//            }
//
//            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track_$userId", audioSource)
//            if (localAudioTrack == null) {
//                Log.e(TAG, "Failed to create audio track")
//                return false
//            }
//
//            // Добавляем треки в peer connection
//            localAudioTrack?.let { audioTrack ->
//                val streamId = "stream_$userId"
//                val result = peerConnection?.addTrack(audioTrack, listOf(streamId))
//                if (result == null) {
//                    Log.e(TAG, "Failed to add audio track to peer connection")
//                    return false
//                }
//            }
//
//            // Создаем локальный медиа поток для отображения
//            val localStream = peerConnectionFactory?.createLocalMediaStream("local_stream_$userId")
//            localAudioTrack?.let { localStream?.addTrack(it) }
//
//            localStream?.let {
//                listener.onLocalStreamAdded(it)
//                Log.d(TAG, "✅ Local media stream created successfully")
//            }
//
//            true
//        } catch (e: Exception) {
//            Log.e(TAG, "Error creating local media stream: ${e.message}")
//            false
//        }
//    }
//
//    private fun createOffer() {
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//        }
//
//        peerConnection?.createOffer(object : SdpObserver {
//            override fun onCreateSuccess(desc: SessionDescription) {
//                Log.d(TAG, "✅ Offer created successfully")
//                peerConnection?.setLocalDescription(object : SdpObserver {
//                    override fun onCreateSuccess(p0: SessionDescription?) {}
//                    override fun onCreateFailure(error: String) {}
//                    override fun onSetSuccess() {
//                        Log.d(TAG, "✅ Local description set")
//                        targetUserId?.let { target ->
//                            val offerData = JSONObject().apply {
//                                put("type", desc.type.toString().lowercase())
//                                put("sdp", desc.description)
//                            }
//                            val webrtcData = JSONObject().apply {
//                                put("toUserId", target)
//                                put("offer", offerData)
//                            }
//                            emitSafe("webrtc_offer", webrtcData)
//                        }
//                    }
//                    override fun onSetFailure(error: String) {
//                        Log.e(TAG, "❌ Failed to set local description: $error")
//                        cleanupCall()
//                        listener.onCallFailed("Failed to set local description: $error")
//                    }
//                }, desc)
//            }
//            override fun onSetSuccess() {}
//            override fun onCreateFailure(error: String) {
//                Log.e(TAG, "❌ Failed to create offer: $error")
//                cleanupCall()
//                listener.onCallFailed("Failed to create offer: $error")
//            }
//            override fun onSetFailure(error: String) {
//                Log.e(TAG, "❌ Failed to set offer: $error")
//                cleanupCall()
//                listener.onCallFailed("Failed to set offer: $error")
//            }
//        }, constraints)
//    }
//
//    private fun handleOffer(fromUserId: String, offerData: JSONObject) {
//        targetUserId = fromUserId
//        isCalling = true
//
//        try {
//            if (!createPeerConnection() || !createLocalMediaStream()) {
//                Log.e(TAG, "Failed to initialize media for handling offer")
//                cleanupCall()
//                listener.onCallFailed("Failed to initialize call")
//                return
//            }
//
//            val type = when (offerData.optString("type")) {
//                "offer" -> SessionDescription.Type.OFFER
//                else -> SessionDescription.Type.OFFER
//            }
//            val sdp = offerData.optString("sdp", "")
//
//            val offer = SessionDescription(type, sdp)
//
//            peerConnection?.setRemoteDescription(object : SdpObserver {
//                override fun onSetSuccess() {
//                    Log.d(TAG, "✅ Remote description set")
//                    createAnswer()
//                }
//                override fun onSetFailure(error: String) {
//                    Log.e(TAG, "❌ Failed to set remote description: $error")
//                    cleanupCall()
//                    listener.onCallFailed("Failed to set remote description: $error")
//                }
//                override fun onCreateSuccess(p0: SessionDescription?) {}
//                override fun onCreateFailure(p0: String?) {}
//            }, offer)
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error handling offer: ${e.message}")
//            cleanupCall()
//            listener.onCallFailed("Error handling offer: ${e.message}")
//        }
//    }
//
//    private fun createAnswer() {
//        val constraints = MediaConstraints().apply {
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//        }
//
//        peerConnection?.createAnswer(object : SdpObserver {
//            override fun onCreateSuccess(desc: SessionDescription) {
//                Log.d(TAG, "✅ Answer created successfully")
//                peerConnection?.setLocalDescription(object : SdpObserver {
//                    override fun onCreateSuccess(p0: SessionDescription?) {}
//                    override fun onCreateFailure(error: String) {}
//                    override fun onSetSuccess() {
//                        Log.d(TAG, "✅ Local description set for answer")
//                        targetUserId?.let { target ->
//                            val answerData = JSONObject().apply {
//                                put("type", desc.type.toString().lowercase())
//                                put("sdp", desc.description)
//                            }
//                            val webrtcData = JSONObject().apply {
//                                put("toUserId", target)
//                                put("answer", answerData)
//                            }
//                            emitSafe("webrtc_answer", webrtcData)
//                        }
//                    }
//                    override fun onSetFailure(error: String) {
//                        Log.e(TAG, "❌ Failed to set local description for answer: $error")
//                        cleanupCall()
//                        listener.onCallFailed("Failed to set local description: $error")
//                    }
//                }, desc)
//            }
//            override fun onSetSuccess() {}
//            override fun onCreateFailure(error: String) {
//                Log.e(TAG, "❌ Failed to create answer: $error")
//                cleanupCall()
//                listener.onCallFailed("Failed to create answer: $error")
//            }
//            override fun onSetFailure(error: String) {
//                Log.e(TAG, "❌ Failed to set answer: $error")
//                cleanupCall()
//                listener.onCallFailed("Failed to set answer: $error")
//            }
//        }, constraints)
//    }
//
//    private fun handleAnswer(answerData: JSONObject) {
//        val type = when (answerData.optString("type")) {
//            "answer" -> SessionDescription.Type.ANSWER
//            else -> SessionDescription.Type.ANSWER
//        }
//        val sdp = answerData.optString("sdp", "")
//
//        val answer = SessionDescription(type, sdp)
//
//        peerConnection?.setRemoteDescription(object : SdpObserver {
//            override fun onSetSuccess() {
//                Log.d(TAG, "✅ Remote answer set successfully")
//            }
//            override fun onSetFailure(error: String) {
//                Log.e(TAG, "❌ Failed to set remote answer: $error")
//                cleanupCall()
//                listener.onCallFailed("Failed to set remote answer: $error")
//            }
//            override fun onCreateSuccess(p0: SessionDescription?) {}
//            override fun onCreateFailure(p0: String?) {}
//        }, answer)
//    }
//
//    private fun handleIceCandidate(candidateData: JSONObject) {
//        val sdpMid = candidateData.optString("sdpMid", "")
//        val sdpMLineIndex = candidateData.optInt("sdpMLineIndex", 0)
//        val sdp = candidateData.optString("sdp", "")
//
//        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
//
//        peerConnection?.addIceCandidate(iceCandidate)
//    }
//
//    private fun cleanupCall() {
//        Log.d(TAG, "🧹 Cleaning up call resources")
//
//        stopCallTimeoutProcess()
//
//        isCalling = false
//        isIncomingCall = false
//        targetUserId = null
//        currentCallId = null
//        callRetryCount = 0
//
//        // Очистка в правильном порядке
//        try {
//            peerConnection?.close()
//            peerConnection = null
//
//            localAudioTrack?.dispose()
//            localAudioTrack = null
//
//            localVideoTrack?.dispose()
//            localVideoTrack = null
//
//            remoteAudioTrack?.dispose()
//            remoteAudioTrack = null
//
//            remoteVideoTrack?.dispose()
//            remoteVideoTrack = null
//
//            audioSource?.dispose()
//            audioSource = null
//
//            videoSource?.dispose()
//            videoSource = null
//        } catch (e: Exception) {
//            Log.e(TAG, "Error during cleanup: ${e.message}")
//        }
//    }
//
//    // НОВЫЙ МЕТОД: Принудительное переподключение
//    fun reconnect() {
//        Log.d(TAG, "🔄 Manual reconnection requested")
//        disconnect()
//        initializeSocket()
//    }
//
//    fun disconnect() {
//        Log.d(TAG, "🔴 Disconnecting WebRTC client")
//
//        stopCallTimeoutProcess()
//        stopConnectionRetryProcess()
//        endCall()
//        cleanupCall()
//
//        try {
//            socket?.disconnect()
//            socket?.off()
//            socket = null
//
//            peerConnectionFactory?.dispose()
//            peerConnectionFactory = null
//        } catch (e: Exception) {
//            Log.e(TAG, "Error during disconnect: ${e.message}")
//        }
//
//        isInitialized = false
//        isConnecting = false
//        Log.d(TAG, "✅ WebRTC client disconnected and cleaned up")
//    }
//
//    // НОВЫЕ МЕТОДЫ ДЛЯ ПРОВЕРКИ СОСТОЯНИЯ
//    fun isSocketConnected(): Boolean = socket?.connected() == true
//    fun isConnecting(): Boolean = isConnecting
//    fun getConnectionRetryCount(): Int = connectionRetryCount
//
//    fun isInCall(): Boolean = isCalling
//    fun getTargetUserId(): String? = targetUserId
//    fun isInitialized(): Boolean = isInitialized
//
//    companion object {
//        private const val TAG = "WebRTCClient"
//    }
//}