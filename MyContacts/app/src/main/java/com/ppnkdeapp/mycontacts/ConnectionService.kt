package com.ppnkdeapp.mycontacts.call

import android.app.*
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ppnkdeapp.mycontacts.MyApp
import com.ppnkdeapp.mycontacts.call.CallActivity
import com.ppnkdeapp.mycontacts.call.ActualCall
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ConnectionService : Service() {

    private val TAG = "ConnectionService"

    private var webRTCClient: WebRTCClient? = null
    private var deviceId: String = ""
    private var serverUrl: String = ""
    private var userId: String = ""

    private val binder = ConnectionServiceBinder()
    private var pollingJob: Job? = null
    private var isPolling = false

    // Polling интервал (5 секунд)
    private val POLLING_INTERVAL = 5000L
    
    // 🔥 НОВОЕ: Переменные для отслеживания статуса подключения
    private var connectionStatus = "Отключено"
    private var connectedUsersCount = 0
    private var isConnectedToServer = false
    
    // 🔥 НОВОЕ: Отслеживание currentActualCall
    private var currentActualCall: ActualCall? = null
    private var actualCallObserver: Job? = null

    inner class ConnectionServiceBinder : Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔄 ConnectionService created")
        createNotificationChannel()
        createCallNotificationChannel()
        createStatusNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 ConnectionService onStartCommand called")
        Log.d(TAG, "   - flags: $flags")
        Log.d(TAG, "   - startId: $startId")
        Log.d(TAG, "   - intent: $intent")

        intent?.let {
            serverUrl = it.getStringExtra("server_url") ?: ""
            userId = it.getStringExtra("user_id") ?: ""
            Log.d(TAG, "   - serverUrl: $serverUrl")
            Log.d(TAG, "   - userId: ${userId.take(8)}...")
            
            // 🔥 НОВОЕ: Обработка push-уведомлений
            val action = it.getStringExtra("action")
            if (action == "handle_push_notification") {
                val notificationData = it.getStringExtra("notification_data")
                if (notificationData != null) {
                    try {
                        val jsonData = JSONObject(notificationData)
                        handlePushNotification(jsonData)
                        return START_NOT_STICKY
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing push notification data: ${e.message}")
                    }
                }
            }
        }

        if (serverUrl.isEmpty() || userId.isEmpty()) {
            Log.e(TAG, "❌ Missing server URL or user ID")
            Log.e(TAG, "   - serverUrl: '$serverUrl'")
            Log.e(TAG, "   - userId: '$userId'")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "✅ Starting ConnectionService initialization...")
        initializeService()
        return START_STICKY
    }

    private fun initializeService() {
        deviceId = getDeviceIdentifier()
        Log.d(TAG, "📱 Device ID: $deviceId")

        // 🔥 НОВОЕ: Показываем уведомление о статусе
        updateConnectionStatus("Инициализация...", false, 0)

        // Запускаем polling
        startPolling()

        // Инициализируем WebRTCClient для обработки звонков
        initializeWebRTCClient()
        
        // Регистрируем пользователя на сервере
        registerUserOnServer()
        
        // Регистрируем устройство после инициализации WebRTCClient
        registerDevice()
        
        // 🔥 НОВОЕ: Подписываемся на push-уведомления
        subscribeToPushNotifications()
        
        // 🔥 НОВОЕ: Запускаем отслеживание currentActualCall
        startActualCallObserver()
        
        // 🔥 НОВОЕ: Обновляем статус после инициализации (регистрация пользователя выполняется асинхронно)
        updateConnectionStatus("Регистрация...", false, 0)
    }

    private fun initializeWebRTCClient() {
        // Проверяем, не инициализирован ли уже WebRTCClient
        if (webRTCClient != null) {
            Log.d(TAG, "⚠️ WebRTCClient already initialized, skipping")
            return
        }
        
        webRTCClient = WebRTCClient(
            context = applicationContext,
            serverUrl = serverUrl,
            userId = userId,
            listener = object : WebRTCClient.WebRTCListener {
                override fun onCallInitiated(callId: String) {
                    Log.d(TAG, "📞 Call initiated: $callId")
                }
                override fun onCallAccepted(callId: String) {
                    Log.d(TAG, "✅ Call accepted: $callId")
                }
                override fun onCallRejected(callId: String) {
                    Log.d(TAG, "❌ Call rejected: $callId")
                }
                override fun onCallEnded(callId: String) {
                    Log.d(TAG, "📞 Call ended: $callId")
                }
                override fun onCallFailed(callId: String, error: String) {
                    Log.e(TAG, "❌ Call failed: $callId - $error")
                }
                override fun onIncomingCall(callId: String, fromUserId: String) {
                    Log.d(TAG, "📥 Incoming call: $callId from $fromUserId")
                    // НЕ показываем уведомление здесь - это делается в handleIncomingCallInService
                }
                override fun onWebRTCConnected() {
                    Log.d(TAG, "✅ WebRTC connected")
                }
                override fun onWebRTCDisconnected() {
                    Log.d(TAG, "📡 WebRTC disconnected")
                }
                override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "🧊 ICE state: $state")
                }
            }
        )
    }

    private fun startPolling() {
        if (isPolling) {
            Log.d(TAG, "🔄 Polling already running")
            return
        }

        Log.d(TAG, "🔄 Starting polling for user: $userId, deviceId: $deviceId")
        isPolling = true
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "🔄 Polling coroutine started")
            while (isPolling) {
                try {
                    Log.d(TAG, "🔄 Polling iteration...")
                    checkForNotifications()
                    delay(POLLING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Polling error: ${e.message}")
                    e.printStackTrace()
                    delay(POLLING_INTERVAL * 2) // Увеличиваем интервал при ошибке
                }
            }
            Log.d(TAG, "🔄 Polling coroutine ended")
        }
        Log.d(TAG, "🔄 Started polling every ${POLLING_INTERVAL}ms")
    }

    private fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        Log.d(TAG, "🛑 Stopped polling")
    }

    private fun checkForNotifications() {
        try {
            val url = URL("$serverUrl/api/notifications/pending?userId=$userId&deviceId=$deviceId")
            Log.d(TAG, "📬 Checking notifications from: $url")
            
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            Log.d(TAG, "📬 Response code: $responseCode")
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "📬 Response: $response")
                
                val jsonResponse = JSONObject(response)

                if (jsonResponse.optBoolean("success", false)) {
                    val notifications = jsonResponse.optJSONArray("notifications")
                    val count = notifications?.length() ?: 0
                    Log.d(TAG, "📬 Found $count notifications")
                    
                    if (count > 0) {
                        Log.d(TAG, "📬 Notifications details:")
                        notifications?.let { array ->
                            for (i in 0 until array.length()) {
                                val notification = array.getJSONObject(i)
                                Log.d(TAG, "📬 Processing notification $i: ${notification.toString()}")
                                processNotification(notification)
                            }
                        }
                    } else {
                        Log.d(TAG, "📬 No notifications found for user: $userId")
                    }

                    // Подтверждаем получение уведомлений
                    if (notifications != null && notifications.length() > 0) {
                        acknowledgeNotifications()
                    }
                } else {
                    Log.w(TAG, "📬 Server returned success=false")
                }
            } else {
                Log.e(TAG, "📬 HTTP error: $responseCode")
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking notifications: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun processNotification(notification: JSONObject) {
        val type = notification.optString("type")
        Log.d(TAG, "📨 Processing notification: $type")
        Log.d(TAG, "📨 Full notification: ${notification.toString()}")

        when (type) {
            "actual_call_update" -> {
                Log.d(TAG, "📨 Processing actual_call_update notification")
                val data = notification.optJSONObject("data")
                Log.d(TAG, "📨 Data object: ${data?.toString()}")
                
                val actualCallData = data?.optJSONObject("actualCall")
                Log.d(TAG, "📨 ActualCall data: ${actualCallData?.toString()}")
                
                actualCallData?.let { call ->
                    val actualCall = ActualCall(
                        callId = call.optString("callId"),
                        callerId = call.optString("callerId"),
                        recipientId = call.optString("recipientId"),
                        status = call.optString("status"),
                        step = call.optString("step"),
                        createdAt = call.optLong("createdAt"),
                        offerSdp = call.optString("offerSdp"),
                        answerSdp = call.optString("answerSdp")
                    )
                    
                    Log.d(TAG, "📞 Processing ActualCall from polling: ${actualCall.callId}, step: ${actualCall.step}")
                    Log.d(TAG, "📞 ActualCall details: callerId=${actualCall.callerId}, recipientId=${actualCall.recipientId}")
                    
                    // Обновляем currentActualCall в MyApp на главном потоке
                    val myApp = applicationContext as? MyApp
                    if (myApp != null) {
                        Log.d(TAG, "📞 Updating currentActualCall in MyApp")
                        // Используем Handler для переключения на главный поток
                        Handler(Looper.getMainLooper()).post {
                            myApp.setCurrentActualCall(actualCall)
                            Log.d(TAG, "✅ currentActualCall updated successfully")
                        }
                    } else {
                        Log.e(TAG, "❌ MyApp is null!")
                    }
                    
                    // Если это входящий звонок, запускаем CallActivity
                    if (actualCall.step == "request_call" || actualCall.step == "offer") {
                        Log.d(TAG, "📞 Launching CallActivity for incoming call")
                        launchCallActivity(actualCall)
                    } else if (actualCall.step == "accept_call") {
                        // Проверяем, кто принял звонок
                        Log.d(TAG, "📞 Processing accept_call: callerId=${actualCall.callerId}, recipientId=${actualCall.recipientId}, myUserId=$userId")
                        if (actualCall.callerId == userId) {
                            // Это отправитель - запускаем WebRTC соединение
                            Log.d(TAG, "📞 Call accepted by recipient, starting WebRTC connection for caller")
                            if (webRTCClient != null) {
                                webRTCClient?.startWebRTCConnection(actualCall)
                            } else {
                                Log.e(TAG, "❌ WebRTCClient is null!")
                            }
                        } else if (actualCall.recipientId == userId) {
                            // Это получатель - запускаем WebRTC соединение для входящего звонка
                            Log.d(TAG, "📞 Call accepted by me, starting WebRTC connection for recipient")
                            if (webRTCClient != null) {
                                webRTCClient?.handleIncomingWebRTCConnection(actualCall)
                            } else {
                                Log.e(TAG, "❌ WebRTCClient is null!")
                            }
                        } else {
                            Log.w(TAG, "⚠️ Unknown role in accept_call: callerId=${actualCall.callerId}, recipientId=${actualCall.recipientId}, myUserId=$userId")
                        }
                    } else {
                        Log.d(TAG, "📞 Not an incoming call, step: ${actualCall.step}")
                    }
                } ?: run {
                    Log.e(TAG, "❌ actualCallData is null!")
                }
            }
            "incoming_call" -> {
                val callData = notification.optJSONObject("data")
                callData?.let { call ->
                    val actualCall = ActualCall(
                        callId = call.optString("callId"),
                        callerId = call.optString("callerId"),
                        recipientId = userId,
                        status = call.optString("status"),
                        step = call.optString("step"),
                        createdAt = call.optLong("createdAt"),
                        offerSdp = call.optString("offerSdp"),
                        answerSdp = call.optString("answerSdp")
                    )
                    
                    // Обновляем ActualCall в MyApp (перезаписываем текущее значение)
                    val myApp = applicationContext as? MyApp
                    if (myApp != null) {
                        Handler(Looper.getMainLooper()).post {
                            myApp.setCurrentActualCall(actualCall)
                            Log.d(TAG, "✅ ActualCall updated in MyApp: ${actualCall.callId}")
                        }
                    }
                    
                    // Обрабатываем входящий звонок через ConnectionService
                    handleIncomingCallInService(actualCall)
                }
            }
            "call_status_update" -> {
                val callData = notification.optJSONObject("data")
                callData?.let { call ->
                    val actualCall = ActualCall(
                        callId = call.optString("callId"),
                        callerId = call.optString("callerId"),
                        recipientId = call.optString("recipientId"),
                        status = call.optString("status"),
                        step = call.optString("step"),
                        createdAt = call.optLong("createdAt"),
                        offerSdp = call.optString("offerSdp"),
                        answerSdp = call.optString("answerSdp")
                    )
                    
                    // Обновляем ActualCall в MyApp
                    val myApp = applicationContext as? MyApp
                    if (myApp != null) {
                        Handler(Looper.getMainLooper()).post {
                            myApp.setCurrentActualCall(actualCall)
                        }
                    }
                    
                    // Обрабатываем обновление статуса через ConnectionService
                    handleCallStatusUpdateInService(actualCall)
                }
            }
            "device_list_updated" -> {
                val deviceList = mutableListOf<String>()
                val deviceDetails = mutableListOf<Map<String, Any>>()
                
                // Получаем список ID устройств
                val devicesArray = notification.optJSONArray("deviceList")
                devicesArray?.let { array ->
                    for (i in 0 until array.length()) {
                        deviceList.add(array.getString(i))
                    }
                }
                
                // Получаем детальную информацию об устройствах
                val devicesDetailsArray = notification.optJSONArray("deviceDetails")
                devicesDetailsArray?.let { array ->
                    for (i in 0 until array.length()) {
                        val deviceObj = array.getJSONObject(i)
                        val deviceMap = mapOf(
                            "deviceId" to deviceObj.optString("deviceId"),
                            "userId" to deviceObj.optString("userId"),
                            "connectedAt" to deviceObj.optString("connectedAt")
                        )
                        deviceDetails.add(deviceMap)
                    }
                }
                
                val totalDevices = notification.optInt("totalDevices", deviceList.size)
                Log.d(TAG, "📱 Device list updated: $totalDevices devices")
                Log.d(TAG, "📱 Device details: $deviceDetails")
                
                // Обновляем UI через MyApp
                val myApp = applicationContext as? MyApp
                myApp?.updateActiveConnections(deviceList)
                
                // 🔥 НОВОЕ: Обновляем статус подключения с информацией об устройствах
                updateConnectionStatus("Подключено", true, totalDevices)
                
                Log.d(TAG, "✅ Device list updated in UI: ${deviceList.size} devices")
            }
            "user_list_updated" -> {
                val userList = mutableListOf<String>()
                val usersArray = notification.optJSONArray("userList")
                usersArray?.let { array ->
                    for (i in 0 until array.length()) {
                        userList.add(array.getString(i))
                    }
                }
                val totalUsers = notification.optInt("totalUsers", 0)
                Log.d(TAG, "👥 User list updated: $totalUsers users")
                
                // 🔥 НОВОЕ: Обновляем статус подключения
                updateConnectionStatus("Подключено", true, totalUsers)
                
                // Обновляем UI через MyApp
                val myApp = applicationContext as? MyApp
                myApp?.updateActiveConnections(userList)
                Log.d(TAG, "✅ User list updated in UI: ${userList.size} users")
            }
        }
    }

    private fun acknowledgeNotifications() {
        Executors.newSingleThreadExecutor().execute {
            try {
                val url = URL("$serverUrl/api/notifications/acknowledge")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val json = JSONObject().apply {
                    put("userId", userId)
                    put("deviceId", deviceId)
                }

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                connection.responseCode // Просто отправляем, не важно ответ
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error acknowledging notifications: ${e.message}")
            }
        }
    }

    private fun showIncomingCallNotification(callId: String, fromUserId: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Создаем Intent для действий
        val acceptIntent = Intent(this, CallReceiver::class.java).apply {
            action = "ACCEPT_CALL"
            putExtra("callId", callId)
        }

        val rejectIntent = Intent(this, CallReceiver::class.java).apply {
            action = "REJECT_CALL"
            putExtra("callId", callId)
        }

        val pendingAcceptIntent = PendingIntent.getBroadcast(
            this, callId.hashCode(), acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pendingRejectIntent = PendingIntent.getBroadcast(
            this, callId.hashCode() + 1, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setContentTitle("📞 Входящий звонок")
            .setContentText("Вам звонит $fromUserId")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingAcceptIntent, true) // Полноэкранное уведомление
            .setTimeoutAfter(30000) // 30 секунд таймаут
            .setAutoCancel(false) // Не закрывать автоматически
            .addAction(
                android.R.drawable.ic_menu_call,
                "Принять",
                pendingAcceptIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отклонить",
                pendingRejectIntent
            )
            .build()

        notificationManager.notify(callId.hashCode(), notification)
    }

    private fun registerDevice() {
        // Регистрируем устройство через webRTCClient, если он инициализирован
        webRTCClient?.let { client ->
            Log.d(TAG, "📱 Registering device: $deviceId for user: $userId")
            client.registerDevice(deviceId) { deviceList ->
                Log.d(TAG, "✅ Device registered. Connected devices: ${deviceList?.size ?: 0}")
                
                // Обновляем UI через MyApp
                deviceList?.let { devices ->
                    val myApp = applicationContext as? MyApp
                    myApp?.updateActiveConnections(devices)
                    Log.d(TAG, "📱 Updated active connections in UI: ${devices.size} devices")
                }
            }
        } ?: run {
            Log.w(TAG, "⚠️ Cannot register device: webRTCClient is null")
        }
    }

    private fun unregisterDevice() {
        Log.d(TAG, "📱 unregisterDevice() called")
        Log.d(TAG, "   - deviceId: '$deviceId'")
        Log.d(TAG, "   - serverUrl: '$serverUrl'")
        
        if (deviceId.isNotEmpty()) {
            Log.d(TAG, "📱 Starting device unregistration: $deviceId")
            
            // Сначала пробуем через WebRTCClient если он доступен
            if (webRTCClient != null) {
                try {
                    Log.d(TAG, "📱 Attempting WebRTCClient unregistration...")
                    webRTCClient?.unregisterDevice(deviceId) { success ->
                        if (success) {
                            Log.d(TAG, "✅ Device unregistered successfully via WebRTCClient")
                        } else {
                            Log.e(TAG, "❌ Failed to unregister device via WebRTCClient")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error unregistering device via WebRTCClient: ${e.message}")
                }
            } else {
                Log.d(TAG, "⚠️ WebRTCClient is null, skipping WebRTC unregistration")
            }
            
            // Дополнительно отправляем HTTP запрос напрямую для надежности
            Log.d(TAG, "📱 Starting HTTP unregistration...")
            Executors.newSingleThreadExecutor().execute {
                try {
                    val fullUrl = "$serverUrl/api/devices/unregister"
                    Log.d(TAG, "📱 HTTP unregister URL: $fullUrl")
                    
                    val url = URL(fullUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 5000 // 5 секунд таймаут
                    connection.readTimeout = 5000

                    val json = JSONObject().apply {
                        put("deviceId", deviceId)
                    }
                    
                    Log.d(TAG, "📱 Sending JSON: ${json.toString()}")

                    connection.outputStream.use { os ->
                        os.write(json.toString().toByteArray())
                    }

                    val responseCode = connection.responseCode
                    Log.d(TAG, "📱 HTTP response code: $responseCode")
                    
                    if (responseCode == 200 || responseCode == 204) {
                        Log.d(TAG, "✅ Device $deviceId successfully unregistered from server via HTTP")
                    } else {
                        Log.e(TAG, "❌ HTTP error unregistering device: $responseCode")
                        // Читаем ответ сервера для отладки
                        try {
                            val errorResponse = connection.errorStream?.bufferedReader()?.readText()
                            Log.e(TAG, "❌ Server error response: $errorResponse")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Could not read error response: ${e.message}")
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error unregistering device via HTTP: ${e.message}")
                    e.printStackTrace()
                }
            }
        } else {
            Log.d(TAG, "⚠️ Device ID is empty, skipping unregistration")
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Регистрация пользователя на сервере
    private fun registerUserOnServer() {
        if (userId.isNotEmpty()) {
            Log.d(TAG, "👤 Starting user registration: $userId")
            Executors.newSingleThreadExecutor().execute {
                try {
                    val url = URL("$serverUrl/api/users/register")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 10000 // 10 секунд таймаут
                    connection.readTimeout = 10000

                    val json = JSONObject().apply {
                        put("userId", userId)
                    }

                    connection.outputStream.use { os ->
                        os.write(json.toString().toByteArray())
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == 200 || responseCode == 201) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(response)
                        
                        if (jsonResponse.optBoolean("success", false)) {
                            Log.d(TAG, "✅ User $userId successfully registered on server via HTTP")
                            
                            // Получаем список подключенных пользователей
                            val userList = mutableListOf<String>()
                            val usersArray = jsonResponse.optJSONArray("userList")
                            usersArray?.let { array ->
                                for (i in 0 until array.length()) {
                                    userList.add(array.getString(i))
                                }
                            }
                            
                            // Обновляем UI через MyApp
                            val myApp = applicationContext as? MyApp
                            myApp?.updateActiveConnections(userList)
                            Log.d(TAG, "📱 Updated active connections from user registration: ${userList.size} users")
                            
                            // Обновляем статус подключения
                            updateConnectionStatus("Подключено", true, userList.size)
                        } else {
                            Log.e(TAG, "❌ HTTP user registration failed: ${jsonResponse.optString("error")}")
                            updateConnectionStatus("Ошибка регистрации", false, 0)
                        }
                    } else {
                        Log.e(TAG, "❌ HTTP error registering user: $responseCode")
                        updateConnectionStatus("Ошибка подключения", false, 0)
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error registering user via HTTP: ${e.message}")
                    updateConnectionStatus("Ошибка сети", false, 0)
                }
            }
        } else {
            Log.d(TAG, "⚠️ Cannot register user: userId is empty")
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Отмена регистрации пользователя на сервере
    private fun unregisterUserFromServer() {
        if (userId.isNotEmpty()) {
            Log.d(TAG, "👤 Starting user unregistration: $userId")
            Executors.newSingleThreadExecutor().execute {
                try {
                    val url = URL("$serverUrl/api/users/unregister")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 5000 // 5 секунд таймаут
                    connection.readTimeout = 5000

                    val json = JSONObject().apply {
                        put("userId", userId)
                    }

                    connection.outputStream.use { os ->
                        os.write(json.toString().toByteArray())
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == 200 || responseCode == 204) {
                        Log.d(TAG, "✅ User $userId successfully unregistered from server via HTTP")
                    } else {
                        Log.e(TAG, "❌ HTTP error unregistering user: $responseCode")
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error unregistering user via HTTP: ${e.message}")
                }
            }
        } else {
            Log.d(TAG, "⚠️ Cannot unregister user: userId is empty")
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Обновление уведомления о статусе
    private fun updateStatusNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = createStatusNotification()
            notificationManager.notify(STATUS_NOTIFICATION_ID, notification)
            Log.d(TAG, "📊 Status notification updated: $connectionStatus, users: $connectedUsersCount")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating status notification: ${e.message}")
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Обновление статуса подключения
    private fun updateConnectionStatus(status: String, isConnected: Boolean, usersCount: Int = 0) {
        connectionStatus = status
        isConnectedToServer = isConnected
        connectedUsersCount = usersCount
        updateStatusNotification()
    }

    private fun getDeviceIdentifier(): String {
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "android_${System.currentTimeMillis()}"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background connection service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createCallNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call notifications"
                setShowBadge(true)
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true) // Обходить режим "Не беспокоить"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Создание канала для уведомлений о статусе
    private fun createStatusNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "Connection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Connection status notifications"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyContacts")
            .setContentText("Служба подключения активна")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    // 🔥 НОВЫЙ МЕТОД: Создание уведомления о статусе подключения
    private fun createStatusNotification(): Notification {
        // Создаем Intent для кнопки "Остановить"
        val stopIntent = Intent(this, ConnectionServiceActionReceiver::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 
            0, 
            stopIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = buildString {
            append("Статус: $connectionStatus")
            if (isConnectedToServer) {
                append("\nПодключено пользователей: $connectedUsersCount")
            }
            append("\nПользователь: ${userId.take(8)}...")
        }

        return NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setContentTitle("📡 MyContacts - Статус подключения")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "🛑 ConnectionService destroying - starting cleanup process")
        Log.d(TAG, "   - deviceId: '$deviceId'")
        Log.d(TAG, "   - userId: '$userId'")
        Log.d(TAG, "   - serverUrl: '$serverUrl'")
        
        // 🔥 КРИТИЧЕСКИ ВАЖНО: Сначала останавливаем polling чтобы не было новых запросов
        stopPolling()
        Log.d(TAG, "📡 Polling stopped")
        
        // 🔥 НОВОЕ: Останавливаем отслеживание currentActualCall
        stopActualCallObserver()
        Log.d(TAG, "📞 ActualCall observer stopped")
        
        // 🔥 КРИТИЧЕСКИ ВАЖНО: Отменяем регистрацию устройства на сервере
        Log.d(TAG, "📱 Unregistering device from server: $deviceId")
        unregisterDevice()
        
        // 🔥 КРИТИЧЕСКИ ВАЖНО: Отменяем регистрацию пользователя на сервере
        Log.d(TAG, "👤 Unregistering user from server: $userId")
        unregisterUserFromServer()
        
        // 🔥 НОВОЕ: Скрываем уведомление о статусе
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(STATUS_NOTIFICATION_ID)
            Log.d(TAG, "📊 Status notification cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cancelling status notification: ${e.message}")
        }
        
//        // 🔥 НОВОЕ: Очищаем WebRTC клиент
//        try {
//            webRTCClient?.disconnect()
//            webRTCClient = null
//            Log.d(TAG, "🔌 WebRTC client disconnected and cleaned up")
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Error cleaning up WebRTC client: ${e.message}")
//        }
        
        // 🔥 НОВОЕ: Очищаем переменные состояния
        deviceId = ""
        userId = ""
        serverUrl = ""
        currentActualCall = null
        
        Log.d(TAG, "🧹 ConnectionService cleanup completed")
        stopForeground(true)
        super.onDestroy()
    }

    /**
     * Обработка входящего звонка в ConnectionService
     * Этот метод вызывается когда ConnectionService получает входящий звонок в фоне
     */
    private fun handleIncomingCallInService(actualCall: ActualCall) {
        Log.d(TAG, "📥 ConnectionService handling incoming call: ${actualCall.callId} from: ${actualCall.callerId}")
        
        // Показываем уведомление о входящем звонке
        showIncomingCallNotification(actualCall.callId, actualCall.callerId)
        
        // Уведомляем MyApp о входящем звонке
        val myApp = applicationContext as? MyApp
        myApp?.handleIncomingCall(actualCall.callerId, actualCall.callId)
        
        Log.d(TAG, "✅ Incoming call processed in ConnectionService")
    }

    /**
     * Обработка обновления статуса звонка в ConnectionService
     */
    private fun handleCallStatusUpdateInService(actualCall: ActualCall) {
        Log.d(TAG, "🔄 ConnectionService handling call status update: ${actualCall.status} for call: ${actualCall.callId}")
        
        // Обрабатываем через WebRTCClient только если он инициализирован
        webRTCClient?.handleCallStatusUpdate(actualCall)
        
        Log.d(TAG, "✅ Call status update processed in ConnectionService")
    }

    /**
     * Принятие звонка из ConnectionService
     */
    fun acceptCallFromService(callId: String, callerId: String) {
        Log.d(TAG, "📞 ConnectionService accepting call: $callId")
        
        // Получаем текущий ActualCall из MyApp
        val myApp = applicationContext as? MyApp
        val currentCall = myApp?.getCurrentActualCall()
        
        if (currentCall != null && currentCall.callId == callId) {
            // Обновляем статус на "accepted"
            val acceptedCall = currentCall.copy(status = "accepted")
            if (myApp != null) {
                Handler(Looper.getMainLooper()).post {
                    myApp.setCurrentActualCall(acceptedCall)
                }
            }
            
            // Уведомляем MyApp о принятии звонка
            myApp?.acceptCall(callerId, callId)
            
            Log.d(TAG, "✅ Call accepted in ConnectionService")
        } else {
            Log.e(TAG, "❌ Call not found or callId mismatch")
        }
    }

    /**
     * Отклонение звонка из ConnectionService
     */
    fun rejectCallFromService(callId: String, callerId: String) {
        Log.d(TAG, "❌ ConnectionService rejecting call: $callId")
        
        // Получаем текущий ActualCall из MyApp
        val myApp = applicationContext as? MyApp
        val currentCall = myApp?.getCurrentActualCall()
        
        if (currentCall != null && currentCall.callId == callId) {
            // Обновляем статус на "rejected"
            val rejectedCall = currentCall.copy(status = "rejected")
            if (myApp != null) {
                Handler(Looper.getMainLooper()).post {
                    myApp.setCurrentActualCall(rejectedCall)
                }
            }
            
            // Уведомляем MyApp об отклонении звонка
            myApp?.rejectCall(callerId, callId)
            
            Log.d(TAG, "✅ Call rejected in ConnectionService")
        } else {
            Log.e(TAG, "❌ Call not found or callId mismatch")
        }
    }

    // 🔥 НОВЫЕ МЕТОДЫ ДЛЯ ОТСЛЕЖИВАНИЯ currentActualCall
    
    /**
     * Запуск отслеживания изменений currentActualCall в MyApp
     */
    private fun startActualCallObserver() {
        actualCallObserver = CoroutineScope(Dispatchers.IO).launch {
            val myApp = applicationContext as? MyApp
            if (myApp != null) {
                // Подписываемся на изменения currentActualCall
                myApp.subscribeToActualCallChanges { actualCall ->
                    Log.d(TAG, "📞 ActualCall changed: ${actualCall?.callId}")
                    
                    // Если callId не null, отправляем на сервер
                    if (actualCall?.callId != null) {
                        currentActualCall = actualCall
                        sendActualCallToServer(actualCall)
                        
                        // Если это входящий звонок, запускаем CallActivity
                        if (actualCall.step == "offer" || actualCall.step == "request_call") {
                            launchCallActivity(actualCall)
                        }
                    } else {
                        // Если callId null, очищаем текущий звонок
                        currentActualCall = null
                    }
                }
            }
        }
    }
    
    /**
     * Остановка отслеживания currentActualCall
     */
    private fun stopActualCallObserver() {
        actualCallObserver?.cancel()
        actualCallObserver = null
    }
    
    /**
     * Отправка ActualCall на сервер по HTTP
     */
    private fun sendActualCallToServer(actualCall: ActualCall) {
        CoroutineScope(Dispatchers.IO).launch {
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
                    put("step", actualCall.step)
                    put("createdAt", actualCall.createdAt)
                    put("offerSdp", actualCall.offerSdp)
                    put("answerSdp", actualCall.answerSdp)
                }

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200 || responseCode == 201) {
                    Log.d(TAG, "✅ ActualCall sent to server successfully: ${actualCall.callId}")
                } else {
                    Log.e(TAG, "❌ Failed to send ActualCall, response: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending ActualCall: ${e.message}")
            }
        }
    }
    
    /**
     * Запуск CallActivity для отображения звонка
     */
    private fun launchCallActivity(actualCall: ActualCall) {
        try {
            // 🔥 SINGLETON: Проверяем, не запущена ли уже CallActivity
            if (CallActivity.isAlreadyRunning()) {
                Log.d(TAG, "📞 CallActivity already running, updating with new call data")
                // CallActivity уже запущена, она обновится через currentActualCall
                return
            }
            
            val myApp = applicationContext as? MyApp
            val contactName = myApp?.getContactName(actualCall.callerId) ?: "Неизвестный"
            
            val intent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("call_id", actualCall.callId)
                putExtra("caller_id", actualCall.callerId)
                putExtra("is_incoming", actualCall.step == "offer")
                putExtra("contact_name", contactName)
            }
            
            startActivity(intent)
            Log.d(TAG, "✅ CallActivity launched for call: ${actualCall.callId}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error launching CallActivity: ${e.message}")
        }
    }

    // Публичные методы
    fun getWebRTCClient(): WebRTCClient? = webRTCClient

    companion object {
        private const val NOTIFICATION_ID = 1234
        private const val CHANNEL_ID = "connection_service"
        private const val CALL_CHANNEL_ID = "incoming_calls"
        private const val STATUS_NOTIFICATION_ID = 1235
        private const val STATUS_CHANNEL_ID = "connection_status"

        fun startService(context: Context, serverUrl: String, userId: String) {
            Log.d("ConnectionService", "🚀 Starting ConnectionService...")
            Log.d("ConnectionService", "   - serverUrl: $serverUrl")
            Log.d("ConnectionService", "   - userId: ${userId.take(8)}...")
            Log.d("ConnectionService", "   - context: ${context.javaClass.simpleName}")
            
            try {
                val intent = Intent(context, ConnectionService::class.java).apply {
                    putExtra("server_url", serverUrl)
                    putExtra("user_id", userId)
                }
                
                Log.d("ConnectionService", "   - Intent created: ${intent.component}")
                Log.d("ConnectionService", "   - Intent action: ${intent.action}")
                
                val result = context.startService(intent)
                Log.d("ConnectionService", "   - startService result: $result")
                
                if (result != null) {
                    Log.d("ConnectionService", "✅ ConnectionService start requested successfully")
                } else {
                    Log.w("ConnectionService", "⚠️ ConnectionService start returned null")
                    Log.w("ConnectionService", "   - This usually means the service is already running or not found")
                    
                    // Попробуем альтернативный способ
                    try {
                        val alternativeIntent = Intent().apply {
                            setClassName(context, "com.ppnkdeapp.mycontacts.call.ConnectionService")
                            putExtra("server_url", serverUrl)
                            putExtra("user_id", userId)
                        }
                        val alternativeResult = context.startService(alternativeIntent)
                        Log.d("ConnectionService", "   - Alternative startService result: $alternativeResult")
                    } catch (e: Exception) {
                        Log.e("ConnectionService", "❌ Alternative startService also failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ConnectionService", "❌ Error starting ConnectionService: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Обработка входящих push-уведомлений
     */
    fun handlePushNotification(notificationData: JSONObject) {
        try {
            Log.d(TAG, "📱 Received push notification: ${notificationData.toString()}")
            
            val type = notificationData.optString("type")
            when (type) {
                "actual_call_update" -> {
                    val actualCallData = notificationData.optJSONObject("actualCall")
                    actualCallData?.let { callData ->
                        val actualCall = ActualCall(
                            callId = callData.optString("callId"),
                            callerId = callData.optString("callerId"),
                            recipientId = callData.optString("recipientId"),
                            status = callData.optString("status"),
                            step = callData.optString("step"),
                            createdAt = callData.optLong("createdAt"),
                            offerSdp = callData.optString("offerSdp"),
                            answerSdp = callData.optString("answerSdp")
                        )
                        
                        Log.d(TAG, "📞 Processing ActualCall from push: ${actualCall.callId}, step: ${actualCall.step}")
                        
                        // Обновляем currentActualCall в MyApp
                        val myApp = applicationContext as? MyApp
                        if (myApp != null) {
                            Handler(Looper.getMainLooper()).post {
                                myApp.setCurrentActualCall(actualCall)
                            }
                        }
                        
                        // Если это входящий звонок, запускаем CallActivity
                        if (actualCall.step == "request_call" || actualCall.step == "offer") {
                            launchCallActivity(actualCall)
                        }
                    }
                }
                "device_list_updated" -> {
                    val deviceList = mutableListOf<String>()
                    val deviceDetails = mutableListOf<Map<String, Any>>()
                    
                    // Получаем список ID устройств
                    val devicesArray = notificationData.optJSONArray("deviceList")
                    devicesArray?.let { array ->
                        for (i in 0 until array.length()) {
                            deviceList.add(array.getString(i))
                        }
                    }
                    
                    // Получаем детальную информацию об устройствах
                    val devicesDetailsArray = notificationData.optJSONArray("deviceDetails")
                    devicesDetailsArray?.let { array ->
                        for (i in 0 until array.length()) {
                            val deviceObj = array.getJSONObject(i)
                            val deviceMap = mapOf(
                                "deviceId" to deviceObj.optString("deviceId"),
                                "userId" to deviceObj.optString("userId"),
                                "connectedAt" to deviceObj.optString("connectedAt")
                            )
                            deviceDetails.add(deviceMap)
                        }
                    }
                    
                    val totalDevices = notificationData.optInt("totalDevices", deviceList.size)
                    Log.d(TAG, "📱 Device list updated via push: $totalDevices devices")
                    Log.d(TAG, "📱 Device details: $deviceDetails")
                    
                    // Обновляем UI через MyApp
                    val myApp = applicationContext as? MyApp
                    myApp?.updateActiveConnections(deviceList)
                    
                    // Обновляем статус подключения с информацией об устройствах
                    updateConnectionStatus("Подключено", true, totalDevices)
                    
                    Log.d(TAG, "✅ Device list updated in UI via push: ${deviceList.size} devices")
                }
                "connection_established" -> {
                    Log.d(TAG, "✅ Connection established notification received")
                }
                else -> {
                    Log.w(TAG, "⚠️ Unknown notification type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling push notification: ${e.message}")
        }
    }

    /**
     * Подписка на push-уведомления через WebPush
     */
    private fun subscribeToPushNotifications() {
        Executors.newSingleThreadExecutor().execute {
            try {
                Log.d(TAG, "📱 Subscribing to WebPush notifications for user: $userId")
                
                // Получаем VAPID ключ от сервера
                val vapidKey = getVapidPublicKey()
                if (vapidKey == null) {
                    Log.e(TAG, "❌ Failed to get VAPID public key")
                    return@execute
                }
                
                // Создаем WebPush subscription
                val subscription = createWebPushSubscription(vapidKey)
                if (subscription == null) {
                    Log.e(TAG, "❌ Failed to create WebPush subscription")
                    return@execute
                }
                
                // Отправляем подписку на сервер
                val success = sendSubscriptionToServer(subscription)
                if (success) {
                    Log.d(TAG, "✅ WebPush subscription successful")
                } else {
                    Log.e(TAG, "❌ WebPush subscription failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error subscribing to WebPush notifications: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Получение VAPID публичного ключа от сервера
     */
    private fun getVapidPublicKey(): String? {
        return try {
            val url = URL("$serverUrl/api/push/vapid-key")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val publicKey = jsonResponse.optString("publicKey")
                Log.d(TAG, "✅ VAPID public key received: ${publicKey.take(20)}...")
                publicKey
            } else {
                Log.e(TAG, "❌ HTTP error getting VAPID key: $responseCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting VAPID key: ${e.message}")
            null
        }
    }
    
    /**
     * Создание WebPush подписки
     */
    private fun createWebPushSubscription(vapidKey: String): JSONObject? {
        return try {
            // 🔥 ИСПРАВЛЕНИЕ: Используем реальный WebPush endpoint вместо FCM
            // В реальном приложении здесь должен быть настоящий WebPush endpoint
            // Для тестирования используем mock endpoint, который будет обработан сервером
            val endpoint = "https://webpush.example.com/push/$deviceId"
            
            // Генерируем ключи для WebPush
            val p256dhKey = generateMockP256dhKey()
            val authKey = generateMockAuthKey()
            
            val subscription = JSONObject().apply {
                put("endpoint", endpoint)
                put("keys", JSONObject().apply {
                    put("p256dh", p256dhKey)
                    put("auth", authKey)
                })
            }
            
            Log.d(TAG, "✅ WebPush subscription created with mock endpoint")
            Log.d(TAG, "   - endpoint: $endpoint")
            Log.d(TAG, "   - p256dh: ${p256dhKey.take(20)}...")
            Log.d(TAG, "   - auth: ${authKey.take(20)}...")
            subscription
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating WebPush subscription: ${e.message}")
            null
        }
    }
    
    /**
     * Отправка подписки на сервер
     */
    private fun sendSubscriptionToServer(subscription: JSONObject): Boolean {
        return try {
            val url = URL("$serverUrl/api/push/subscribe")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val json = JSONObject().apply {
                put("userId", userId)
                put("subscription", subscription)
            }
            
            connection.outputStream.use { os ->
                os.write(json.toString().toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val success = jsonResponse.optBoolean("success", false)
                if (success) {
                    Log.d(TAG, "✅ Subscription sent to server successfully")
                } else {
                    Log.e(TAG, "❌ Server rejected subscription: ${jsonResponse.optString("error")}")
                }
                success
            } else {
                Log.e(TAG, "❌ HTTP error sending subscription: $responseCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending subscription to server: ${e.message}")
            false
        }
    }
    
    /**
     * Генерация mock p256dh ключа (65 байт в base64)
     * Используем валидный P-256 публичный ключ
     */
    private fun generateMockP256dhKey(): String {
        // Валидный P-256 публичный ключ (65 байт: 0x04 + 32 байта x + 32 байта y)
        val validP256Key = byteArrayOf(
            0x04.toByte(), // Несжатый формат
            // x координата (32 байта)
            0x6b.toByte(), 0x17.toByte(), 0xd1.toByte(), 0xf2.toByte(), 0xe1.toByte(), 0x2c.toByte(), 0x42.toByte(), 0x47.toByte(), 
            0xf8.toByte(), 0xbc.toByte(), 0xe6.toByte(), 0xe5.toByte(), 0x63.toByte(), 0xa4.toByte(), 0x40.toByte(), 0xf2.toByte(),
            0x77.toByte(), 0x03.toByte(), 0x7d.toByte(), 0x81.toByte(), 0x2d.toByte(), 0xeb.toByte(), 0x33.toByte(), 0xa0.toByte(), 
            0xf4.toByte(), 0xa1.toByte(), 0x39.toByte(), 0x45.toByte(), 0xd8.toByte(), 0x98.toByte(), 0xc2.toByte(), 0x96.toByte(),
            // y координата (32 байта)
            0x4f.toByte(), 0xe3.toByte(), 0x42.toByte(), 0xe2.toByte(), 0xfe.toByte(), 0x1a.toByte(), 0x7f.toByte(), 0x9b.toByte(), 
            0x8e.toByte(), 0xe7.toByte(), 0xeb.toByte(), 0x4a.toByte(), 0x7c.toByte(), 0x0f.toByte(), 0x9e.toByte(), 0x16.toByte(),
            0x2b.toByte(), 0xce.toByte(), 0x33.toByte(), 0x57.toByte(), 0x6b.toByte(), 0x31.toByte(), 0x5e.toByte(), 0xce.toByte(), 
            0xcb.toByte(), 0xb6.toByte(), 0x40.toByte(), 0x68.toByte(), 0x37.toByte(), 0xbf.toByte(), 0x51.toByte(), 0xf5.toByte()
        )
        return android.util.Base64.encodeToString(validP256Key, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }
    
    /**
     * Генерация mock auth ключа (16 байт в base64)
     */
    private fun generateMockAuthKey(): String {
        // Генерируем случайные байты для auth ключа
        val bytes = ByteArray(16)
        for (i in bytes.indices) {
            bytes[i] = (i * 17 + 42).toByte() // Простая, но детерминированная генерация
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }
}

/**
 * BroadcastReceiver для обработки действий с уведомлений о статусе ConnectionService
 */
class ConnectionServiceActionReceiver : BroadcastReceiver() {
    private val TAG = "ConnectionServiceActionReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "📱 ConnectionServiceActionReceiver received action: $action")

        when (action) {
            "STOP_SERVICE" -> {
                Log.d(TAG, "🛑 Stopping ConnectionService from notification")
                val serviceIntent = Intent(context, ConnectionService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }
}

/**
 * BroadcastReceiver для обработки действий с уведомлений о звонках
 */
class CallReceiver : BroadcastReceiver() {
    private val TAG = "CallReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val callId = intent.getStringExtra("callId")
        val callerId = intent.getStringExtra("callerId")

        Log.d(TAG, "📞 CallReceiver received action: $action for callId: $callId")

        when (action) {
            "ACCEPT_CALL" -> {
                Log.d(TAG, "✅ Accepting call: $callId")
                // Получаем ConnectionService и принимаем звонок
                val serviceIntent = Intent(context, ConnectionService::class.java)
                context.bindService(serviceIntent, object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val binder = service as ConnectionService.ConnectionServiceBinder
                        val connectionService = binder.getService()
                        connectionService.acceptCallFromService(callId ?: "", callerId ?: "")
                        context.unbindService(this)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }, Context.BIND_AUTO_CREATE)
            }
            "REJECT_CALL" -> {
                Log.d(TAG, "❌ Rejecting call: $callId")
                // Получаем ConnectionService и отклоняем звонок
                val serviceIntent = Intent(context, ConnectionService::class.java)
                context.bindService(serviceIntent, object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val binder = service as ConnectionService.ConnectionServiceBinder
                        val connectionService = binder.getService()
                        connectionService.rejectCallFromService(callId ?: "", callerId ?: "")
                        context.unbindService(this)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }, Context.BIND_AUTO_CREATE)
            }
        }
    }
}