package com.ppnkdeapp.mycontacts.call

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.ppnkdeapp.mycontacts.MyApp
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.concurrent.Executors

class WebRTCClient(
    private val context: Context,
    private val serverUrl: String,
    private val userId: String,
    private val listener: WebRTCListener
) {
    private var myApp: MyApp? = null
    private var peerConnection: PeerConnection? = null
    private var socket: Socket? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var targetUserId: String? = null
    private var currentCallId: String? = null
    private var isCalling = false
    private var isIncomingCall = false
    private var isRegisteredOnServer = false
    
    // 🔥 НОВОЕ: Callback для отслеживания изменений currentActualCall
    private var actualCallChangeCallback: ((ActualCall?) -> Unit)? = null
    
    // 🔥 НОВОЕ: Флаг для предотвращения рекурсивного вызова cleanup()
    private var isCleaningUp = false
    
    // 🔥 НОВОЕ: Подготовленный offer для быстрой отправки
    private var preparedOffer: SessionDescription? = null

    // ICE серверы для NAT traversal
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
            .setUsername("83c6ad7d0639d8d9f8e7c7d9")
            .setPassword("3H7lN3jC8T0rK8bK")
            .createIceServer()
    )

    interface WebRTCListener {
        fun onCallInitiated(callId: String)
        fun onCallAccepted(callId: String)
        fun onCallRejected(callId: String)
        fun onCallEnded(callId: String)
        fun onCallFailed(callId: String, error: String)
        fun onIncomingCall(callId: String, fromUserId: String)
        fun onWebRTCConnected()
        fun onWebRTCDisconnected()
        fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState)
    }

    init {
        // 🔥 ИЗМЕНЕНО: Асинхронная инициализация WebRTC в фоновом потоке
        Executors.newSingleThreadExecutor().execute {
            try {
                Log.d(TAG, "🔄 Starting async WebRTC initialization...")
                initializeWebRTC()
                Log.d(TAG, "✅ Async WebRTC initialization completed")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Async WebRTC initialization failed: ${e.message}")
            }
        }
        
        // Получаем ссылку на MyApp только если context это MyApp
        if (context is MyApp) {
            myApp = context
        }
    }

    private fun initializeWebRTC() {
        try {
            // 🔥 НОВОЕ: Проверяем, не инициализирован ли уже WebRTC
            if (peerConnectionFactory != null) {
                Log.d(TAG, "✅ WebRTC already initialized, reusing PeerConnectionFactory")
                return
            }
            
            Log.d(TAG, "🔄 Initializing WebRTC...")
            
            // Инициализация PeerConnectionFactory
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
                Log.d(TAG, "✅ PeerConnectionFactory.initialize() completed")
            } catch (e: IllegalStateException) {
                // WebRTC уже инициализирован
                Log.d(TAG, "ℹ️ WebRTC already initialized: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ PeerConnectionFactory initialization error: ${e.message}")
                throw e
            }

            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .createAudioDeviceModule()
            
            Log.d(TAG, "✅ AudioDeviceModule created")

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule)
                .createPeerConnectionFactory()
            
            Log.d(TAG, "✅ PeerConnectionFactory created successfully")
            Log.d(TAG, "✅ WebRTC initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ WebRTC initialization failed: ${e.message}")
            e.printStackTrace()
        }
    }
    // Добавляем эти методы в класс WebRTCClient:

    /**
     * МЕТОДЫ ДЛЯ РАБОТЫ СО СПИСКОМ УСТРОЙСТВ
     */

    /**
     * Регистрация устройства на сервере
     */
    fun registerDevice(deviceId: String, callback: (List<String>?) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL("$serverUrl/api/devices/register")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("userId", userId)
                }

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)

                    if (jsonResponse.optBoolean("success", false)) {
                        val deviceList = mutableListOf<String>()
                        val devicesArray = jsonResponse.optJSONArray("deviceList")

                        devicesArray?.let { array ->
                            for (i in 0 until array.length()) {
                                deviceList.add(array.getString(i))
                            }
                        }

                        Log.d(TAG, "✅ Device registered successfully. Total devices: ${deviceList.size}")
                        callback(deviceList)
                    } else {
                        Log.e(TAG, "❌ Failed to register device: ${jsonResponse.optString("error")}")
                        callback(null)
                    }
                } else {
                    Log.e(TAG, "❌ HTTP error registering device: $responseCode")
                    callback(null)
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error registering device: ${e.message}")
                callback(null)
            }
        }
    }

    /**
     * Отмена регистрации устройства на сервере
     * @param deviceId ID устройства для отключения
     * @param callback Callback с результатом операции (true = успешно, false = ошибка)
     */
    fun unregisterDevice(deviceId: String, callback: ((Boolean) -> Unit)? = null) {
        Executors.newSingleThreadExecutor().execute {
            try {
                Log.d(TAG, "📱 Starting device unregistration: $deviceId")
                
                if (deviceId.isBlank()) {
                    Log.e(TAG, "❌ Device ID is empty, cannot unregister")
                    callback?.invoke(false)
                    return@execute
                }
                
                val url = URL("$serverUrl/api/devices/unregister")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000 // 5 секунд таймаут
                connection.readTimeout = 5000

                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                }
                
                Log.d(TAG, "📱 Sending unregister request for device: $deviceId")
                Log.d(TAG, "📱 Request JSON: ${json.toString()}")

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "📱 HTTP response code: $responseCode")
                
                if (responseCode == 200 || responseCode == 204) {
                    Log.d(TAG, "✅ Device unregistered successfully from server: $deviceId")
                    callback?.invoke(true)
                } else {
                    Log.e(TAG, "❌ HTTP error unregistering device: $responseCode")
                    
                    // Читаем ответ сервера для отладки
                    try {
                        val errorResponse = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "❌ Server error response: $errorResponse")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Could not read error response: ${e.message}")
                    }
                    
                    callback?.invoke(false)
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error unregistering device: ${e.message}")
                e.printStackTrace()
                callback?.invoke(false)
            }
        }
    }

    /**
     * Получение текущего списка устройств
     */
    fun getDeviceList(callback: (List<String>?) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL("$serverUrl/api/devices")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)

                    if (jsonResponse.optBoolean("success", false)) {
                        val deviceList = mutableListOf<String>()
                        val devicesArray = jsonResponse.optJSONArray("deviceList")

                        devicesArray?.let { array ->
                            for (i in 0 until array.length()) {
                                deviceList.add(array.getString(i))
                            }
                        }

                        Log.d(TAG, "✅ Retrieved device list. Total devices: ${deviceList.size}")
                        callback(deviceList)
                    } else {
                        Log.e(TAG, "❌ Failed to get device list: ${jsonResponse.optString("error")}")
                        callback(null)
                    }
                } else {
                    Log.e(TAG, "❌ HTTP error getting device list: $responseCode")
                    callback(null)
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting device list: ${e.message}")
                callback(null)
            }
        }
    }


    /**
     * Получение уникального ID устройства
     */
    fun getDeviceId(): String {
        // Используем Android ID или генерируем уникальный ID
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "device_${UUID.randomUUID()}"
    }
    /**
     * ИНИЦИАЦИЯ ЗВОНКА - Шаг 1
     * Создает ActualCall объект и отправляет на сервер через HTTP
     */
    fun initiateCall(targetUserId: String): String {
        val callId = generateCallId()
        this.targetUserId = targetUserId
        this.currentCallId = callId
        this.isCalling = true

        Log.d(TAG, "📞 Initiating call to: $targetUserId, callId: $callId")

        // Создаем ActualCall объект
        val actualCall = ActualCall(
            callId = callId,
            callerId = userId,
            recipientId = targetUserId,
            status = "null",
            step = "request_call",
            createdAt = System.currentTimeMillis(),
            offerSdp = null,
            answerSdp = null
        )

        // Обновляем ActualCall в MyApp
        myApp?.setCurrentActualCall(actualCall)

        // Отправляем через HTTP на сервер
        sendCallViaHttp(actualCall)

        // 🔥 НОВОЕ: Параллельно подготавливаем WebRTC соединение
        prepareWebRTCConnectionAsync()

        listener.onCallInitiated(callId)
        return callId
    }

    /**
     * 🔥 НОВОЕ: Асинхронная подготовка WebRTC соединения
     * Создает PeerConnection, аудио трек и подготавливает offer
     */
    private fun prepareWebRTCConnectionAsync() {
        Executors.newSingleThreadExecutor().execute {
            try {
                Log.d(TAG, "🚀 Preparing WebRTC connection asynchronously...")
                
                // Создаем PeerConnection
                if (!createPeerConnection()) {
                    Log.e(TAG, "❌ Failed to create PeerConnection during preparation")
                    return@execute
                }
                
                // Создаем локальный аудио трек
                createLocalAudioTrack()
                
                // Подключаемся к WebSocket
                connectWebSocket()
                
                // Создаем и сохраняем offer
                createAndPrepareOffer()
                
                Log.d(TAG, "✅ WebRTC connection prepared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error preparing WebRTC connection: ${e.message}")
            }
        }
    }

    /**
     * ОБРАБОТКА ВХОДЯЩЕГО ЗВОНКА - Шаг 2
     * Вызывается когда получаем push с объектом ActualCall со статусом "offer"
     */
    fun handleIncomingCall(actualCall: ActualCall) {
        Log.d(TAG, "📥 Handling incoming call: ${actualCall.callId} from: ${actualCall.callerId}")

        this.targetUserId = actualCall.callerId
        this.currentCallId = actualCall.callId
        this.isIncomingCall = true

        // Обновляем ActualCall в MyApp
        myApp?.setCurrentActualCall(actualCall)

        listener.onIncomingCall(actualCall.callId, actualCall.callerId)
    }

    /**
     * ПРИНЯТИЕ ЗВОНКА - Шаг 3
     * Обновляет ActualCall на сервере со статусом "accepted"
     */
    fun acceptIncomingCall(callId: String) {
        if (currentCallId != callId) {
            Log.e(TAG, "❌ Call ID mismatch")
            return
        }

        Log.d(TAG, "✅ Accepting incoming call: $callId")

        val updatedCall = ActualCall(
            callId = callId,
            callerId = targetUserId!!,
            recipientId = userId,
            status = "null",
            step = "accept_call",
            createdAt = System.currentTimeMillis(),
            offerSdp = null,
            answerSdp = null
        )

        // Обновляем ActualCall в MyApp
        myApp?.setCurrentActualCall(updatedCall)

        // Обновляем статус на сервере через HTTP
        updateCallViaHttp(updatedCall) { success ->
            if (success) {
                Log.d(TAG, "✅ Call accepted on server")
                listener.onCallAccepted(callId)

                // Подготавливаем WebRTC для входящего звонка
                prepareForIncomingCall()
            } else {
                Log.e(TAG, "❌ Failed to accept call on server")
                listener.onCallFailed(callId, "Failed to accept call")
            }
        }
    }

    /**
     * ОТКЛОНЕНИЕ ЗВОНКА - Шаг 3 (альтернатива)
     * Обновляет ActualCall на сервере со статусом "rejected"
     */
    fun rejectIncomingCall(callId: String) {
        Log.d(TAG, "❌ Rejecting call: $callId")

        val updatedCall = ActualCall(
            callId = callId,
            callerId = targetUserId!!,
            recipientId = userId,
            status = "null",
            step = "reject_call",
            createdAt = System.currentTimeMillis(),
            offerSdp = null,
            answerSdp = null
        )

        // Обновляем ActualCall в MyApp
        myApp?.setCurrentActualCall(updatedCall)

        updateCallViaHttp(updatedCall) { success ->
            if (success) {
                Log.d(TAG, "✅ Call rejected on server")
                listener.onCallRejected(callId)
                if (!isCleaningUp) {
                    cleanup()
                }
            } else {
                Log.e(TAG, "❌ Failed to reject call on server")
            }
        }
    }

    /**
     * ОБРАБОТКА СТАТУСА ЗВОНКА - Шаг 4
     * Вызывается когда получаем push с обновленным ActualCall
     */
    fun handleCallStatusUpdate(actualCall: ActualCall) {
        Log.d(TAG, "🔄 Handling call status update: ${actualCall.status} for call: ${actualCall.callId}")

        // Обновляем ActualCall в MyApp
        myApp?.setCurrentActualCall(actualCall)

        when (actualCall.step) {
            "accept_call" -> {
                Log.d(TAG, "🎯 Call accepted by recipient, starting WebRTC connection")
                listener.onCallAccepted(actualCall.callId)

                // 🔥 ИЗМЕНЕНО: Используем подготовленный offer или создаем новый
                if (preparedOffer != null) {
                    Log.d(TAG, "🚀 Using prepared offer for fast connection")
                    sendOffer(preparedOffer!!)
                } else {
                    Log.d(TAG, "⚠️ No prepared offer, creating new connection")
                    initializeWebRTCConnection()
                }
            }
            "reject_call" -> {
                Log.d(TAG, "🚫 Call rejected by recipient")
                listener.onCallRejected(actualCall.callId)
                if (!isCleaningUp) {
                    cleanup()
                }
            }
            "end_call6" -> {
                Log.d(TAG, "📞 Call ended")
                listener.onCallEnded(actualCall.callId)
                if (!isCleaningUp) {
                    cleanup()
                }
            }
            else -> {
                Log.w(TAG, "⚠️ Unknown call status: ${actualCall.status}")
            }
        }
    }

    /**
     * ПОДГОТОВКА К ВХОДЯЩЕМУ ЗВОНКУ WebRTC
     */
    private fun prepareForIncomingCall() {
        Log.d(TAG, "🔧 Preparing for incoming WebRTC call")

        // Создаем PeerConnection
        createPeerConnection()

        // Создаем локальный аудио трек
        createLocalAudioTrack()

        // Подключаемся к WebSocket для обмена SDP и ICE кандидатами
        connectWebSocket()
    }

    /**
     * 🔥 НОВОЕ: Создание и подготовка offer без отправки
     */
    private fun createAndPrepareOffer() {
        Log.d(TAG, "📤 Creating and preparing WebRTC offer (not sending yet)")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        Log.d(TAG, "📤 Calling createOffer...")
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d(TAG, "✅ Offer created and prepared successfully: ${desc?.type}")
                desc?.let {
                    Log.d(TAG, "📤 Setting local description...")
                    peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            Log.d(TAG, "✅ Local description set successfully")
                            // Сохраняем offer для быстрой отправки
                            preparedOffer = it
                            Log.d(TAG, "💾 Offer prepared and ready for sending")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "❌ Failed to set local description: $error")
                        }
                    }, it)
                }
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "❌ Failed to create offer: $error")
            }
        }, constraints)
    }

    /**
     * ИНИЦИАЦИЯ WebRTC СОЕДИНЕНИЯ - Шаг 5
     */
    private fun initializeWebRTCConnection() {
        Log.d(TAG, "🌐 Initializing WebRTC connection")

        // Создаем PeerConnection
        if (!createPeerConnection()) {
            listener.onCallFailed(currentCallId!!, "Failed to create PeerConnection")
            return
        }

        // Создаем локальный аудио трек
        createLocalAudioTrack()

        // Подключаемся к WebSocket
        connectWebSocket()

        // Создаем и отправляем offer
        createAndSendOffer()
    }

    /**
     * Запуск WebRTC соединения после получения accept_call (для отправителя)
     */
    fun startWebRTCConnection(actualCall: ActualCall) {
        Log.d(TAG, "🌐 Starting WebRTC connection for call: ${actualCall.callId}")
        Log.d(TAG, "🌐 Caller: ${actualCall.callerId}, Recipient: ${actualCall.recipientId}")
        Log.d(TAG, "🌐 My userId: $userId")
        
        this.currentCallId = actualCall.callId
        this.targetUserId = actualCall.recipientId
        this.isCalling = true
        
        Log.d(TAG, "🌐 Creating PeerConnection...")
        // Создаем PeerConnection
        if (!createPeerConnection()) {
            Log.e(TAG, "❌ Failed to create PeerConnection")
            listener.onCallFailed(actualCall.callId, "Failed to create PeerConnection")
            return
        }
        
        Log.d(TAG, "🌐 Creating local audio track...")
        // Создаем локальный аудио трек
        createLocalAudioTrack()
        
        Log.d(TAG, "🌐 Connecting to WebSocket...")
        // Подключаемся к WebSocket для сигналинга
        connectWebSocket()
        
        Log.d(TAG, "🌐 Creating and sending offer...")
        // Создаем и отправляем offer
        createAndSendOffer()
    }

    /**
     * Обработка входящего WebRTC соединения (для получателя)
     */
    fun handleIncomingWebRTCConnection(actualCall: ActualCall) {
        Log.d(TAG, "📥 Handling incoming WebRTC connection: ${actualCall.callId}")
        Log.d(TAG, "📥 Caller: ${actualCall.callerId}, Recipient: ${actualCall.recipientId}")
        Log.d(TAG, "📥 My userId: $userId")
        
        this.currentCallId = actualCall.callId
        this.targetUserId = actualCall.callerId
        this.isIncomingCall = true
        
        Log.d(TAG, "📥 Creating PeerConnection...")
        // Создаем PeerConnection
        if (!createPeerConnection()) {
            Log.e(TAG, "❌ Failed to create PeerConnection")
            listener.onCallFailed(actualCall.callId, "Failed to create PeerConnection")
            return
        }
        
        Log.d(TAG, "📥 Creating local audio track...")
        // Создаем локальный аудио трек
        createLocalAudioTrack()
        
        Log.d(TAG, "📥 Connecting to WebSocket...")
        // Подключаемся к WebSocket для сигналинга
        connectWebSocket()
        
        // Ждем offer от отправителя
        Log.d(TAG, "⏳ Waiting for WebRTC offer from caller")
    }

    private fun createPeerConnection(): Boolean {
        return try {
            Log.d(TAG, "🔧 Creating PeerConnection with ${iceServers.size} ICE servers")
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        Log.d(TAG, "🧊 New ICE candidate: ${candidate.sdp}")
                        sendIceCandidate(candidate)
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        Log.d(TAG, "🧊 ICE connection state: $state")
                        listener.onIceConnectionStateChanged(state)

                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                Log.d(TAG, "✅ WebRTC connection established - checking microphones")
                                // Проверяем микрофоны после установления ICE соединения
                                checkMicrophonesReady()
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.CLOSED -> {
                                Log.w(TAG, "❌ WebRTC connection lost")
                                listener.onWebRTCDisconnected()
                                // 🔥 КРИТИЧЕСКИ ВАЖНО: Проверяем флаг перед вызовом cleanup
                                if (!isCleaningUp) {
                                    cleanup()
                                } else {
                                    Log.d(TAG, "⚠️ Cleanup already in progress, skipping duplicate call")
                                }
                            }
                            else -> {}
                        }
                    }

                    override fun onAddStream(stream: MediaStream) {
                        Log.d(TAG, "📹 Remote stream added")
                        if (stream.audioTracks.isNotEmpty()) {
                            remoteAudioTrack = stream.audioTracks[0]
                            remoteAudioTrack?.setEnabled(true)
                            Log.d(TAG, "🎧 Remote audio track enabled")
                            
                            // 🔥 НОВОЕ: Проверяем готовность обоих микрофонов
                            checkMicrophonesReady()
                        }
                    }

                    // Остальные методы Observer (обязательные)
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                    override fun onRemoveStream(stream: MediaStream) {}
                    override fun onDataChannel(dataChannel: DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
                    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
                    override fun onTrack(transceiver: RtpTransceiver) {}
                    override fun onRemoveTrack(receiver: RtpReceiver) {}
                }
            )

            // Добавляем локальный аудио трек
            localAudioTrack?.let { track ->
                peerConnection?.addTrack(track, listOf("audio_stream"))
            }

            peerConnection != null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating PeerConnection: ${e.message}")
            false
        }
    }

    private fun createLocalAudioTrack() {
        try {
            // 🔥 НОВОЕ: Проверяем, готов ли PeerConnectionFactory
            if (peerConnectionFactory == null) {
                Log.e(TAG, "❌ PeerConnectionFactory not ready yet, waiting...")
                // Ждем инициализацию
                var attempts = 0
                while (peerConnectionFactory == null && attempts < 10) {
                    Thread.sleep(100)
                    attempts++
                }
                if (peerConnectionFactory == null) {
                    Log.e(TAG, "❌ PeerConnectionFactory initialization timeout")
                    return
                }
                Log.d(TAG, "✅ PeerConnectionFactory ready after $attempts attempts")
            }
            
            Log.d(TAG, "🎤 Checking audio permissions...")
            if (!hasAudioPermissions()) {
                Log.e(TAG, "❌ No audio permissions - RECORD_AUDIO permission required")
                return
            }
            Log.d(TAG, "✅ Audio permissions granted")

            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }

            Log.d(TAG, "🎤 Creating audio source...")
            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            if (audioSource == null) {
                Log.e(TAG, "❌ Failed to create audio source")
                return
            }

            Log.d(TAG, "🎤 Creating audio track...")
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track_$userId", audioSource)
            if (localAudioTrack == null) {
                Log.e(TAG, "❌ Failed to create audio track")
                return
            }

            localAudioTrack?.setEnabled(true)
            Log.d(TAG, "✅ Audio track enabled: ${localAudioTrack?.enabled()}")

            // Настраиваем аудио для звонка
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            Log.d(TAG, "🔊 Audio mode set to MODE_IN_COMMUNICATION")

            Log.d(TAG, "🎤 Local audio track created and enabled successfully")
            
            // 🔥 НОВОЕ: Проверяем готовность микрофонов после создания локального трека
            checkMicrophonesReady()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating local audio track: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 🔥 НОВЫЙ МЕТОД: Проверка готовности микрофонов
     */
    private fun checkMicrophonesReady() {
        val localReady = localAudioTrack?.enabled() == true
        val remoteReady = remoteAudioTrack?.enabled() == true
        
        Log.d(TAG, "🎤 Checking microphones readiness:")
        Log.d(TAG, "   - Local microphone: $localReady (track: ${localAudioTrack != null})")
        Log.d(TAG, "   - Remote microphone: $remoteReady (track: ${remoteAudioTrack != null})")
        Log.d(TAG, "   - PeerConnection state: ${peerConnection?.signalingState()}")
        Log.d(TAG, "   - ICE connection state: ${peerConnection?.iceConnectionState()}")
        
        // 🔥 ИЗМЕНЕНО: Запускаем таймер когда локальный микрофон готов И есть удаленный поток
        // Не ждем удаленный микрофон, так как он может прийти позже
        if (localReady && remoteAudioTrack != null) {
            Log.d(TAG, "🎤 Local microphone ready and remote stream received - starting call timer")
            listener.onWebRTCConnected()
        } else if (localReady) {
            Log.d(TAG, "🎤 Local microphone ready, waiting for remote stream...")
        } else {
            Log.d(TAG, "⏳ Waiting for local microphone to be ready...")
        }
    }

    private fun createAndSendOffer() {
        Log.d(TAG, "📤 Creating and sending WebRTC offer")
        Log.d(TAG, "📤 PeerConnection state: ${peerConnection?.signalingState()}")
        Log.d(TAG, "📤 Local audio track enabled: ${localAudioTrack?.enabled()}")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        Log.d(TAG, "📤 Calling createOffer...")
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d(TAG, "✅ Offer created successfully: ${desc?.type}")
                Log.d(TAG, "📤 Offer SDP length: ${desc?.description?.length ?: 0}")
                desc?.let {
                    Log.d(TAG, "📤 Setting local description...")
                    peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            Log.d(TAG, "✅ Local description set successfully")
                            sendOffer(it)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "❌ Failed to set local description: $error")
                        }
                    }, it)
                }
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "❌ Failed to create offer: $error")
            }
        }, constraints)
    }

    /**
     * ОБРАБОТКА ПОЛУЧЕННОГО OFFER - Шаг 6
     */
    fun handleOffer(offerSdp: String) {
        Log.d(TAG, "📥 Handling received offer")

        if (peerConnection == null) {
            Log.e(TAG, "❌ PeerConnection not initialized")
            return
        }

        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Remote offer set successfully")
                createAndSendAnswer()
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "❌ Failed to set remote offer: $error")
            }
        }, offer)
    }

    private fun createAndSendAnswer() {
        Log.d(TAG, "📤 Creating and sending WebRTC answer")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                Log.d(TAG, "✅ Answer created successfully")
                desc?.let {
                    peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            Log.d(TAG, "✅ Local description set for answer")
                            sendAnswer(it)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "❌ Failed to set local description for answer: $error")
                        }
                    }, it)
                }
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "❌ Failed to create answer: $error")
            }
        }, constraints)
    }

    /**
     * ОБРАБОТКА ПОЛУЧЕННОГО ANSWER - Шаг 7
     */
    fun handleAnswer(answerSdp: String) {
        Log.d(TAG, "📥 Handling received answer")

        val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)

        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Remote answer set successfully")
                // Соединение установлено, начинается передача аудио
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "❌ Failed to set remote answer: $error")
            }
        }, answer)
    }

    /**
     * ОБРАБОТКА ICE КАНДИДАТОВ
     */
    fun handleIceCandidate(candidate: IceCandidate) {
        Log.d(TAG, "🧊 Handling remote ICE candidate")
        peerConnection?.addIceCandidate(candidate)
    }

    /**
     * WebSocket СОЕДИНЕНИЕ ДЛЯ WebRTC СИГНАЛИНГА
     */
    private fun connectWebSocket() {
        try {
            Log.d(TAG, "🔌 Connecting to WebSocket: $serverUrl")
            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true      // ❌ ОТКЛЮЧЕНО: Автоматическое переподключение
                reconnectionAttempts = 5 // ❌ ОТКЛЮЧЕНО: Попытки переподключения
                timeout = 5000
            }

            socket = IO.socket(serverUrl, opts)
            Log.d(TAG, "🔌 WebSocket socket created")

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ WebSocket connected for call: $currentCallId")
                registerWithWebSocket()
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "📡 WebSocket disconnected")
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "❌ WebSocket connection error: ${args.contentToString()}")
            }

