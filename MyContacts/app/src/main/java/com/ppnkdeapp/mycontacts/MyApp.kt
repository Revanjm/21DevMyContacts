package com.ppnkdeapp.mycontacts

//// CallManager больше не используется
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ppnkdeapp.mycontacts.call.CallActivity
// CallManager больше не используется
import com.ppnkdeapp.mycontacts.call.CallService
import com.ppnkdeapp.mycontacts.call.ConnectionService
import com.ppnkdeapp.mycontacts.call.SignalClient
import com.ppnkdeapp.mycontacts.call.WebRTCClient
import com.ppnkdeapp.mycontacts.call.ActualCall
import com.ppnkdeapp.mycontacts.network.NetworkManager
import com.tencent.mmkv.MMKV
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.webrtc.MediaStream
import com.google.firebase.messaging.FirebaseMessaging
import org.webrtc.PeerConnection
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner

class MyApp : Application() {

    private lateinit var mmkv: MMKV
    private val contactsJsonPath = "/storage/emulated/0/MyContacts/contacts/contacts.json"
    private var connectionTimeoutHandler = Handler(Looper.getMainLooper())
    private val CONNECTION_TIMEOUT_DELAY = 10000L // 10 секунд
    private var isConnectionTimeoutScheduled = false
    // CallManager больше не используется - удален
    
    // HTTP опрос входящих звонков
    private val incomingCallsPollingHandler = Handler(Looper.getMainLooper())
    private val INCOMING_CALLS_POLLING_INTERVAL = 5000L // 5 секунд
    private var isIncomingCallsPollingActive = false
    private val processedIncomingCalls = mutableSetOf<String>() // Храним обработанные callId
    
    // HTTP опрос статуса звонка
    private val callStatusPollingHandler = Handler(Looper.getMainLooper())
    private val CALL_STATUS_POLLING_INTERVAL = 2000L // 2 секунды
    private var isCallStatusPollingActive = false
    private var currentCallIdForStatusCheck: String? = null

    // ОБНОВЛЕННЫЕ ОБЪЯВЛЕНИЯ:
    private val signalClient = SignalClient()
    private val connectionExecutor = Executors.newSingleThreadExecutor()
    private val serverConnectionCallbacks = mutableListOf<(ServerConnectionState) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val SIGNALING_SERVER_URL = "https://e65ea583-8f5e-4d58-9f20-58e16b4d9ba5-00-182nvjnxjskc1.sisko.replit.dev"

        private const val CONTACTS_KEY = "contacts_data"
        private const val USER_ID_KEY = "user_id"
        private const val AUTH_TOKEN_KEY = "auth_token"
        private const val SERVER_CONNECTION_KEY = "server_connection_state"

        // Для доступа из Activity
        fun getInstance(context: android.content.Context): MyApp {
            return context.applicationContext as MyApp
        }
    }

    // LiveData для наблюдения за контактами
    private val _contactsLiveData = MutableLiveData<List<Contact>>()
    val contactsLiveData: LiveData<List<Contact>> get() = _contactsLiveData

    private val _myActiveContacts = MutableLiveData<List<Contact>>()
    val myActiveContacts: LiveData<List<Contact>> get() = _myActiveContacts

    // Callback для изменений списка активных контактов
    private val activeContactsChangeCallbacks = mutableListOf<(List<Contact>) -> Unit>()
    // LiveData для состояния сети
    private val _networkStateLiveData = MutableLiveData<Boolean>()
    val networkStateLiveData: LiveData<Boolean> get() = _networkStateLiveData

    // LiveData для состояния WebRTC
    private val _webRTCInitializedLiveData = MutableLiveData<Boolean>()
    val webRTCInitializedLiveData: LiveData<Boolean> get() = _webRTCInitializedLiveData

    // LiveData для состояния соединения с сервером
    private val _serverConnectionState = MutableLiveData<ServerConnectionState>()
    val serverConnectionState: LiveData<ServerConnectionState> get() = _serverConnectionState

    // LiveData для personal_id из root-контакта
    private val _personalId0 = MutableLiveData<String?>()
    val personalId0: LiveData<String?> get() = _personalId0

    // LiveData для хранения ActualCall
    private val _currentActualCall = MutableLiveData<ActualCall?>()
    val currentActualCall: LiveData<ActualCall?> get() = _currentActualCall

    // Переменная для отслеживания состояния подключения к серверу
    private var isConnectedToServer = false
    private var socket: Socket? = null
    private var connectionRetryCount = 0
    private val _rootContactId = MutableLiveData<String?>()
    val rootContactId: LiveData<String?> get() = _rootContactId
    // Менеджеры
    private lateinit var networkManager: NetworkManager
    private var connectionService: ConnectionService? = null
    private var isServiceBound = false
    
    // ServiceConnection для привязки к ConnectionService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ConnectionService.ConnectionServiceBinder
            connectionService = binder.getService()
            isServiceBound = true
            
            // Регистрируем callback'ы сразу после подключения
            registerConnectionServiceCallbacks()
            
            Log.d("MyApp", "✅ ConnectionService bound successfully")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            unregisterConnectionServiceCallbacks()
            connectionService = null
            isServiceBound = false
            Log.d("MyApp", "❌ ConnectionService unbound")
        }
    }

    // Callback для наблюдения за изменениями (альтернативный способ)
    private val contactsChangeCallbacks = mutableListOf<(List<Contact>) -> Unit>()
    private val serverConnectionChangeCallbacks = mutableListOf<(ServerConnectionState) -> Unit>()
    private val personalIdChangeCallbacks = mutableListOf<(String?) -> Unit>()
    private val _activeConnectionsIds = MutableLiveData<List<String>>()
    val activeConnectionsIds: LiveData<List<String>> get() = _activeConnectionsIds

    // Callback для изменений списка подключенных ID
    private val activeConnectionsChangeCallbacks = mutableListOf<(List<String>) -> Unit>()

    // Callback для изменений ActualCall
    private val actualCallChangeCallbacks = mutableListOf<(ActualCall?) -> Unit>()

    sealed class ServerConnectionState {
        object Disconnected : ServerConnectionState()
        object Connecting : ServerConnectionState()
        object Connected : ServerConnectionState()
        data class Error(val message: String) : ServerConnectionState()
    }

    private lateinit var webRTCClient: WebRTCClient
    private var isWebRTCInitialized = false
    private var deviceId: String = ""

    // WebRTCListener для обработки событий
    // В класс MyApp добавьте:

    private var currentCallActivity: CallActivity? = null
    private var pendingIncomingCall: Pair<String, String>? = null // callerId, callId
    private val handledCallIds = mutableSetOf<String>() // Защита от дублирования
    
    fun handleIncomingCall(fromUserId: String, callId: String) {
        Log.d("MyApp", "📞 Handling incoming call from: $fromUserId, callId: $callId")

        // Проверяем, не обрабатывали ли мы уже этот звонок
        if (handledCallIds.contains(callId)) {
            Log.w("MyApp", "⚠️ Call $callId already being handled, ignoring duplicate")
            return
        }
        
        handledCallIds.add(callId)
        
        // Ограничиваем размер Set (храним последние 100 звонков)
        if (handledCallIds.size > 100) {
            val oldestCallId = handledCallIds.first()
            handledCallIds.remove(oldestCallId)
        }

        // ✅ ИСПРАВЛЕНО: Используем существующее соединение вместо принудительного переподключения
//        Log.d("MyApp", "📞 Processing incoming call using existing connection...")
//                processIncomingCallAfterReconnect(fromUserId, callId)
    }



    // Метод для завершения звонка (вызывается из CallActivity)
    fun endCall(callId: String?, callerId: String?) {
        Log.d("MyApp", "📞 Ending call: $callId")

        // Удаляем callId из обработанных (можно принимать этот звонок снова)
        callId?.let { handledCallIds.remove(it) }
        Log.d("MyApp", "🧹 Removed callId from handledCallIds cache")
        
        // Очищаем ActualCall в MyApp
        clearCurrentActualCall()
        
        // Останавливаем сервис
        stopCallService()

        // Очищаем данные звонка
        clearCallData()

        // Останавливаем аудио сессию WebRTC
//        if (isWebRTCInitialized) {
//            try {
//                webRTCClient.stopAudioSession()
//            } catch (e: Exception) {
//                Log.e("MyApp", "Error stopping audio session: ${e.message}")
//            }
//        }
    }

    // Метод для принятия звонка (вызывается из CallActivity)
    fun acceptCall(callerId: String, callId: String) {
        Log.d("MyApp", "📞 Accepting call from: $callerId")

        if (isWebRTCInitialized) {
            webRTCClient.acceptIncomingCall(callId)
            // Обновляем уведомление сервиса
            startCallService(callId, callerId, false)
        }
    }
    // Метод для обработки входящих звонков
//    fun handleIncomingCall(fromUserId: String, callId: String) {
//        Log.d("MyApp", "📞 Handling incoming call from: $fromUserId, callId: $callId")
//
//        mainHandler.post {
//            // Сохраняем информацию о звонке
//            pendingIncomingCall = Pair(fromUserId, callId)
//
//            // Запускаем CallActivity
//            val intent = Intent(this, com.ppnkdeapp.mycontacts.call.CallActivity::class.java).apply {
//                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
//                putExtra("call_id", callId)
//                putExtra("caller_id", fromUserId)
//                putExtra("is_incoming", true)
//
//                // Получаем имя контакта
//                val contactName = getContactName(fromUserId)
//                putExtra("contact_name", contactName)
//            }
//
//            startActivity(intent)
//            Log.d("MyApp", "✅ CallActivity started for incoming call")
//        }
//    }

    // Метод для получения имени контакта
    fun getContactName(userId: String?): String {
        if (userId.isNullOrEmpty()) return "Неизвестный"

        val contacts = _contactsLiveData.value ?: emptyList()
        val contact = contacts.find { it.personal_id == userId }
        return contact?.Name ?: "Контакту"
    }

    // Метод для установки текущей CallActivity
    fun setCurrentCallActivity(activity: CallActivity?) {
        currentCallActivity = activity

        // Если есть ожидающий звонок и активность установлена, передаем данные
        if (activity != null && pendingIncomingCall != null) {
            val (callerId, callId) = pendingIncomingCall!!
            pendingIncomingCall = null
        }
    }

    // Метод для очистки информации о звонке
