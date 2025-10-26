package com.ppnkdeapp.mycontacts.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NetworkManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: NetworkManager? = null

        fun getInstance(context: Context): NetworkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // Два типа соединений: WebSocket (OkHttp) и Socket.IO
    private var webSocket: okhttp3.WebSocket? = null
    private var socketIO: Socket? = null
    private var isConnected = false
    private var connectionType: ConnectionType = ConnectionType.WEBSOCKET

    enum class ConnectionType {
        WEBSOCKET,
        SOCKET_IO
    }

    interface NetworkListener {
        fun onConnected()
        fun onDisconnected()
        fun onCallReceived(call: Call)
        fun onCallAccepted(callId: String)
        fun onCallRejected(callId: String)
        fun onCallEnded(callId: String)
    }

    private val listeners = mutableListOf<NetworkListener>()

    // OkHttp клиент для WebSocket
    private val client = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun addListener(listener: NetworkListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: NetworkListener) {
        listeners.remove(listener)
    }

    // Основной метод подключения (WebSocket)
    fun connect(userId: String, authToken: String) {
        connectWebSocket(userId, authToken)
    }

    // Подключение через WebSocket
    private fun connectWebSocket(userId: String, authToken: String) {
        connectionType = ConnectionType.WEBSOCKET

        val request = Request.Builder()
            .url("wss://your-server.com/ws?user_id=$userId&token=$authToken")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                isConnected = true
                Log.d("NetworkManager", "WebSocket connected")
                Handler(Looper.getMainLooper()).post {
                    listeners.forEach { it.onConnected() }
                }
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, bytes: ByteString) {
                Log.d("NetworkManager", "Binary message received: ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("NetworkManager", "WebSocket closing: $reason")
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.d("NetworkManager", "WebSocket closed: $reason")
                Handler(Looper.getMainLooper()).post {
                    listeners.forEach { it.onDisconnected() }
                }
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e("NetworkManager", "WebSocket failure", t)
                Handler(Looper.getMainLooper()).post {
                    listeners.forEach { it.onDisconnected() }
                }

                // Автопереподключение через 5 секунд
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isConnected) {
                        connectWebSocket(userId, authToken)
                    }
                }, 5000)
            }
        })
    }

    // Подключение через Socket.IO (для WebRTC)
    fun connectSocketIO(serverUrl: String, userId: String, authToken: String) {
        connectionType = ConnectionType.SOCKET_IO

        try {
            Log.d("NetworkManager", "Connecting Socket.IO to: $serverUrl")

            val options = IO.Options().apply {
                forceNew = true
                reconnection = true
                reconnectionAttempts = Integer.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 20000
            }

            // В более новых версиях Socket.IO query может быть устаревшим
            // Вместо этого используем параметры в URL или extraHeaders
            socketIO = IO.socket("$serverUrl?user_id=$userId&token=$authToken", options)

            // Настройка обработчиков событий
            setupSocketIOListeners()

            socketIO?.connect()

        } catch (e: Exception) {
            Log.e("NetworkManager", "Error creating Socket.IO connection", e)
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it.onDisconnected() }
            }
        }
    }

    private fun setupSocketIOListeners() {
        socketIO?.on(Socket.EVENT_CONNECT) {
            isConnected = true
            Log.d("NetworkManager", "Socket.IO connected")
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it.onConnected() }
            }
        }

        socketIO?.on(Socket.EVENT_DISCONNECT) {
            isConnected = false
            Log.d("NetworkManager", "Socket.IO disconnected")
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it.onDisconnected() }
            }
        }

        socketIO?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args?.getOrNull(0)?.toString() ?: "Unknown error"
            Log.e("NetworkManager", "Socket.IO connection error: $error")
        }

        // Обработчики для звонков - используем лямбды вместо ссылок на методы
        socketIO?.on("incoming_call") { data ->
            handleIncomingCall(data)
        }

        socketIO?.on("call_accepted") { data ->
            handleCallAccepted(data)
        }

        socketIO?.on("call_rejected") { data ->
            handleCallRejected(data)
        }

        socketIO?.on("call_ended") { data ->
            handleCallEnded(data)
        }
    }

    private fun handleIncomingCall(data: Array<Any>) {
        try {
            val json = data[0] as? JSONObject
            json?.let {
//                val call = Call(
//                    id = it.getString("call_id"),
//                    targetUserId = it.getString("caller_id"),
//                    callerName = it.getString("caller_name"),
//                    isOutgoing = false,
//                    startTime = System.currentTimeMillis()
//                )
//                Handler(Looper.getMainLooper()).post {
//                    listeners.forEach { listener -> listener.onCallReceived(call) }
//                }
            }
        } catch (e: Exception) {
            Log.e("NetworkManager", "Error handling incoming call", e)
        }
    }

    private fun handleCallAccepted(data: Array<Any>) {
        try {
            val json = data[0] as? JSONObject
            val callId = json?.getString("call_id")
            callId?.let {
                Handler(Looper.getMainLooper()).post {
                    listeners.forEach { listener -> listener.onCallAccepted(it) }
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkManager", "Error handling call accepted", e)
        }
    }

    private fun handleCallRejected(data: Array<Any>) {
        try {
            val json = data[0] as? JSONObject
            val callId = json?.getString("call_id")
            callId?.let {
                Handler(Looper.getMainLooper()).post {
                    listeners.forEach { listener -> listener.onCallRejected(it) }
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkManager", "Error handling call rejected", e)
        }
    }

    private fun handleCallEnded(data: Array<Any>) {
        try {
            val json = data[0] as? JSONObject
            val callId = json?.getString("call_id")
            callId?.let {
                Handler(Looper.getMainLooper()).post {
                    listeners.forEach { listener -> listener.onCallEnded(it) }
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkManager", "Error handling call ended", e)
        }
    }

    // Метод для получения Socket.IO сокета (для WebRTC)
    fun getSocket(): Socket? {
        return socketIO
    }

    fun disconnect() {
        when (connectionType) {
            ConnectionType.WEBSOCKET -> {
                webSocket?.close(1000, "User disconnected")
                webSocket = null
            }
            ConnectionType.SOCKET_IO -> {
                socketIO?.disconnect()
                socketIO = null
            }
        }
        isConnected = false
    }

    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
//                "incoming_call" -> {
//                    val call = Call(
//                        id = json.getString("call_id"),
//                        targetUserId = json.getString("caller_id"),
//                        callerName = json.getString("caller_name"),
//                        isOutgoing = false,
//                        startTime = System.currentTimeMillis()
//                    )
//                    Handler(Looper.getMainLooper()).post {
//                        listeners.forEach { it.onCallReceived(call) }
//                    }
//                }
                "call_accepted" -> {
                    val callId = json.getString("call_id")
                    Handler(Looper.getMainLooper()).post {
                        listeners.forEach { it.onCallAccepted(callId) }
                    }
                }
                "call_rejected" -> {
                    val callId = json.getString("call_id")
                    Handler(Looper.getMainLooper()).post {
                        listeners.forEach { it.onCallRejected(callId) }
                    }
                }
                "call_ended" -> {
                    val callId = json.getString("call_id")
                    Handler(Looper.getMainLooper()).post {
                        listeners.forEach { it.onCallEnded(callId) }
                    }
                }
                "ping" -> {
                    sendPong()
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkManager", "Error handling message: ${e.message}", e)
        }
    }

    // Отправка сигнальных сообщений через текущее соединение
    fun sendCallRequest(targetUserId: String, callerName: String): String {
        val callId = System.currentTimeMillis().toString()

        when (connectionType) {
            ConnectionType.WEBSOCKET -> {
                val message = JSONObject().apply {
                    put("type", "call_request")
                    put("call_id", callId)
                    put("target_user_id", targetUserId)
                    put("caller_name", callerName)
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                webSocket?.send(message)
            }
            ConnectionType.SOCKET_IO -> {
                val message = JSONObject().apply {
                    put("type", "call_request")
                    put("call_id", callId)
                    put("target_user_id", targetUserId)
                    put("caller_name", callerName)
                    put("timestamp", System.currentTimeMillis())
                }
                socketIO?.emit("call_request", message)
            }
        }

        return callId
    }

    fun sendCallAccept(callId: String) {
        when (connectionType) {
            ConnectionType.WEBSOCKET -> {
                val message = JSONObject().apply {
                    put("type", "call_accept")
                    put("call_id", callId)
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                webSocket?.send(message)
            }
            ConnectionType.SOCKET_IO -> {
                val message = JSONObject().apply {
                    put("type", "call_accept")
                    put("call_id", callId)
                    put("timestamp", System.currentTimeMillis())
                }
                socketIO?.emit("call_accept", message)
            }
        }
    }

    fun sendCallReject(callId: String) {
        when (connectionType) {
            ConnectionType.WEBSOCKET -> {
                val message = JSONObject().apply {
                    put("type", "call_reject")
                    put("call_id", callId)
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                webSocket?.send(message)
            }
            ConnectionType.SOCKET_IO -> {
                val message = JSONObject().apply {
                    put("type", "call_reject")
                    put("call_id", callId)
                    put("timestamp", System.currentTimeMillis())
                }
                socketIO?.emit("call_reject", message)
            }
        }
    }

    fun sendCallEnd(callId: String) {
        when (connectionType) {
            ConnectionType.WEBSOCKET -> {
                val message = JSONObject().apply {
                    put("type", "call_end")
                    put("call_id", callId)
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                webSocket?.send(message)
            }
            ConnectionType.SOCKET_IO -> {
                val message = JSONObject().apply {
                    put("type", "call_end")
                    put("call_id", callId)
                    put("timestamp", System.currentTimeMillis())
                }
                socketIO?.emit("call_end", message)
            }
        }
    }

    private fun sendPong() {
        when (connectionType) {
            ConnectionType.WEBSOCKET -> {
                val message = JSONObject().apply {
                    put("type", "pong")
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                webSocket?.send(message)
            }
            ConnectionType.SOCKET_IO -> {
                val message = JSONObject().apply {
                    put("type", "pong")
                    put("timestamp", System.currentTimeMillis())
                }
                socketIO?.emit("pong", message)
            }
        }
    }

    // Отправка произвольного сообщения
    fun sendMessage(type: String, data: Map<String, Any> = emptyMap()) {
        when (connectionType) {
            ConnectionType.WEBSOCKET -> {
                val message = JSONObject().apply {
                    put("type", type)
                    put("timestamp", System.currentTimeMillis())
                    data.forEach { (key, value) ->
                        put(key, value)
                    }
                }.toString()
                webSocket?.send(message)
            }
            ConnectionType.SOCKET_IO -> {
                val message = JSONObject().apply {
                    put("type", type)
                    put("timestamp", System.currentTimeMillis())
                    data.forEach { (key, value) ->
                        put(key, value)
                    }
                }
                socketIO?.emit(type, message)
            }
        }
    }

    fun isConnected(): Boolean = isConnected

    fun getConnectionType(): ConnectionType = connectionType

    fun getConnectionState(): String {
        return when {
            isConnected -> "CONNECTED"
            webSocket != null || socketIO != null -> "CONNECTING"
            else -> "DISCONNECTED"
        }
    }
}