//            socket?.on(Socket.EVENT_ERROR) { args ->
//                Log.e(TAG, "❌ WebSocket error: ${args.contentToString()}")
//            }

            socket?.on("webrtc_offer") { args ->
                val data = args[0] as? JSONObject
                data?.let {
                    val offerSdp = it.optString("sdp")
                    val callId = it.optString("callId")
                    val fromUserId = it.optString("fromUserId")
                    Log.d(TAG, "📥 Received WebRTC offer from: $fromUserId for call: $callId")
                    if (callId == currentCallId && offerSdp.isNotEmpty()) {
                        handleOffer(offerSdp)
                    }
                }
            }

            socket?.on("webrtc_answer") { args ->
                val data = args[0] as? JSONObject
                data?.let {
                    val answerSdp = it.optString("sdp")
                    val callId = it.optString("callId")
                    val fromUserId = it.optString("fromUserId")
                    Log.d(TAG, "📥 Received WebRTC answer from: $fromUserId for call: $callId")
                    if (callId == currentCallId && answerSdp.isNotEmpty()) {
                        handleAnswer(answerSdp)
                    }
                }
            }

            socket?.on("webrtc_ice_candidate") { args ->
                val data = args[0] as? JSONObject
                data?.let {
                    val candidateData = it.optJSONObject("candidate")
                    val callId = it.optString("callId")
                    val fromUserId = it.optString("fromUserId")
                    Log.d(TAG, "🧊 Received ICE candidate from: $fromUserId for call: $callId")
                    if (callId == currentCallId && candidateData != null) {
                        val iceCandidate = IceCandidate(
                            candidateData.optString("sdpMid"),
                            candidateData.optInt("sdpMLineIndex"),
                            candidateData.optString("candidate")
                        )
                        handleIceCandidate(iceCandidate)
                    }
                }
            }

            socket?.connect()
            Log.d(TAG, "🔌 Connecting WebSocket...")

        } catch (e: Exception) {
            Log.e(TAG, "❌ WebSocket connection error: ${e.message}")
        }
    }

    private fun registerWithWebSocket() {
        val data = JSONObject().apply {
            put("userId", userId)
            put("callId", currentCallId)
        }
        Log.d(TAG, "👤 Registering with WebSocket: userId=$userId, callId=$currentCallId")
        socket?.emit("register", data)
        Log.d(TAG, "✅ Registration sent to WebSocket")
    }

    private fun sendOffer(offer: SessionDescription) {
        val data = JSONObject().apply {
            put("sdp", offer.description)
            put("callId", currentCallId)
            put("toUserId", targetUserId)
        }
        socket?.emit("webrtc_offer", data)
        Log.d(TAG, "📤 Sent WebRTC offer")
    }

    private fun sendAnswer(answer: SessionDescription) {
        val data = JSONObject().apply {
            put("sdp", answer.description)
            put("callId", currentCallId)
            put("toUserId", targetUserId)
        }
        socket?.emit("webrtc_answer", data)
        Log.d(TAG, "📤 Sent WebRTC answer")
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        val candidateData = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }

        val data = JSONObject().apply {
            put("candidate", candidateData)
            put("callId", currentCallId)
            put("toUserId", targetUserId)
        }
        socket?.emit("webrtc_ice_candidate", data)
        Log.d(TAG, "📤 Sent ICE candidate")
    }

    /**
     * HTTP МЕТОДЫ ДЛЯ РАБОТЫ С ACTUALCALL
     */
    private fun sendCallViaHttp(actualCall: ActualCall) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL("$serverUrl/api/calls")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val json = JSONObject().apply {
                    put("callId", actualCall.callId)
                    put("callerId", actualCall.callerId)
                    put("recipientId", actualCall.recipientId)
                    put("status", actualCall.status)
                    put("createdAt", actualCall.createdAt)
                }

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200 || responseCode == 201) {
                    Log.d(TAG, "✅ Call sent to server successfully")
                } else {
                    Log.e(TAG, "❌ Failed to send call, response: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ HTTP error sending call: ${e.message}")
            }
        }
    }

    private fun updateCallViaHttp(actualCall: ActualCall, callback: (Boolean) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL("$serverUrl/api/calls/${actualCall.callId}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val json = JSONObject().apply {
                    put("callId", actualCall.callId)
                    put("callerId", actualCall.callerId)
                    put("recipientId", actualCall.recipientId)
                    put("status", actualCall.status)
                    put("createdAt", actualCall.createdAt)
                }

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    Log.d(TAG, "✅ Call updated on server successfully")
                    callback(true)
                } else {
                    Log.e(TAG, "❌ Failed to update call, response: $responseCode")
                    callback(false)
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ HTTP error updating call: ${e.message}")
                callback(false)
            }
        }
    }

    /**
     * ЗАВЕРШЕНИЕ ЗВОНКА
     */
    fun endCall() {
        Log.d(TAG, "📞 Ending call: $currentCallId")

        currentCallId?.let { callId ->
            val endedCall = ActualCall(
                callId = callId,
                callerId = userId,
                recipientId = targetUserId!!,
                status = "null",
                step = "end_call",
                createdAt = System.currentTimeMillis(),
                offerSdp = null,
                answerSdp = null
            )

            // Обновляем ActualCall в MyApp
            myApp?.setCurrentActualCall(endedCall)

            updateCallViaHttp(endedCall) { success ->
                if (success) {
                    listener.onCallEnded(callId)
                }
            }
        }

        cleanup()
    }

    private fun cleanup() {
        // 🔥 КРИТИЧЕСКИ ВАЖНО: Предотвращаем рекурсивный вызов
        if (isCleaningUp) {
            Log.d(TAG, "⚠️ Cleanup already in progress, skipping recursive call")
            return
        }
        
        isCleaningUp = true
        Log.d(TAG, "🧹 Cleaning up WebRTC resources")

        try {
            // 🔥 НОВОЕ: Отписываемся от изменений currentActualCall
            actualCallChangeCallback?.let { callback ->
                myApp?.unsubscribeFromActualCallChanges(callback)
                actualCallChangeCallback = null
            }
            
            // 🔥 НОВОЕ: Очищаем подготовленный offer
            preparedOffer = null
            
            // 🔥 КРИТИЧЕСКИ ВАЖНО: Сначала отключаемся от событий, потом закрываем соединение
            socket?.off()
            socket?.disconnect()
            
            // Закрываем PeerConnection только если он еще не закрыт
            peerConnection?.let { pc ->
                try {
                    if (pc.connectionState() != PeerConnection.PeerConnectionState.CLOSED) {
                        pc.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error closing peer connection: ${e.message}")
                }
                pc.dispose()
            }
            
            localAudioTrack?.dispose()
            audioSource?.dispose()

            peerConnection = null
            localAudioTrack = null
            audioSource = null
            socket = null

            // Восстанавливаем нормальный режим аудио
            audioManager.mode = AudioManager.MODE_NORMAL

            currentCallId = null
            targetUserId = null
            isCalling = false
            isIncomingCall = false

            // Очищаем ActualCall в MyApp
            myApp?.clearCurrentActualCall()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup: ${e.message}")
        } finally {
            // 🔥 КРИТИЧЕСКИ ВАЖНО: Сбрасываем флаг в конце
            isCleaningUp = false
            Log.d(TAG, "✅ WebRTC cleanup completed")
        }
    }

    // Вспомогательные методы
    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
    }

    private fun hasAudioPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleMute(): Boolean {
        return localAudioTrack?.let { track ->
            val newState = !track.enabled()
            track.setEnabled(newState)
            Log.d(TAG, if (newState) "🎤 Microphone enabled" else "🔇 Microphone muted")
            newState
        } ?: false
    }

    fun toggleSpeaker(): Boolean {
        val newState = !audioManager.isSpeakerphoneOn
        audioManager.isSpeakerphoneOn = newState
        Log.d(TAG, "🔊 Speakerphone: $newState")
        return newState
    }

    fun getCallState(): String = when {
        !isCalling && !isIncomingCall -> "IDLE"
        isIncomingCall -> "INCOMING"
        peerConnection == null -> "CONNECTING"
        else -> "CONNECTED"
    }

    /**
     * Установка ссылки на MyApp (вызывается из MyApp)
     */
    fun setMyApp(myApp: MyApp) {
        this.myApp = myApp
        
        // 🔥 НОВОЕ: Настраиваем отслеживание изменений currentActualCall
        setupActualCallObserver()
    }
    
    /**
     * 🔥 НОВЫЙ МЕТОД: Настройка отслеживания изменений currentActualCall
     */
    private fun setupActualCallObserver() {
        myApp?.let { app ->
            actualCallChangeCallback = { actualCall ->
                Log.d(TAG, "📞 ActualCall changed in WebRTCClient: ${actualCall?.callId}")
                
                // Если currentActualCall изменился (перезаписан), прерываем аудиоканал
                if (actualCall == null || actualCall.callId != currentCallId) {
                    Log.d(TAG, "🛑 ActualCall changed or cleared - interrupting audio channel")
                    
                    // Прерываем текущий аудиоканал
                    interruptAudioChannel()
                } else {
                    Log.d(TAG, "📞 ActualCall updated but same callId - continuing")
                }
            }
            
            // Подписываемся на изменения
            app.subscribeToActualCallChanges(actualCallChangeCallback!!)
            Log.d(TAG, "✅ ActualCall observer setup completed")
        }
    }
    
    /**
     * 🔥 НОВЫЙ МЕТОД: Прерывание аудиоканала при изменении currentActualCall
     */
    private fun interruptAudioChannel() {
        Log.d(TAG, "🛑 Interrupting audio channel due to ActualCall change")
        
        // Останавливаем аудио треки
        localAudioTrack?.setEnabled(false)
        remoteAudioTrack?.setEnabled(false)
        
        // Восстанавливаем нормальный режим аудио
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        
        // Очищаем WebRTC ресурсы
        cleanup()
        
        Log.d(TAG, "✅ Audio channel interrupted successfully")
    }

    companion object {
        private const val TAG = "WebRTCClient"
    }
}

/**
 * МОДЕЛЬ ДАННЫХ ACTUALCALL
 */
data class ActualCall(
    val callId: String,
    val callerId: String,
    val recipientId: String,
    val step: String?,
    val status: String,
    val createdAt: Long,
    val offerSdp: String?,
    val answerSdp: String?
)

/**
 * УПРОЩЕННЫЙ SDP OBSERVER
 */
open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {
        Log.d("SimpleSdpObserver", "onCreateSuccess: ${desc?.type}")
    }
    override fun onSetSuccess() {
        Log.d("SimpleSdpObserver", "onSetSuccess")
    }
    override fun onCreateFailure(error: String?) {
        Log.e("SimpleSdpObserver", "onCreateFailure: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.e("SimpleSdpObserver", "onSetFailure: $error")
    }
}