// Обновите метод очистки данных звонка
    fun clearCallData() {
        pendingIncomingCall = null
        currentCallActivity = null
        stopCallService() // Добавьте эту строку
        stopCallStatusPolling() // Останавливаем опрос статуса
    }

    // Метод для инициирования исходящего звонка
//    fun initiateOutgoingCall(targetUserId: String, contactName: String) {
//        mainHandler.post {
//            val callId = "call_${System.currentTimeMillis()}"
//
//            val intent = Intent(this, com.ppnkdeapp.mycontacts.call.CallActivity::class.java).apply {
//                flags = Intent.FLAG_ACTIVITY_NEW_TASK
//                putExtra("call_id", callId)
//                putExtra("caller_id", targetUserId)
//                putExtra("is_incoming", false)
//                putExtra("contact_name", contactName)
//            }
//
//            startActivity(intent)
//            Log.d("MyApp", "✅ CallActivity started for outgoing call to: $targetUserId")
//        }
//    }
    // Замените весь webRTCListener на:
    private val webRTCListener = object : WebRTCClient.WebRTCListener {
        private val activityListeners = mutableListOf<WebRTCClient.WebRTCListener>()

        fun addActivityListener(listener: WebRTCClient.WebRTCListener) {
            if (!activityListeners.contains(listener)) {
                activityListeners.add(listener)
            }
        }

        fun removeActivityListener(listener: WebRTCClient.WebRTCListener) {
            activityListeners.remove(listener)
        }

        private fun notifyAllActivityListeners(block: (WebRTCClient.WebRTCListener) -> Unit) {
            mainHandler.post {
                activityListeners.forEach { listener ->
                    try {
                        block(listener)
                    } catch (e: Exception) {
                        Log.e("MyApp", "Error notifying activity listener: ${e.message}")
                    }
                }
            }
        }

        override fun onIncomingCall(callId: String, fromUserId: String) {

            // ВРЕМЕННО ОТКЛЮЧЕНО: Звонки обрабатываются только через HTTP
            // handleIncomingCall(fromUserId, callId)
            Log.d("WebRTC", "⚠️ Socket.IO incoming call handling DISABLED - using HTTP only")
        }
//        override fun onIncomingCall(fromUserId: String, callId: String) {
//            Log.d("MyApp", "📞 Incoming call in MyApp: $fromUserId, callId: $callId")
//
//            // Обрабатываем входящий звонок через MyApp
//            handleIncomingCall(fromUserId, callId)
//
//            // Уведомляем activity listeners
//            notifyAllActivityListeners { it.onIncomingCall(fromUserId, callId) }
//        }
////        override fun onIncomingCall(fromUserId: String, callId: String) {
////            Log.d("MyApp", "📞 Incoming call in MyApp: $fromUserId, callId: $callId")
////            notifyAllActivityListeners { it.onIncomingCall(fromUserId, callId) }
////        }

        // Реализация всех методов из WebRTCClient.WebRTCListener
        override fun onCallInitiated(callId: String) {
            notifyAllActivityListeners { it.onCallInitiated(callId) }
        }

        override fun onCallAccepted(callId: String) {
            Log.d("MyApp", "✅ Call accepted: $callId")
            notifyAllActivityListeners { it.onCallAccepted(callId) }
        }

        override fun onCallRejected(callId: String) {
            Log.d("MyApp", "❌ Call rejected: $callId")

            // Останавливаем опрос статуса
            stopCallStatusPolling()

            // Очищаем данные звонка
            clearCallData()

            // Останавливаем сервис
            stopCallService()

            notifyAllActivityListeners { it.onCallRejected(callId) }
        }

        override fun onCallEnded(callId: String) {
            Log.d("MyApp", "📞 Call ended: $callId")

            // Останавливаем сервис при завершении звонка
            mainHandler.post {
                stopCallService()
                clearCallData()
            }

            notifyAllActivityListeners { it.onCallEnded(callId) }
        }

        override fun onCallFailed(callId: String, error: String) {
            Log.d("MyApp", "📞 Call failed: $callId - $error")

            // Останавливаем сервис при ошибке звонка
            mainHandler.post {
                stopCallService()
                clearCallData()
            }

            notifyAllActivityListeners { it.onCallFailed(callId, error) }
        }

        override fun onWebRTCConnected() {
            notifyAllActivityListeners { it.onWebRTCConnected() }
        }

        override fun onWebRTCDisconnected() {
            notifyAllActivityListeners { it.onWebRTCDisconnected() }
        }

        override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
            notifyAllActivityListeners { it.onIceConnectionStateChanged(state) }
        }
    }
    override fun onCreate() {
        super.onCreate()
        initializeMMKV()

        // Проверяем и создаем папку при запуске приложения
        ensureContactsDirectoryExists()

        // Инициализируем пустым списком при старте
        _contactsLiveData.value = emptyList()
        _networkStateLiveData.value = false
        _webRTCInitializedLiveData.value = false
        _serverConnectionState.value = ServerConnectionState.Disconnected
        _personalId0.value = null
        _activeConnectionsIds.value = emptyList()
        _currentActualCall.value = null

        // Затем загружаем контакты
        loadContactsFromStorage()
        // CallManager больше не используется
        
        // 🔥 НОВОЕ: Автоматический запуск ConnectionService при старте приложения
        // С небольшей задержкой, чтобы контакты успели загрузиться
        // 🔥 НОВОЕ: Инициализация Firebase Cloud Messaging
//        initializeFirebaseMessaging()

        // 🔥 НОВОЕ: Регистрируем ProcessLifecycleOwner для отслеживания закрытия приложения
        registerProcessLifecycleObserver()
        
        // ❌ УБРАНО: Автоматический запуск ConnectionService из MyApp
        // Теперь ConnectionService запускается из ContactsListActivity.onCreate()
        // чтобы избежать ошибки ForegroundServiceStartNotAllowedException в Android 12+
//        Log.d("MyApp", "ℹ️ ConnectionService will be started from ContactsListActivity")
        
    }

    // Метод для получения идентификатора устройства
    private fun getDeviceIdentifier(): String {
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "android_${System.currentTimeMillis()}"
    }

    private fun ensureContactsDirectoryExists() {
        try {
            val contactsDir = File("/storage/emulated/0/MyContacts/contacts")
            if (!contactsDir.exists()) {
                val created = contactsDir.mkdirs()
                if (created) {
                    Log.d("MyApp", "✅ Папка контактов создана при запуске: ${contactsDir.absolutePath}")

                    // Создаем файл contacts.json
                    val contactsFile = File(contactsDir, "contacts.json")
                    val uniqueId = generateUniqueId()
                    val contactsJson = """
                {
                   "contacts": [
                      {
                         "Name": "No name",
                         "email": "No email",
                         "personal_id": "$uniqueId",
                         "group_id": 1,
                         "root_contact": true,
                         "list_id": 0
                      }
                   ]
                }
                """.trimIndent()

                    contactsFile.writeText(contactsJson)
                    Log.d("MyApp", "✅ Файл contacts.json создан с уникальным ID: $uniqueId")

                } else {
                    Log.e("MyApp", "❌ Не удалось создать папку контактов при запуске")
                }
            }
        } catch (e: Exception) {
            Log.e("MyApp", "❌ Ошибка при создании папки контактов: ${e.message}")
        }
    }

    private fun generateUniqueId(): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..34)
            .map { allowedChars.random() }
            .joinToString("")
    }
//    override fun onCreate() {
//        super.onCreate()
//        initializeMMKV()
//
//        // Инициализируем пустым списком при старте
//        _contactsLiveData.value = emptyList()
//        _networkStateLiveData.value = false
//        _webRTCInitializedLiveData.value = false
//        _serverConnectionState.value = ServerConnectionState.Disconnected
//        _personalId0.value = null
//        _activeConnectionsIds.value = emptyList() // Инициализируем пустым списком
//
//        // Затем загружаем контакты
//        loadContactsFromStorage()
//        // CallManager больше не используется
//
//        // Автоподключение к сети если есть сохраненные данные
//    }

    private fun initializeMMKV() {
        try {
            MMKV.initialize(this)
            mmkv = MMKV.defaultMMKV()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchActiveConnectionsFromServer() {
        connectionExecutor.execute {
            try {
                val url = URL("$SIGNALING_SERVER_URL/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 1000
                connection.readTimeout = 10000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    val usersArray = jsonObject.getJSONArray("connectedUsers")

                    val usersList = mutableListOf<String>()
                    for (i in 0 until usersArray.length()) {
                        usersList.add(usersArray.getString(i))
                    }

                    // Обновляем LiveData и уведомляем подписчиков
                    updateActiveConnections(usersList)
//                    Log.d("MyApp", "✅ Получен список подключенных ID: ${usersList.size} пользователей")
                } else {
//                    Log.e("MyApp", "❌ Ошибка при запросе списка подключенных: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
//                Log.e("MyApp", "❌ Ошибка при получении списка подключенных: ${e.message}")
                // В случае ошибки обновляем пустым списком
                updateActiveConnections(emptyList())
            }
        }
    }

    // Запуск опроса статуса звонка
    private fun startCallStatusPolling(callId: String) {
        if (isCallStatusPollingActive) {
            return
        }
        
        currentCallIdForStatusCheck = callId
        isCallStatusPollingActive = true
        
        val statusCheckRunnable = object : Runnable {
            override fun run() {
                if (isCallStatusPollingActive && currentCallIdForStatusCheck != null) {
                    checkCallStatus(currentCallIdForStatusCheck!!)
                    callStatusPollingHandler.postDelayed(this, CALL_STATUS_POLLING_INTERVAL)
                }
            }
        }
        
        callStatusPollingHandler.post(statusCheckRunnable)
        Log.d("MyApp", "✅ Started call status polling for callId: $callId")
    }
    
    // Остановка опроса статуса звонка
    private fun stopCallStatusPolling() {
        isCallStatusPollingActive = false
        currentCallIdForStatusCheck = null
        callStatusPollingHandler.removeCallbacksAndMessages(null)
        Log.d("MyApp", "🛑 Stopped call status polling")
    }

    // Проверка статуса звонка через HTTP
    private fun checkCallStatus(callId: String) {
        connectionExecutor.execute {
            try {
                val url = URL("$SIGNALING_SERVER_URL/call-status/$callId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 1000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    
                    val currentStatus = jsonObject.optString("currentStatus", "")
                    val isActive = jsonObject.optBoolean("isActive", true)
                    
                    Log.d("MyApp", "📞 Call status check: callId=$callId, status=$currentStatus, isActive=$isActive")
                    
                    // Если звонок отклонен или завершен, очищаем данные
                    if (currentStatus == "rejected" || currentStatus == "ended" || !isActive) {
                        mainHandler.post {
                            Log.d("MyApp", "❌ Call was rejected or ended, cleaning up...")
                            stopCallStatusPolling() // Останавливаем опрос
                            clearCallData()
                            // Также очищаем WebRTC ресурсы
                            if (isWebRTCInitialized()) {
                                webRTCClient.endCall()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error checking call status: ${e.message}")
            }
        }
    }

    // HTTP опрос входящих звонков (параллельно с Socket.IO)
    private fun fetchIncomingCallsFromServer() {
        val personalId = getPersonalId0()
        if (personalId.isNullOrBlank()) {
            return
        }

        connectionExecutor.execute {
            try {
                val url = URL("$SIGNALING_SERVER_URL/incoming-calls/$personalId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(response)
                    
                    if (jsonObject.getString("status") == "Success") {
                        val incomingCallsArray = jsonObject.getJSONArray("incomingCalls")
                        
                        // Обрабатываем каждый входящий звонок
                        for (i in 0 until incomingCallsArray.length()) {
                            val callData = incomingCallsArray.getJSONObject(i)
                            val callId = callData.getString("callId")
                            val callerId = callData.getString("callerId")
                            val contactName = callData.optString("contactName", "")
                            val status = callData.optString("status", "")
                            val createdAt = callData.optString("createdAt", "")
                            val isOfflineCall = callData.optBoolean("isOfflineCall", false)
                            
                            // Проверяем, не обрабатывали ли мы уже этот звонок
                            if (!processedIncomingCalls.contains(callId)) {
                                processedIncomingCalls.add(callId)
                                
                                val callInfo = buildString {
                                    append("📞 HTTP: New incoming call detected\n")
                                    append("   From: $callerId")
                                    if (contactName.isNotEmpty()) append(" ($contactName)")
                                    append("\n")
                                    append("   CallId: $callId\n")
                                    append("   Status: $status")
                                    if (isOfflineCall) append(" (OFFLINE)")
                                    append("\n")
                                    if (createdAt.isNotEmpty()) append("   Created: $createdAt")
                                }
                                Log.d("MyApp", callInfo)
                                
                                // Обрабатываем звонок через существующий механизм MyApp
                                mainHandler.post {
                                    try {
                                        Log.d("MyApp", "📞 HTTP: Processing incoming call from $callerId")
                                        handleIncomingCall(callerId, callId)
                                    } catch (e: Exception) {
                                        Log.e("MyApp", "❌ Error processing HTTP incoming call: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error fetching incoming calls via HTTP: ${e.message}")
            }
        }
    }

    // Runnable для периодического опроса
    private val incomingCallsPollingRunnable = object : Runnable {
        override fun run() {
            if (isIncomingCallsPollingActive && isConnectedToServer) {
                fetchIncomingCallsFromServer()
                incomingCallsPollingHandler.postDelayed(this, INCOMING_CALLS_POLLING_INTERVAL)
            }
        }
    }

    // Запуск HTTP опроса
    private fun startIncomingCallsPolling() {
        if (!isIncomingCallsPollingActive) {
            isIncomingCallsPollingActive = true
            Log.d("MyApp", "🔄 Starting HTTP polling for incoming calls")
            incomingCallsPollingHandler.post(incomingCallsPollingRunnable)
        }
    }

    // Остановка HTTP опроса
    private fun stopIncomingCallsPolling() {
        if (isIncomingCallsPollingActive) {
            isIncomingCallsPollingActive = false
            Log.d("MyApp", "⏹️ Stopping HTTP polling for incoming calls")
            incomingCallsPollingHandler.removeCallbacks(incomingCallsPollingRunnable)
            
            // Очищаем обработанные звонки и ограничиваем размер Set (храним последние 100)
            processedIncomingCalls.clear()
            Log.d("MyApp", "🧹 Cleared processed incoming calls cache")
        }
    }

    fun getWebRTCClient(): WebRTCClient {
        if (!isWebRTCInitialized) {
            throw IllegalStateException("WebRTCClient not initialized")
        }
        return webRTCClient
    }

    fun isWebRTCInitialized(): Boolean = isWebRTCInitialized

    // Для подписки на события WebRTC (опционально)
    private val webRTCEventCallbacks = mutableListOf<(String, Any?) -> Unit>()

    fun subscribeToWebRTCEvents(callback: (String, Any?) -> Unit) {
        webRTCEventCallbacks.add(callback)
    }

    fun unsubscribeFromWebRTCEvents(callback: (String, Any?) -> Unit) {
        webRTCEventCallbacks.remove(callback)
    }
    fun startCallService(callId: String, callerId: String, isIncoming: Boolean) {
        val intent = Intent(this, CallService::class.java).apply {
            action = if (isIncoming) "INCOMING_CALL" else "OUTGOING_CALL"
            putExtra("call_id", callId)
            putExtra("caller_id", callerId)
            putExtra("is_incoming", isIncoming)
            
            // Получаем имя контакта для уведомления
            val contactName = getContactName(callerId)
            if (isIncoming) {
                putExtra("caller_name", contactName)
            } else {
                putExtra("target_name", contactName)
                putExtra("target_user_id", callerId)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Log.d("MyApp", "📞 CallService started for ${if (isIncoming) "incoming" else "outgoing"} call")
    }

    // Метод для остановки сервиса звонка
    fun stopCallService() {
        val intent = Intent(this, CallService::class.java)
        stopService(intent)
        Log.d("MyApp", "📞 CallService stopped")
    }
    private val rootContactIdChangeCallbacks = mutableListOf<(String?) -> Unit>()

    fun subscribeToRootContactIdChanges(callback: (String?) -> Unit) {
        rootContactIdChangeCallbacks.add(callback)
        callback(_rootContactId.value)
    }

    fun unsubscribeFromRootContactIdChanges(callback: (String?) -> Unit) {
        rootContactIdChangeCallbacks.remove(callback)
    }

    private fun notifyRootContactIdChanged(rootContactId: String?) {
        rootContactIdChangeCallbacks.forEach { callback ->
            try {
                callback(rootContactId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // CallManager больше не используется - удален

    private fun updateActiveContacts(activeConnectionIds: List<String>) {
        val currentContacts = _contactsLiveData.value ?: emptyList()

        // Фильтруем контакты, оставляя только те, чьи personal_id есть в списке активных подключений
        // И исключаем root-контакт
        val activeContacts = currentContacts.filter { contact ->
            contact.personal_id != null &&
                    activeConnectionIds.contains(contact.personal_id) &&
                    contact.root_contact != true
        }

        mainHandler.post {
            _myActiveContacts.value = activeContacts
            notifyActiveContactsChanged(activeContacts)

            // Логируем для отладки
            if (activeContacts.isNotEmpty()) {
//                Log.d("MyApp", "🎯 Активные контакты: ${activeContacts.size} шт.")
                activeContacts.forEach { contact ->
//                    Log.d("MyApp", "   👤 ${contact.Name} (ID: ${contact.personal_id})")
                }
            } else {
//                Log.d("MyApp", "🎯 Нет активных контактов")
            }
        }
    }

    private fun notifyActiveContactsChanged(activeContacts: List<Contact>) {
        activeContactsChangeCallbacks.forEach { callback ->
            try {
                callback(activeContacts)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun subscribeToActiveContactsChanges(callback: (List<Contact>) -> Unit) {
        activeContactsChangeCallbacks.add(callback)
        // Immediately call with current value
        callback(_myActiveContacts.value ?: emptyList())
    }

    // НОВЫЙ МЕТОД: Отписка от изменений активных контактов
    fun unsubscribeFromActiveContactsChanges(callback: (List<Contact>) -> Unit) {
        activeContactsChangeCallbacks.remove(callback)
    }

    // НОВЫЙ МЕТОД: Получение текущего списка активных контактов
    fun getCurrentActiveContacts(): List<Contact> {
        return _myActiveContacts.value ?: emptyList()
    }

    // 🔥 НОВЫЕ МЕТОДЫ ДЛЯ FCM
    fun registerFCMToken(token: String) {
        Log.d("MyApp", "🔑 Registering FCM token: $token")
        
        // Отправляем токен на сервер
        connectionExecutor.execute {
            try {
                val url = URL("$SIGNALING_SERVER_URL/api/push/subscribe")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val json = JSONObject().apply {
                    put("userId", getPersonalId0())
                    put("subscription", JSONObject().apply {
                        put("endpoint", "https://fcm.googleapis.com/fcm/send/$token")
                        put("keys", JSONObject().apply {
                            put("p256dh", "dummy_p256dh_key")
                            put("auth", "dummy_auth_key")
                        })
                    })
                }

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200 || responseCode == 201) {
                    Log.d("MyApp", "✅ FCM token registered successfully")
                } else {
                    Log.e("MyApp", "❌ Failed to register FCM token: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error registering FCM token: ${e.message}")
            }
        }
    }

    fun handleCallStatusUpdate(callId: String, status: String) {
        Log.d("MyApp", "🔄 Handling call status update: $callId -> $status")
        
        // Обрабатываем обновление статуса звонка
        when (status) {
            "accepted" -> {
                Log.d("MyApp", "✅ Call accepted: $callId")
                // Обновляем UI
            }
            "rejected" -> {
                Log.d("MyApp", "❌ Call rejected: $callId")
                // Обновляем UI
            }
            "ended" -> {
                Log.d("MyApp", "📞 Call ended: $callId")
                // Обновляем UI
            }
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Инициализация Firebase Cloud Messaging
//    private fun initializeFirebaseMessaging() {
//        try {
//            Log.d("MyApp", "🔥 Initializing Firebase Cloud Messaging...")
//
//            // Получаем FCM токен
//            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
//                if (task.isSuccessful) {
//                    val token = task.result
//                    Log.d("MyApp", "🔑 FCM token obtained: $token")
//
//                    // Регистрируем токен на сервере
//                    registerFCMToken(token)
//                } else {
//                    Log.e("MyApp", "❌ Failed to get FCM token: ${task.exception?.message}")
//                }
//            }
//
//            Log.d("MyApp", "✅ Firebase Cloud Messaging initialized")
//        } catch (e: Exception) {
//            Log.e("MyApp", "❌ Error initializing Firebase Messaging: ${e.message}")
//        }
//    }

    fun updateActiveConnections(connections: List<String>) {
        mainHandler.post {
            val previousConnections = _activeConnectionsIds.value ?: emptyList()
            _activeConnectionsIds.value = connections

            // Обновляем активные контакты
            updateActiveContacts(connections)

            // 🔥 НОВОЕ: Обновляем состояние подключения к серверу на основе полученного списка
            val hasActiveConnections = connections.isNotEmpty()
            if (hasActiveConnections) {
                // Если получен не пустой список - считаем, что подключены к серверу
                if (_serverConnectionState.value !is ServerConnectionState.Connected) {
                    updateServerConnectionState(ServerConnectionState.Connected)
                    Log.d("MyApp", "✅ Server connection state: Connected (got ${connections.size} active connections)")
                }
            } else {
                // Если список пустой - считаем, что отключены от сервера
                if (_serverConnectionState.value !is ServerConnectionState.Disconnected) {
                    updateServerConnectionState(ServerConnectionState.Disconnected)
                    Log.d("MyApp", "❌ Server connection state: Disconnected (empty connection list)")
                }
            }

            notifyActiveConnectionsChanged(connections)

            // Логируем для отладки
            if (connections.isNotEmpty()) {
                Log.d("MyApp", "📊 Активные подключения: ${connections.joinToString(", ")}")
            } else {
                Log.d("MyApp", "📊 Нет активных подключений")
            }
        }
    }

    private fun notifyActiveConnectionsChanged(connections: List<String>) {
        activeConnectionsChangeCallbacks.forEach { callback ->
            try {
                callback(connections)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun subscribeToActiveConnectionsChanges(callback: (List<String>) -> Unit) {
        activeConnectionsChangeCallbacks.add(callback)
        callback(_activeConnectionsIds.value ?: emptyList())
    }

    fun unsubscribeFromActiveConnectionsChanges(callback: (List<String>) -> Unit) {
        activeConnectionsChangeCallbacks.remove(callback)
    }

    // Методы для управления ActualCall
    fun setCurrentActualCall(actualCall: ActualCall?) {
        _currentActualCall.value = actualCall
        notifyActualCallChanged(actualCall)
    }

    fun getCurrentActualCall(): ActualCall? = _currentActualCall.value

    fun clearCurrentActualCall() {
        setCurrentActualCall(null)
    }

    // Методы для подписки на изменения ActualCall
    fun subscribeToActualCallChanges(callback: (ActualCall?) -> Unit) {
        actualCallChangeCallbacks.add(callback)
        callback(_currentActualCall.value)
    }

    fun unsubscribeFromActualCallChanges(callback: (ActualCall?) -> Unit) {
        actualCallChangeCallbacks.remove(callback)
    }

    private fun notifyActualCallChanged(actualCall: ActualCall?) {
        actualCallChangeCallbacks.forEach { callback ->
            try {
                callback(actualCall)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Инициация звонка к указанному контакту
     * @param recipientId ID контакта, которому звоним
     */
    fun initiateCall(recipientId: String) {
        Log.d("MyApp", "📞 Initiating call to: $recipientId")
        
        // Очищаем текущий ActualCall
        clearCurrentActualCall()
        
        // Получаем текущий personalId
        val callerId = getPersonalId0()
        if (callerId.isNullOrBlank()) {
            Log.e("MyApp", "❌ Cannot initiate call: personalId is null or blank")
            return
        }
        
        // Генерируем уникальный callId
        val callId = "call_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
        
        // Создаем ActualCall объект
        val actualCall = ActualCall(
            callId = callId,
            callerId = callerId,
            recipientId = recipientId,
            status = "null",
            step = "request_call",
            createdAt = System.currentTimeMillis(),
            offerSdp = null,
            answerSdp = null
        )
        
        // Устанавливаем ActualCall в MyApp
        setCurrentActualCall(actualCall)
        
        Log.d("MyApp", "✅ ActualCall created and set: $callId")
    }

    // НОВЫЙ МЕТОД: Получение SignalClient для использования в других частях приложения
    fun getSignalClient(): SignalClient {
        return signalClient
    }
    private fun loadContactsFromStorage() {
        try {
            val contactsFile = File(contactsJsonPath)

            // Создаем папку если она не существует
            val contactsDir = contactsFile.parentFile
            if (contactsDir != null && !contactsDir.exists()) {
                val created = contactsDir.mkdirs()
                if (created) {
                    Log.d("MyApp", "✅ Папка создана: ${contactsDir.absolutePath}")

                    // Создаем пустой файл контактов
                    val emptyContacts = ContactsResponse(emptyList())
                    val jsonString = Json.encodeToString(emptyContacts)
                    contactsFile.writeText(jsonString)

                    Log.d("MyApp", "✅ Пустой файл контактов создан")
                } else {
                    Log.e("MyApp", "❌ Не удалось создать папку: ${contactsDir.absolutePath}")
                }
            }

            if (contactsFile.exists()) {
                val jsonString = contactsFile.readText()
                val contactsResponse = Json.decodeFromString<ContactsResponse>(jsonString)
                val sortedContacts = sortContactsAlphabeticallyWithRootFirst(contactsResponse.contacts)

                // Обновляем LiveData с отсортированными контактами
                _contactsLiveData.postValue(sortedContacts)

                // ⬇️ УБИРАЕМ СОХРАНЕНИЕ В MMKV
                // mmkv.encode(CONTACTS_KEY, jsonString)

                // Уведомляем подписчиков callback
                notifyContactsChanged(sortedContacts)
                // ИЗВЛЕКАЕМ rootContactId ПРИ ЗАГРУЗКЕ КОНТАКТОВ
                extractPersonalIdFromRootContact(sortedContacts)

                Log.d("MyApp", "Successfully loaded ${sortedContacts.size} contacts from file")
            } else {
                Log.d("MyApp", "Contacts file not found at: $contactsJsonPath")
                // ⬇️ УБИРАЕМ ЗАГРУЗКУ ИЗ MMKV
                // loadContactsFromMMKV()

                // Вместо этого создаем пустой список
                _contactsLiveData.postValue(emptyList())
                Log.d("MyApp", "Created empty contacts list")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MyApp", "Error loading contacts from file: ${e.message}")
            // ⬇️ УБИРАЕМ FALLBACK НА MMKV
            // loadContactsFromMMKV()

            // В случае ошибки создаем пустой список
            _contactsLiveData.postValue(emptyList())
        }
    }
//    private fun loadContactsFromStorage() {
//        try {
//            val contactsFile = File(contactsJsonPath)
//
//            // Создаем папку если она не существует
//            val contactsDir = contactsFile.parentFile
//            if (contactsDir != null && !contactsDir.exists()) {
//                val created = contactsDir.mkdirs()
//                if (created) {
//                    Log.d("MyApp", "✅ Папка создана: ${contactsDir.absolutePath}")
//
//                    // Создаем пустой файл контактов
//                    val emptyContacts = ContactsResponse(emptyList())
//                    val jsonString = Json.encodeToString(emptyContacts)
//                    contactsFile.writeText(jsonString)
//
//                    Log.d("MyApp", "✅ Пустой файл контактов создан")
//                } else {
//                    Log.e("MyApp", "❌ Не удалось создать папку: ${contactsDir.absolutePath}")
//                }
//            }
//
//            if (contactsFile.exists()) {
//                val jsonString = contactsFile.readText()
//                val contactsResponse = Json.decodeFromString<ContactsResponse>(jsonString)
//                val sortedContacts = sortContactsAlphabeticallyWithRootFirst(contactsResponse.contacts)
//
//                // Обновляем LiveData с отсортированными контактами
//                _contactsLiveData.postValue(sortedContacts)
//
//                // Сохраняем в MMKV для будущего использования
//                mmkv.encode(CONTACTS_KEY, jsonString)
//
//                // Уведомляем подписчиков callback
//                notifyContactsChanged(sortedContacts)
//                // ИЗВЛЕКАЕМ rootContactId ПРИ ЗАГРУЗКЕ КОНТАКТОВ
//                extractPersonalIdFromRootContact(sortedContacts)
//
//                Log.d("MyApp", "Successfully loaded ${sortedContacts.size} contacts from file")
//            } else {
//                Log.d("MyApp", "Contacts file not found at: $contactsJsonPath")
//                // Пытаемся загрузить из MMKV если файл не существует
//                loadContactsFromMMKV()
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            Log.e("MyApp", "Error loading contacts from file: ${e.message}")
//            // Fallback to MMKV
//            loadContactsFromMMKV()
//        }
//    }
//    private fun loadContactsFromStorage() {
//        try {
//            val contactsFile = File(contactsJsonPath)
//            if (contactsFile.exists()) {
//                val jsonString = contactsFile.readText()
//                val contactsResponse = Json.decodeFromString<ContactsResponse>(jsonString)
//                val sortedContacts = sortContactsAlphabeticallyWithRootFirst(contactsResponse.contacts)
//
//                // Обновляем LiveData с отсортированными контактами
//                _contactsLiveData.postValue(sortedContacts)
//
//                // Сохраняем в MMKV для будущего использования
//                mmkv.encode(CONTACTS_KEY, jsonString)
//
//                // Уведомляем подписчиков callback
//                notifyContactsChanged(sortedContacts)
//                // ИЗВЛЕКАЕМ rootContactId ПРИ ЗАГРУЗКЕ КОНТАКТОВ
//                extractPersonalIdFromRootContact(sortedContacts)
//
//                Log.d("MyApp", "Successfully loaded ${sortedContacts.size} contacts from file")
//            } else {
//                Log.d("MyApp", "Contacts file not found at: $contactsJsonPath")
//                // Пытаемся загрузить из MMKV если файл не существует
//                loadContactsFromMMKV()
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//            Log.e("MyApp", "Error loading contacts from file: ${e.message}")
//            // Fallback to MMKV
//            loadContactsFromMMKV()
//        }
//    }

    fun deleteContactByListId(contactToDelete: Contact) {
        val currentContacts = _contactsLiveData.value ?: emptyList()

        Log.d("MyApp", "=== DELETE CONTACT BY LIST_ID ===")
        Log.d("MyApp", "Deleting contact: ${contactToDelete.Name} with list_id: ${contactToDelete.list_id}")
        Log.d("MyApp", "Current contacts count: ${currentContacts.size}")

        if (contactToDelete.list_id == null) {
            Log.e("MyApp", "❌ Cannot delete contact: list_id is null")
            return
        }

        val updatedContacts = currentContacts.filter {
            it.list_id != contactToDelete.list_id
        }

        if (updatedContacts.size == currentContacts.size) {
            Log.e("MyApp", "❌ Contact with list_id ${contactToDelete.list_id} not found for deletion")
            return
        }

        updateContacts(updatedContacts)
        saveContactsToJsonFile(updatedContacts)

        Log.d("MyApp", "✅ Contact with list_id ${contactToDelete.list_id} deleted successfully")
        Log.d("MyApp", "Total contacts now: ${updatedContacts.size}")
    }

    fun deleteContactByListIdOnly(listId: Int?) {
        val currentContacts = _contactsLiveData.value ?: emptyList()

        Log.d("MyApp", "=== DELETE CONTACT BY LIST_ID ONLY ===")
        Log.d("MyApp", "Deleting contact with list_id: $listId")
        Log.d("MyApp", "Current contacts count: ${currentContacts.size}")

        if (listId == null) {
            Log.e("MyApp", "❌ Cannot delete contact: list_id is null")
            return
        }

        val updatedContacts = currentContacts.filter { it.list_id != listId }

        if (updatedContacts.size == currentContacts.size) {
            Log.e("MyApp", "❌ Contact with list_id $listId not found")
            return
        }

        updateContacts(updatedContacts)
        saveContactsToJsonFile(updatedContacts)

        Log.d("MyApp", "✅ Contact with list_id $listId deleted successfully")
        Log.d("MyApp", "Total contacts now: ${updatedContacts.size}")
    }

    fun updateContactByListId(updatedContact: Contact) {
        val currentContacts = _contactsLiveData.value ?: emptyList()

        if (updatedContact.list_id == null) {
            val updatedContacts = currentContacts + updatedContact
            updateContacts(updatedContacts)
            saveContactsToJsonFile(updatedContacts)
            Log.d("MyApp", "New contact added (no list_id): ${updatedContact.Name}")
            Log.d("MyApp", "Total contacts now: ${updatedContacts.size}")
            return
        }

        val existingContactIndex = currentContacts.indexOfFirst {
            it.list_id == updatedContact.list_id
        }

        val updatedContacts = if (existingContactIndex != -1) {
            currentContacts.toMutableList().apply {
                this[existingContactIndex] = updatedContact
            }
        } else {
            currentContacts + updatedContact
        }

        updateContacts(updatedContacts)
        saveContactsToJsonFile(updatedContacts)

        if (existingContactIndex != -1) {
            Log.d("MyApp", "Contact with list_id ${updatedContact.list_id} updated successfully")
        } else {
            Log.d("MyApp", "New contact with list_id ${updatedContact.list_id} added successfully")
            Log.d("MyApp", "Total contacts now: ${updatedContacts.size}")
        }
    }

    private fun loadContactsFromMMKV() {
        try {
            val savedJson = mmkv.decodeString(CONTACTS_KEY)
            if (!savedJson.isNullOrEmpty()) {
                val contactsResponse = Json.decodeFromString<ContactsResponse>(savedJson)
                val sortedContacts = sortContactsAlphabeticallyWithRootFirst(contactsResponse.contacts)
                _contactsLiveData.postValue(sortedContacts)
                notifyContactsChanged(sortedContacts)
                // ИЗВЛЕКАЕМ rootContactId ПРИ ЗАГРУЗКЕ КОНТАКТОВ
                extractPersonalIdFromRootContact(sortedContacts)

                Log.d("MyApp", "Successfully loaded ${sortedContacts.size} contacts from MMKV")
            } else {
                Log.d("MyApp", "No contacts found in MMKV")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MyApp", "Error loading contacts from MMKV: ${e.message}")
        }
    }

    fun refreshContacts() {
        loadContactsFromStorage()
    }

    fun subscribeToContactsChanges(callback: (List<Contact>) -> Unit) {
        contactsChangeCallbacks.add(callback)
        callback(_contactsLiveData.value ?: emptyList())
    }

    fun unsubscribeFromContactsChanges(callback: (List<Contact>) -> Unit) {
        contactsChangeCallbacks.remove(callback)
    }

    fun subscribeToServerConnectionChanges(callback: (ServerConnectionState) -> Unit) {
        serverConnectionChangeCallbacks.add(callback)
        callback(_serverConnectionState.value ?: ServerConnectionState.Disconnected)
    }

    fun unsubscribeFromServerConnectionChanges(callback: (ServerConnectionState) -> Unit) {
        serverConnectionChangeCallbacks.remove(callback)
    }

    fun subscribeToPersonalIdChanges(callback: (String?) -> Unit) {
        personalIdChangeCallbacks.add(callback)
        callback(_personalId0.value)
    }

    fun unsubscribeFromPersonalIdChanges(callback: (String?) -> Unit) {
        personalIdChangeCallbacks.remove(callback)
    }

    private fun notifyContactsChanged(contacts: List<Contact>) {
        contactsChangeCallbacks.forEach { callback ->
            try {
                callback(contacts)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Извлекаем personal_id из контакта с root_contact = true
        extractPersonalIdFromRootContact(contacts)
    }

    private fun extractPersonalIdFromRootContact(contacts: List<Contact>) {
        try {
            val rootContact = contacts.find { it.root_contact == true }
            if (rootContact != null && !rootContact.personal_id.isNullOrBlank()) {
                val normalizedId = rootContact.personal_id!!.trim()
                _personalId0.postValue(normalizedId)
                _rootContactId.postValue(normalizedId)
                notifyPersonalIdChanged(normalizedId)

                // 🔥 НОВОЕ: HTTP регистрация пользователя на сервере при запуске приложения
                Log.d("MyApp", "🚀 HTTP регистрация пользователя на сервере при старте приложения")
//                registerUserOnServer(normalizedId)

                // 🔥 НОВОЕ: HTTP регистрация устройства на сервере при запуске приложения
                Log.d("MyApp", "🚀 HTTP регистрация устройства на сервере при старте приложения")
//                registerDeviceOnServer(deviceId)

                // ❌ УБРАНО: startConnectionService() отсюда - теперь запускается из ContactsListActivity
                // чтобы избежать ошибки ForegroundServiceStartNotAllowedException в Android 12+
                Log.d("MyApp", "ℹ️ ConnectionService will be started from ContactsListActivity")

                // ❌ УБРАНО: checkAndConnectToServer(normalizedId) - WebSocket подключается только при звонках
                Log.d("MyApp", "ℹ️ WebSocket connection will be established only when needed for calls")

                Log.d("MyApp", "✅ Personal ID извлечен из root контакта: ${rootContact.personal_id}")
            } else {
                Log.d("MyApp", "ℹ️ Root контакт не найден или personal_id отсутствует")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MyApp", "❌ Ошибка при извлечении personal_id: ${e.message}")
        }
    }

    fun setWebRTCActivityListener(listener: WebRTCClient.WebRTCListener?) {
        if (listener != null) {
            webRTCListener.addActivityListener(listener)
            Log.d("MyApp", "✅ Activity listener added")
        } else {
            // Если нужно удалить listener
            // webRTCListener.removeActivityListener(listener)
            Log.d("MyApp", "🔄 Activity listener cleared")
        }
    }
    fun initializeWebRTC(userId: String) {
        if (!isWebRTCInitialized) {
            try {
                webRTCClient = WebRTCClient(
                    context = this,
                    serverUrl = "https://e65ea583-8f5e-4d58-9f20-58e16b4d9ba5-00-182nvjnxjskc1.sisko.replit.dev",
                    userId = userId,
                    listener = webRTCListener
                )

                // Устанавливаем ссылку на MyApp в WebRTCClient
                webRTCClient.setMyApp(this)

                // ИНИЦИАЛИЗИРУЕМ CALL MANAGER

                isWebRTCInitialized = true
                _webRTCInitializedLiveData.postValue(true)
                Log.d("MyApp", "✅ WebRTC client initialized in MyApp with ID: $userId")
                
                // 🔥 НОВОЕ: Регистрация устройства на сервере после инициализации WebRTC
                deviceId = getDeviceIdentifier()
                webRTCClient.registerDevice(deviceId) { deviceList ->
                    Log.d("MyApp", "✅ Device registered. Connected devices: ${deviceList?.size ?: 0}")
                    
                    // Обновляем UI с полученным списком устройств
                    deviceList?.let { devices ->
                        updateActiveConnections(devices)
                        Log.d("MyApp", "📱 Updated active connections in UI: ${devices.size} devices")
                    }
                }
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error initializing WebRTC in MyApp: ${e.message}")
            }
        }
    }
//    fun initializeWebRTC(userId: String) {
//        if (!isWebRTCInitialized) {
//            try {
//                webRTCClient = WebRTCClient(
//                    context = this,
//                    serverUrl = "https://e65ea583-8f5e-4d58-9f20-58e16b4d9ba5-00-182nvjnxjskc1.sisko.replit.dev",
//                    userId = userId,
//                    listener = webRTCListener
//                )
//
//                // ИНИЦИАЛИЗИРУЕМ CALL MANAGER
//                callManager.initialize(webRTCClient)
//
//                isWebRTCInitialized = true
//                _webRTCInitializedLiveData.postValue(true)
//                Log.d("MyApp", "✅ WebRTC client initialized in MyApp with ID: $userId")
//            } catch (e: Exception) {
//                Log.e("MyApp", "❌ Error initializing WebRTC in MyApp: ${e.message}")
//            }
//        }
//    }
//    fun acceptCall(callerId: String, callId: String) {
//        if (isWebRTCInitialized) {
//            webRTCClient.acceptCall(callerId, callId)
//            // ❌ УБЕРИ этот вызов - он уже есть внутри acceptCall
//            // webRTCClient.startAudioSession()
//        }
//    }
//    fun acceptCall(callerId: String, callId: String) {
//        if (isWebRTCInitialized) {
//            webRTCClient.acceptCall(callerId, callId)
//            webRTCClient.startAudioSession() // Дублируем для надежности
//        }
//    }

    fun rejectCall(callerId: String, callId: String) {
        if (isWebRTCInitialized) {
            webRTCClient.rejectIncomingCall(callId)
        }
    }

    fun endCurrentCall() {
        if (isWebRTCInitialized) {
            webRTCClient.endCall()
        }
    }
    private fun notifyServerConnectionChanged(state: ServerConnectionState) {
        serverConnectionChangeCallbacks.forEach { callback ->
            try {
                callback(state)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun notifyPersonalIdChanged(personalId: String?) {
        personalIdChangeCallbacks.forEach { callback ->
            try {
                callback(personalId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun checkAndConnectToServer(personalId: String) {
        connectionExecutor.execute {
            try {
                // Проверяем есть ли уже подключение
                if (isConnectedToServer && socket?.connected() == true) {
                    Log.d("MyApp", "✅ Already connected to server")
                    // ❌ ОТКЛЮЧЕНО: Автоматический запуск ConnectionService
                    // startConnectionService()
                    return@execute
                }
                
                // Обновляем состояние на "подключение"
                updateServerConnectionState(ServerConnectionState.Connecting)

                Log.d("MyApp", "🔄 Попытка подключения к серверу с personal_id: $personalId")

                // Если уже подключены, сначала отключаемся
                if (isConnectedToServer && socket != null) {
                    disconnectFromServer()
                }

                // Выполняем подключение
                connectToServer(personalId)
                
                // ❌ ОТКЛЮЧЕНО: Автоматический запуск ConnectionService
                // startConnectionService()

            } catch (e: Exception) {
                e.printStackTrace()
                handleConnectionError("Ошибка при подключении: ${e.message}")
            }
        }
    }

    // ЯВНАЯ ПЕРЕРЕГИСТРАЦИЯ ПОД ТЕМ ЖЕ personal_id
    fun forceReregister() {
        val personalId = _personalId0.value
        if (personalId.isNullOrBlank()) return
        try {
            if (socket?.connected() == true) {
                socket?.emit("register", personalId)
                Log.d("MyApp", "👤 Force re-register with ID: $personalId")
            } else {
                // ❌ УБРАНО: checkAndConnectToServer(personalId) - WebSocket подключается только при звонках
                Log.d("MyApp", "ℹ️ WebSocket connection will be established only when needed for calls")
            }
        } catch (e: Exception) {
            Log.e("MyApp", "❌ Force re-register error: ${e.message}")
        }
    }

    // НОВЫЙ МЕТОД: HTTP регистрация пользователя на сервере
    private fun registerUserOnServer(userId: String) {
        connectionExecutor.execute {
            try {
                val url = URL("$SIGNALING_SERVER_URL/api/users/register")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

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
                        Log.d("MyApp", "✅ User $userId successfully registered on server via HTTP")
                        
                        // Получаем список подключенных пользователей
                        val userList = mutableListOf<String>()
                        val usersArray = jsonResponse.optJSONArray("userList")
                        usersArray?.let { array ->
                            for (i in 0 until array.length()) {
                                userList.add(array.getString(i))
                            }
                        }
                        
                        // Обновляем UI с полученным списком
                        mainHandler.post {
                            updateActiveConnections(userList)
                            Log.d("MyApp", "📱 Updated active connections from HTTP registration: ${userList.size} users")
                        }
                    } else {
                        Log.e("MyApp", "❌ HTTP user registration failed: ${jsonResponse.optString("error")}")
                    }
                } else {
                    Log.e("MyApp", "❌ HTTP error registering user: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error registering user via HTTP: ${e.message}")
            }
        }
    }

    // НОВЫЙ МЕТОД: HTTP регистрация устройства на сервере
//    private fun registerDeviceOnServer(deviceId: String) {
//        connectionExecutor.execute {
//            try {
//                val url = URL("$SIGNALING_SERVER_URL/api/devices/register")
//                val connection = url.openConnection() as HttpURLConnection
//                connection.requestMethod = "POST"
//                connection.setRequestProperty("Content-Type", "application/json")
//                connection.doOutput = true
//
//                val json = JSONObject().apply {
//                    put("deviceId", deviceId)
//                }
//
//                connection.outputStream.use { os ->
//                    os.write(json.toString().toByteArray())
//                }
//
//                val responseCode = connection.responseCode
//                if (responseCode == 200) {
//                    Log.d("MyApp", "✅ Device registered on server: $deviceId")
//                    } else {
//                    Log.e("MyApp", "❌ Device registration failed: HTTP $responseCode")
//                }
//            } catch (e: Exception) {
//                Log.e("MyApp", "❌ Error registering device: ${e.message}")
//            }
//        }
//    }

    // НОВЫЙ МЕТОД: HTTP отмена регистрации пользователя на сервере
    private fun unregisterUserFromServer(userId: String) {
        connectionExecutor.execute {
            try {
                val url = URL("$SIGNALING_SERVER_URL/api/users/unregister")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val json = JSONObject().apply {
                    put("userId", userId)
                }

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == 200 || responseCode == 204) {
                    Log.d("MyApp", "✅ User $userId successfully unregistered from server via HTTP")
                } else {
                    Log.e("MyApp", "❌ HTTP error unregistering user: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error unregistering user via HTTP: ${e.message}")
            }
        }
    }

    // НОВЫЙ МЕТОД: HTTP отмена регистрации устройства на сервере
    private fun unregisterDeviceFromServer(deviceId: String) {
        connectionExecutor.execute {
            try {
                Log.d("MyApp", "📱 Starting device unregistration: $deviceId")
                
                val url = URL("$SIGNALING_SERVER_URL/api/devices/unregister")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000 // 5 секунд таймаут
                connection.readTimeout = 5000

                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                }
                
                Log.d("MyApp", "📱 Sending unregister request for device: $deviceId")

                connection.outputStream.use { os ->
                    os.write(json.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                Log.d("MyApp", "📱 HTTP response code: $responseCode")
                
                if (responseCode == 200 || responseCode == 204) {
                    Log.d("MyApp", "✅ Device unregistered from server: $deviceId")
                } else {
                    Log.e("MyApp", "❌ Device unregistration failed: HTTP $responseCode")
                    // Читаем ответ сервера для отладки
                    try {
                        val errorResponse = connection.errorStream?.bufferedReader()?.readText()
                        Log.e("MyApp", "❌ Server error response: $errorResponse")
                    } catch (e: Exception) {
                        Log.e("MyApp", "❌ Could not read error response: ${e.message}")
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error unregistering device: ${e.message}")
                e.printStackTrace()
            }
        }
    }


    private fun connectToServer(personalId: String) {
        try {
            // Проверяем есть ли уже подключение
            if (isConnectedToServer && socket?.connected() == true) {
                Log.d("MyApp", "✅ Already connected, no need to connect again")
                return
            }
            
            // ОТМЕНЯЕМ ПРЕДЫДУЩИЙ ТАЙМАУТ ЕСЛИ ОН БЫЛ
            cancelConnectionTimeout()

            // Создаем Socket.IO подключение
            val options = IO.Options().apply {
                forceNew = true
                reconnectionAttempts = 0  // ❌ ОТКЛЮЧЕНО: Автоматическое переподключение
                reconnection = false      // ❌ ОТКЛЮЧЕНО: Автоматическое переподключение
                timeout = 20000
                // Разрешаем fallback на polling (транспорты по умолчанию)
                path = "/socket.io/"
                reconnectionDelay = 0     // ❌ ОТКЛЮЧЕНО: Задержка переподключения
                reconnectionDelayMax = 0 // ❌ ОТКЛЮЧЕНО: Максимальная задержка
            }

            socket = IO.socket(SIGNALING_SERVER_URL, options)

            // Обработчики событий Socket.IO
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("MyApp", "🔌 Socket.IO подключен")
                connectionRetryCount = 0

                // ОТМЕНЯЕМ ТАЙМАУТ ПРИ УСПЕШНОМ ПОДКЛЮЧЕНИИ
                cancelConnectionTimeout()

                // Регистрируем пользователя на сервере
                socket?.emit("register", personalId)

                // МОМЕНТАЛЬНОЕ ОБНОВЛЕНИЕ: Подключение установлено
                updateServerConnectionState(ServerConnectionState.Connected)
                isConnectedToServer = true

                // ЗАПУСКАЕМ HTTP ОПРОС ВХОДЯЩИХ ЗВОНКОВ (параллельно с Socket.IO)
                startIncomingCallsPolling()

                // ЗАПРАШИВАЕМ СПИСОК ПОДКЛЮЧЕННЫХ ID
                fetchActiveConnectionsFromServer()

                mainHandler.post {
//                    Toast.makeText(this, "✅ Подключено к серверу", Toast.LENGTH_SHORT).show()
                }
            }

            socket?.on("registration_success") { args ->
//                Log.d("MyApp", "✅ Успешная регистрация на сервере")
                fetchActiveConnectionsFromServer()
            }

            socket?.on("user_connected") { data ->
                fetchActiveConnectionsFromServer()
//                Log.d("MyApp", "📱 Новый пользователь подключился к серверу")
            }

            socket?.on("user_disconnected") { data ->
                fetchActiveConnectionsFromServer()
//                Log.d("MyApp", "📱 Пользователь отключился от сервера")
            }

            socket?.on("registration_failed") { args ->
                Log.d("MyApp", "❌ Ошибка регистрации на сервере")
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("MyApp", "🔌 Socket.IO отключен")

                // ЗАПУСКАЕМ ТАЙМАУТ ТОЛЬКО ЕСЛИ РАНЬШЕ БЫЛО ПОДКЛЮЧЕНИЕ
                if (isConnectedToServer) {
                    scheduleConnectionTimeout()
                }
            }

            // ПОВЕДЕНИЕ ПРИ ПОВТОРНОМ ПОДКЛЮЧЕНИИ
//            socket?.on(Socket.EVENT_RECONNECT) {
//                try {
//                    val id = _personalId0.value
//                    if (!id.isNullOrBlank()) {
//                        socket?.emit("register", id)
//                        Log.d("MyApp", "🔄 Reconnected → re-register: $id")
//                    }
//                } catch (e: Exception) {
//                    Log.e("MyApp", "❌ Re-register on reconnect error: ${e.message}")
//                }
//            }
//
//            socket?.on(Socket.EVENT_RECONNECT_ATTEMPT) { args ->
//                Log.d("MyApp", "🔄 Reconnect attempt: ${args.joinToString()}")
//            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val errorMessage = args.joinToString()
                Log.e("MyApp", "❌ Ошибка подключения Socket.IO: $errorMessage")

                // ЗАПУСКАЕМ ТАЙМАУТ ПРИ ОШИБКЕ ПОДКЛЮЧЕНИЯ
                scheduleConnectionTimeout()
            }

            // ЗАПУСКАЕМ ТАЙМАУТ ПРИ НАЧАЛЕ ПОДКЛЮЧЕНИЯ
            scheduleConnectionTimeout()

            // Подключаемся
            socket?.connect()

        } catch (e: Exception) {
            e.printStackTrace()
            handleConnectionError("Исключение при подключении: ${e.message}")
        }
    }

    // НОВЫЕ МЕТОДЫ ДЛЯ УПРАВЛЕНИЯ ТАЙМАУТОМ
    private fun scheduleConnectionTimeout() {
        if (!isConnectionTimeoutScheduled) {
            isConnectionTimeoutScheduled = true
            connectionTimeoutHandler.postDelayed({
                handleConnectionTimeout()
            }, CONNECTION_TIMEOUT_DELAY)
            Log.d("MyApp", "⏰ Таймаут подключения запланирован на 10 секунд")
        }
    }

    private fun cancelConnectionTimeout() {
        if (isConnectionTimeoutScheduled) {
            connectionTimeoutHandler.removeCallbacksAndMessages(null)
            isConnectionTimeoutScheduled = false
            Log.d("MyApp", "⏰ Таймаут подключения отменен")
        }
    }

    private fun handleConnectionTimeout() {
        isConnectionTimeoutScheduled = false

        // ПРОВЕРЯЕМ, ЧТО МЫ ДЕЙСТВИТЕЛЬНО НЕ ПОДКЛЮЧЕНЫ
        if (!isConnectedToServer && socket?.connected() != true) {
            Log.d("MyApp", "⏰ Таймаут подключения истек - устанавливаем статус Disconnected")
            updateServerConnectionState(ServerConnectionState.Disconnected)
            isConnectedToServer = false
            stopIncomingCallsPolling() // Останавливаем HTTP опрос
            updateActiveConnections(emptyList())

            mainHandler.post {
                Toast.makeText(this, "❌ Не удалось подключиться к серверу", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d("MyApp", "⏰ Таймаут истек, но подключение восстановилось - статус не меняем")
        }
    }

    // НОВЫЙ МЕТОД: Принудительное обновление списка подключенных
    fun refreshActiveConnections() {
        if (isConnectedToServer) {
            fetchActiveConnectionsFromServer()
        } else {
            Log.d("MyApp", "⚠️ Не подключены к серверу, невозможно обновить список подключенных")
        }
    }

    // НОВЫЙ МЕТОД: Централизованное обновление состояния соединения
    private fun updateServerConnectionState(state: ServerConnectionState) {
        mainHandler.post {
            _serverConnectionState.value = state
            notifyServerConnectionChanged(state)

            // Логируем изменение состояния
            when (state) {
                is ServerConnectionState.Connected -> Log.d("MyApp", "✅ Состояние соединения обновлено: Connected")
                is ServerConnectionState.Connecting -> Log.d("MyApp", "🔄 Состояние соединения обновлено: Connecting")
                is ServerConnectionState.Disconnected -> Log.d("MyApp", "🔌 Состояние соединения обновлено: Disconnected")
                is ServerConnectionState.Error -> Log.d("MyApp", "❌ Состояние соединения обновлено: Error - ${state.message}")
            }
        }
    }

    // НОВЫЙ МЕТОД: Обработка ошибок соединения
    private fun handleConnectionError(errorMessage: String) {
        isConnectedToServer = false
        stopIncomingCallsPolling() // Останавливаем HTTP опрос
        updateServerConnectionState(ServerConnectionState.Error(errorMessage))

        mainHandler.post {
            Toast.makeText(this, "❌ $errorMessage", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectFromServer() {
        try {
            // ОТМЕНЯЕМ ТАЙМАУТ ПРИ РУЧНОМ ОТКЛЮЧЕНИИ
            cancelConnectionTimeout()

            // Отключаем Socket.IO
            socket?.disconnect()
            socket?.off()
            socket = null

            // МГНОВЕННО ОБНОВЛЯЕМ СТАТУС ПРИ РУЧНОМ ОТКЛЮЧЕНИИ
            isConnectedToServer = false
            stopIncomingCallsPolling() // Останавливаем HTTP опрос
            updateServerConnectionState(ServerConnectionState.Disconnected)
            updateActiveContacts(emptyList())

            Log.d("MyApp", "🔌 Отключились от сервера")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun saveContactsToJsonFile(contacts: List<Contact>) {
        try {
            val contactsFile = File(contactsJsonPath)
            val contactsDir = contactsFile.parentFile

            // Создаем папку если она не существует
            if (contactsDir != null && !contactsDir.exists()) {
                val created = contactsDir.mkdirs()
                if (created) {
                    Log.d("MyApp", "✅ Папка создана при сохранении: ${contactsDir.absolutePath}")
                } else {
                    Log.e("MyApp", "❌ Не удалось создать папку при сохранении: ${contactsDir.absolutePath}")
                    return
                }
            }

            val sortedContacts = sortContactsAlphabeticallyWithRootFirst(contacts)
            val contactsResponse = ContactsResponse(sortedContacts)
            val jsonString = Json.encodeToString(contactsResponse)
            contactsFile.writeText(jsonString)

            Log.d("MyApp", "Contacts successfully saved to JSON file: ${contactsFile.absolutePath}")
            Log.d("MyApp", "Total contacts saved: ${sortedContacts.size}")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MyApp", "Error saving contacts to JSON file: ${e.message}")
        }
    }
//    private fun saveContactsToJsonFile(contacts: List<Contact>) {
//        try {
//            val contactsFile = File(contactsJsonPath)
//            val sortedContacts = sortContactsAlphabeticallyWithRootFirst(contacts)
//            contactsFile.parentFile?.mkdirs()
//
//            val contactsResponse = ContactsResponse(sortedContacts)
//            val jsonString = Json.encodeToString(contactsResponse)
//            contactsFile.writeText(jsonString)
//
//            Log.d("MyApp", "Contacts successfully saved to JSON file: ${contactsFile.absolutePath}")
//            Log.d("MyApp", "Total contacts saved: ${sortedContacts.size}")
//        } catch (e: Exception) {
//            e.printStackTrace()
//            Log.e("MyApp", "Error saving contacts to JSON file: ${e.message}")
//        }
//    }

    fun getRootContactId(): String? = _rootContactId.value

    fun updateContacts(newContacts: List<Contact>) {
        val sortedContacts = sortContactsAlphabeticallyWithRootFirst(newContacts)
        _contactsLiveData.postValue(sortedContacts)
        val contactsResponse = ContactsResponse(sortedContacts)
        val jsonString = Json.encodeToString(contactsResponse)
        mmkv.encode(CONTACTS_KEY, jsonString)
        notifyContactsChanged(sortedContacts)
        extractPersonalIdFromRootContact(sortedContacts)

        Log.d("MyApp", "Updated contacts list with ${sortedContacts.size} contacts")
    }

    fun saveUserCredentials(userId: String, authToken: String) {
        mmkv.encode(USER_ID_KEY, userId)
        mmkv.encode(AUTH_TOKEN_KEY, authToken)
    }

    fun getSavedUserId(): String? = mmkv.decodeString(USER_ID_KEY)

    private fun sortContactsAlphabeticallyWithRootFirst(contacts: List<Contact>): List<Contact> {
        return contacts.sortedWith(compareByDescending<Contact> { it.root_contact == true }
            .thenBy { it.Name ?: "" })
    }

    fun getSavedAuthToken(): String? = mmkv.decodeString(AUTH_TOKEN_KEY)

    // Метод для получения personal_id из root-контакта
    fun getPersonalId0(): String? = _personalId0.value

    // Методы для управления подключением к серверу
    fun isConnectedToServer(): Boolean = isConnectedToServer

    fun reconnectToServer() {
        val personalId = _personalId0.value
        if (!personalId.isNullOrBlank()) {
            // ❌ УБРАНО: checkAndConnectToServer(personalId) - WebSocket подключается только при звонках
            Log.d("MyApp", "ℹ️ WebSocket connection will be established only when needed for calls")
            Toast.makeText(this, "ℹ️ WebSocket подключается только при звонках", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "❌ Personal ID не найден", Toast.LENGTH_SHORT).show()
        }
    }

    fun disconnectFromServerPublic() {
        disconnectFromServer()
    }

    // ========== CONNECTION SERVICE ИНТЕГРАЦИЯ ==========
    
    fun startConnectionService() {
        val personalId = getPersonalId0()
        Log.d("MyApp", "🔍 Attempting to start ConnectionService...")
        Log.d("MyApp", "   - personalId: ${personalId?.take(8)}...")
        Log.d("MyApp", "   - isConnectionServiceRunning: ${isConnectionServiceRunning()}")
        Log.d("MyApp", "   - deviceId: ${deviceId.take(8)}...")
        
        if (!personalId.isNullOrBlank()) {
            try {
                Log.d("MyApp", "🚀 Calling ConnectionService.startService()...")
                ConnectionService.startService(
                    this,
                    "https://e65ea583-8f5e-4d58-9f20-58e16b4d9ba5-00-182nvjnxjskc1.sisko.replit.dev",
                    personalId
                )
                Log.d("MyApp", "✅ ConnectionService.startService() called successfully")
                
                // Привязываемся к сервису для взаимодействия
                val intent = Intent(this, ConnectionService::class.java)
                Log.d("MyApp", "🔗 Creating bind intent: ${intent.component}")
                val bindResult = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                
                Log.d("MyApp", "🔄 ConnectionService start requested")
                Log.d("MyApp", "   - bindService result: $bindResult")
                Log.d("MyApp", "   - userId: $personalId")
                
                // Проверяем статус через 3 секунды
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d("MyApp", "🔍 ConnectionService status check after 3 seconds:")
                    Log.d("MyApp", "   - isConnectionServiceRunning: ${isConnectionServiceRunning()}")
                    Log.d("MyApp", "   - getConnectionService: ${getConnectionService()}")
                }, 3000)
                
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error starting ConnectionService: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.w("MyApp", "⚠️ Cannot start ConnectionService: personalId is null or blank")
            Log.d("MyApp", "   - personalId value: '$personalId'")
            Log.d("MyApp", "   - Will retry when personalId becomes available")
        }
    }
    
    fun stopConnectionService() {
        Log.d("MyApp", "🛑 Stopping ConnectionService...")
        
        // Сначала отменяем регистрацию пользователя и устройства через HTTP
        val personalId = getPersonalId0()
        if (!personalId.isNullOrBlank()) {
            Log.d("MyApp", "📤 Unregistering user from server: $personalId")
            unregisterUserFromServer(personalId)
        }
        
        if (deviceId.isNotEmpty()) {
            Log.d("MyApp", "📤 Unregistering device from server: $deviceId")
            unregisterDeviceFromServer(deviceId)
        }
        
        // 🔥 КРИТИЧЕСКИ ВАЖНО: Даем время для выполнения HTTP запросов перед остановкой сервиса
        Handler(Looper.getMainLooper()).postDelayed({
            // Отменяем привязку к сервису
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
                Log.d("MyApp", "🔗 Service unbound")
            }
            
            // Останавливаем сервис
            val intent = Intent(this, ConnectionService::class.java)
            stopService(intent)
            connectionService = null
            
            Log.d("MyApp", "✅ ConnectionService stopped and unbound")
        }, 1000) // Задержка 1 секунда для выполнения HTTP запросов
    }
    
    fun isConnectionServiceRunning(): Boolean {
        return connectionService != null && isServiceBound
    }
    
    fun getConnectionService(): ConnectionService? {
        return connectionService
    }
    
    // Методы для регистрации callback'ов в ConnectionService
    fun registerConnectionServiceCallbacks() {
        connectionService?.let { service ->
            Log.d("MyApp", "✅ ConnectionService callbacks registered (no callbacks in current implementation)")
        }
    }
    
    fun unregisterConnectionServiceCallbacks() {
        connectionService?.let { service ->
            Log.d("MyApp", "🔄 ConnectionService callbacks unregistered")
        }
    }
    
    fun cleanup() {
        networkManager.disconnect()
        // Отключаем SignalClient при очистке
        signalClient.disconnect()
        // Отключаемся от сервера
        disconnectFromServer()
        // Останавливаем ConnectionService
        stopConnectionService()
    }

    private fun generateNextListId(contacts: List<Contact>): Int {
        val maxListId = contacts.maxOfOrNull { it.list_id ?: -1 } ?: -1
        return maxListId + 1
    }

    fun addContact(newContact: Contact) {
        try {
            val currentContacts = _contactsLiveData.value ?: emptyList()
            val nextListId = generateNextListId(currentContacts)
            val contactWithId = newContact.copy(list_id = nextListId)
            val updatedContacts = currentContacts + contactWithId

            updateContacts(updatedContacts)
            saveContactsToJsonFile(updatedContacts)

            Log.d("MyApp", "New contact successfully created: ${contactWithId.Name} (list_id: $nextListId)")
            Log.d("MyApp", "Total contacts now: ${updatedContacts.size}")

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("MyApp", "Error creating new contact: ${e.message}")
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        
        Log.d("MyApp", "🛑 MyApp terminating - cleaning up registrations")
        
        // 🔥 КРИТИЧЕСКИ ВАЖНО: Сначала останавливаем ConnectionService
        try {
            Log.d("MyApp", "🛑 Stopping ConnectionService on terminate")
            stopConnectionService()
        } catch (e: Exception) {
            Log.e("MyApp", "❌ Error stopping ConnectionService on terminate: ${e.message}")
        }
        
        // 🔥 ОТМЕНЯЕМ РЕГИСТРАЦИЮ ПОЛЬЗОВАТЕЛЯ НА СЕРВЕРЕ
        val personalId = getPersonalId0()
        if (!personalId.isNullOrBlank()) {
            Log.d("MyApp", "📤 Unregistering user from server: $personalId")
            unregisterUserFromServer(personalId)
        } else {
            Log.d("MyApp", "⚠️ No personalId found for unregistration")
        }
        
        // 🔥 ОТМЕНЯЕМ HTTP РЕГИСТРАЦИЮ УСТРОЙСТВА НА СЕРВЕРЕ
        if (deviceId.isNotEmpty()) {
            Log.d("MyApp", "📤 Unregistering device from server: $deviceId")
            unregisterDeviceFromServer(deviceId)
        } else {
            Log.d("MyApp", "⚠️ No deviceId found for unregistration")
        }
        
        // 🔥 ОТМЕНЯЕМ РЕГИСТРАЦИЮ УСТРОЙСТВА ЧЕРЕЗ WebRTC
        if (isWebRTCInitialized && deviceId.isNotEmpty()) {
            try {
                webRTCClient.unregisterDevice(deviceId) { success ->
                    if (success) {
                        Log.d("MyApp", "✅ Device unregistered via WebRTC on terminate")
                    } else {
                        Log.e("MyApp", "❌ Failed to unregister device via WebRTC")
                    }
                }
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error unregistering device via WebRTC: ${e.message}")
            }
        }
        
        // 🔥 ОСТАНАВЛИВАЕМ ConnectionService
        try {
            stopConnectionService()
            Log.d("MyApp", "✅ ConnectionService stopped")
        } catch (e: Exception) {
            Log.e("MyApp", "❌ Error stopping ConnectionService: ${e.message}")
        }
        
        cleanup()
        Log.d("MyApp", "✅ MyApp cleanup completed")
    }

    // 🔥 НОВЫЙ МЕТОД: Регистрация ProcessLifecycleOwner для отслеживания закрытия приложения
    private fun registerProcessLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : LifecycleObserver {
            @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
            fun onAppBackgrounded() {
                Log.d("MyApp", "📱 App backgrounded - checking if cleanup needed")
                // Приложение ушло в фон, но не закрыто
            }

            @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            fun onAppDestroyed() {
                Log.d("MyApp", "💀 App process destroyed - performing emergency cleanup")
                // Приложение полностью закрыто - выполняем экстренную очистку
                performEmergencyCleanup()
            }
        })
    }

    // 🔥 НОВЫЙ МЕТОД: Экстренная очистка при принудительном закрытии
    private fun performEmergencyCleanup() {
        try {
            Log.d("MyApp", "🚨 Emergency cleanup started")
            
            // 🔥 КРИТИЧЕСКИ ВАЖНО: Сначала останавливаем ConnectionService
            try {
                Log.d("MyApp", "🛑 Stopping ConnectionService in emergency cleanup")
                stopConnectionService()
            } catch (e: Exception) {
                Log.e("MyApp", "❌ Error stopping ConnectionService in emergency cleanup: ${e.message}")
            }
            
            // Отменяем регистрацию пользователя
            val personalId = getPersonalId0()
            if (!personalId.isNullOrBlank()) {
                Log.d("MyApp", "📤 Emergency unregistering user: $personalId")
                unregisterUserFromServer(personalId)
            }
            
            // Отменяем регистрацию устройства
            if (deviceId.isNotEmpty()) {
                Log.d("MyApp", "📤 Emergency unregistering device: $deviceId")
                unregisterDeviceFromServer(deviceId)
            }
            
            Log.d("MyApp", "✅ Emergency cleanup completed")
        } catch (e: Exception) {
            Log.e("MyApp", "❌ Error during emergency cleanup: ${e.message}")
        }
    }
}
