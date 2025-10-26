    package com.ppnkdeapp.mycontacts

    import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
    import androidx.compose.material.Icon
    import androidx.compose.material.IconButton
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.filled.Call
    import android.content.Intent
    import android.content.pm.PackageManager
    import android.media.RingtoneManager
    import android.net.Uri
    import com.ppnkdeapp.mycontacts.models.Call
    import android.util.Log
    import android.widget.Toast
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.compose.animation.animateColorAsState
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.material.CircularProgressIndicator
    import androidx.compose.foundation.lazy.items
    import androidx.compose.material.*
    import androidx.compose.runtime.*
    import androidx.compose.runtime.livedata.observeAsState
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextAlign
    import androidx.compose.ui.tooling.preview.Preview
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import androidx.compose.foundation.background
    import androidx.compose.animation.core.tween
    import androidx.compose.foundation.shape.CircleShape
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material.icons.filled.Message
    import androidx.compose.material.icons.filled.Mic
    import androidx.compose.material.icons.filled.VolumeUp
    import androidx.core.content.ContextCompat
    import androidx.lifecycle.LiveData
    import androidx.lifecycle.MutableLiveData
    import com.google.accompanist.insets.ProvideWindowInsets
    import com.google.accompanist.insets.statusBarsPadding
    // CallManager больше не используется
    import com.ppnkdeapp.mycontacts.call.WebRTCClient
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import org.webrtc.MediaStream
    import org.webrtc.PeerConnection
    import kotlinx.coroutines.delay
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext

    class ContactsListActivity : ComponentActivity(), WebRTCClient.WebRTCListener {

        private var webRTCClient: WebRTCClient? = null

        private val _incomingCallState = mutableStateOf(IncomingCallState())
        val incomingCallState: IncomingCallState get() = _incomingCallState.value

        data class IncomingCallState(
            val isIncomingCall: Boolean = false,
            val fromUserId: String = "",
            val callId: String = ""
        )

        // CallManager больше не используется

        private var isWebRTCInitialized = false
        private var ringtone: android.media.Ringtone? = null

        private val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                showMainContent()
            } else {
                showPermissionDeniedScreen()
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            
            // 🔥 ИЗМЕНЕНО: Запускаем ConnectionService СРАЗУ после onCreate, пока Activity видна
            val myApp = application as MyApp
            
            // 🔥 КРИТИЧЕСКИ ВАЖНО: Запускаем ConnectionService ДО checkPermissions
            // чтобы он был запущен в контексте видимой Activity
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.d("ContactsListActivity", "🚀 Starting ConnectionService in onCreate...")
                    myApp.startConnectionService()
                } catch (e: Exception) {
                    Log.e("ContactsListActivity", "❌ Error starting ConnectionService in onCreate: ${e.message}")
                }
            }, 100) // Задержка 100ms для полной инициализации Activity
            
            checkPermissions()
            // CallManager больше не используется

            setupServerConnectionObservers()
            setupConnectionServiceIntegration()

            myApp.rootContactId.observe(this) { rootContactId ->
                if (rootContactId != null) {
                    try {
                        if (!myApp.isWebRTCInitialized()) {
                            myApp.initializeWebRTC(rootContactId)
                        }
                        webRTCClient = myApp.getWebRTCClient()
                        myApp.setWebRTCActivityListener(this@ContactsListActivity)
//                        webRTCClient?.setListener(this@ContactsListActivity)
                        initializeActivity()
                    } catch (e: Exception) {
                        Log.e("WebRTC", "❌ Error getting WebRTC client: ${e.message}")
//                        myApp.initializeWebRTC(rootContactId)
                        webRTCClient = myApp.getWebRTCClient()
                    }
                }
            }

//            callManager.incomingCall.observe(this) { call ->
//                call?.let {
//                    showIncomingCallDialog(it)
//                }
//            }
        }

        private fun setupServerConnectionObservers() {
            val myApp = application as MyApp

            myApp.serverConnectionState.observe(this) { state ->
                when (state) {
                    is MyApp.ServerConnectionState.Connected -> {
                        Log.d("SERVER_STATUS", "✅ Подключено к серверу")
                    }

                    is MyApp.ServerConnectionState.Disconnected -> {
                        Log.d("SERVER_STATUS", "🔌 Отключено от сервера")
                    }

                    is MyApp.ServerConnectionState.Connecting -> {
                        Log.d("SERVER_STATUS", "🔄 Подключение...")
                    }

                    is MyApp.ServerConnectionState.Error -> {
                        Log.e("SERVER_STATUS", "❌ Ошибка: ${state.message}")
                    }
                }
            }

            myApp.activeConnectionsIds.observe(this) { connections ->
//                Log.d("ACTIVE_USERS", "👥 Активных пользователей: ${connections.size}")
            }

            myApp.myActiveContacts.observe(this) { activeContacts ->
//                Log.d("ACTIVE_CONTACTS", "📱 Активных контактов: ${activeContacts.size}")
            }
        }

        private fun setupConnectionServiceIntegration() {
            val myApp = application as MyApp

            Log.d("ContactsListActivity", "🔍 Checking ConnectionService status...")
            Log.d(
                "ContactsListActivity",
                "   - isConnectionServiceRunning: ${myApp.isConnectionServiceRunning()}"
            )
            Log.d(
                "ContactsListActivity",
                "   - getConnectionService: ${myApp.getConnectionService()}"
            )

            // 🔥 ИЗМЕНЕНО: Только проверяем статус, не запускаем
            // ConnectionService уже запущен в onCreate()
            if (myApp.isConnectionServiceRunning()) {
                Log.d("ContactsListActivity", "✅ ConnectionService running")
            } else {
                Log.d("ContactsListActivity", "⏳ ConnectionService starting...")
            }

            Log.d("ContactsListActivity", "📡 ConnectionService integration setup completed")
        }

        private fun showIncomingCallDialog(call: Call) {
            runOnUiThread {
            }
        }

        private fun initializeActivity() {
            // Вызываем showMainContent() снова после инициализации webRTCClient
            runOnUiThread {
                showMainContent()
            }
        }

        override fun onResume() {
            super.onResume()
        }

        override fun onPause() {
            super.onPause()
//            if (isFinishing) {
//                webRTCClient?.disconnect()
//            }
        }

        override fun onDestroy() {
            super.onDestroy()
            stopRingtone()
            val myApp = application as MyApp
            myApp.setWebRTCActivityListener(null)
        }

        // Реализация для WebRTCClient.WebRTCListener - правильно порядок параметров
        override fun onIncomingCall(callId: String, fromUserId: String) {
            handleIncomingCallInternal(fromUserId, callId)
        }

        private fun handleIncomingCallInternal(fromUserId: String, callId: String) {
            Log.d("WebRTC", "📞 Incoming call from: $fromUserId, callId: $callId")
            Log.d(
                "WebRTC",
                "📞 Activity state: isFinishing=${isFinishing}, isDestroyed=$isDestroyed"
            )

            runOnUiThread {
                Log.d("WebRTC", "📞 Updating UI with incoming call")
                _incomingCallState.value = IncomingCallState(
                    isIncomingCall = true,
                    fromUserId = fromUserId,
                    callId = callId
                )
                performVibration(1000)
                playRingtone()

                redirectToCallActivity(fromUserId, callId, true)
            }
        }


        // Дополнительные методы из WebRTCClient.WebRTCListener
        override fun onCallInitiated(callId: String) {
            Log.d("WebRTC", "📞 Call initiated: $callId")
        }

        override fun onCallAccepted(callId: String) {
            Log.d("WebRTC", "✅ Call accepted: $callId")
            resetIncomingCallState()
        }

        override fun onCallRejected(callId: String) {
            Log.d("WebRTC", "❌ Call rejected: $callId")
            resetIncomingCallState()
        }

        override fun onCallEnded(callId: String) {
            Log.d("WebRTC", "📞 Call ended: $callId")
            resetIncomingCallState()
        }

        override fun onCallFailed(callId: String, error: String) {
            Log.d("WebRTC", "❌ Call failed: $callId - $error")
            resetIncomingCallState()
        }

        override fun onWebRTCConnected() {
            Log.d("WebRTC", "✅ WebRTC connected")
        }

        override fun onWebRTCDisconnected() {
            Log.d("WebRTC", "🔴 WebRTC disconnected")
        }

        override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
            Log.d("WebRTC", "❄️ ICE connection state: $state")
        }

        private fun initializeWebRTC() {
            try {
                val myApp = application as MyApp
                myApp.rootContactId.observe(this) { rootContactId ->
                    if (rootContactId != null && !isWebRTCInitialized) {
                        webRTCClient = WebRTCClient(
                            context = this,
                            serverUrl = "https://e65ea583-8f5e-4d58-9f20-58e16b4d9ba5-00-182nvjnxjskc1.sisko.replit.dev:3000",
                            userId = rootContactId,
                            listener = this
                        )
                        isWebRTCInitialized = true
                        Log.d("WebRTC", "✅ WebRTC client initialized with ID: $rootContactId")
                    }
                }
            } catch (e: Exception) {
                Log.e("WebRTC", "❌ Error initializing WebRTC: ${e.message}")
            }
        }

        private fun redirectToCallActivity(
            targetUserId: String,
            callId: String,
            isIncoming: Boolean
        ) {
            val intent = Intent(this, com.ppnkdeapp.mycontacts.call.CallActivity::class.java).apply {
                // ✅ ИСПОЛЬЗУЕМ ЕДИНЫЕ КЛЮЧИ
                putExtra("call_id", callId)
                putExtra("caller_id", targetUserId)
                putExtra("is_incoming", isIncoming)
                putExtra("contact_name", targetUserId) // TODO: Получить реальное имя

                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            Log.d("ContactsList", "✅ CallActivity started with CallId: $callId")
        }

        //    private fun redirectToCallActivity(targetUserId: String, callId: String, isIncoming: Boolean) {
        //        val intent = Intent(this, com.ppnkdeapp.mycontacts.call.CallActivity::class.java).apply {
        //            // ✅ ПРАВИЛЬНЫЕ КЛЮЧИ (такие же как в MyApp.handleIncomingCall())
        //            putExtra("call_id", callId)
        //            putExtra("caller_id", targetUserId)
        //            putExtra("is_incoming", isIncoming)
        //            putExtra("contact_name", "Контакту") // Или получите имя контакта
        //
        //            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        //        }
        //        startActivity(intent)
        //        Log.d("ContactsList", "✅ CallActivity started with correct data")
        //    }
        private fun startCallActivity(userId: String, isIncoming: Boolean) {
            val intent = Intent(this, com.ppnkdeapp.mycontacts.call.CallActivity::class.java).apply {
                putExtra("targetUserId", userId)
                putExtra("isIncomingCall", isIncoming)
            }
            startActivity(intent)
        }

        private fun performVibration(duration: Long) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            duration,
                            VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
        }

        private val _webSocketState = MutableLiveData<Boolean>(false)
        val webSocketState: LiveData<Boolean> get() = _webSocketState

        //        override fun onSocketConnected() {
//            Log.d("WebRTC", "✅ Socket connected")
//            _webSocketState.postValue(true)
//        }
//
//        override fun onSocketDisconnected() {
//            Log.d("WebRTC", "🔴 Socket disconnected")
//            _webSocketState.postValue(false)
//        }
        private fun playRingtone() {
            val notification: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            ringtone?.play()
        }

        private fun stopRingtone() {
            ringtone?.stop()
            ringtone = null
        }

        fun resetIncomingCallState() {
            _incomingCallState.value = IncomingCallState()
            stopRingtone()
        }

        fun acceptIncomingCall() {
            val myApp = application as MyApp
            incomingCallState.let { state ->
                if (state.isIncomingCall) {
                    myApp.acceptCall(state.fromUserId, state.callId)
                    resetIncomingCallState()
                    redirectToCallActivity(state.fromUserId, state.callId, true)
                }
            }
        }

        fun rejectIncomingCall() {
            val myApp = application as MyApp
            incomingCallState.let { state ->
                if (state.isIncomingCall) {
                    myApp.rejectCall(state.fromUserId, state.callId)
                    resetIncomingCallState()
                }
            }
        }

        private fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.WAKE_LOCK,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.WAKE_LOCK,
                    Manifest.permission.FOREGROUND_SERVICE
                )
            }
        }

        private fun checkPermissions() {
            val requiredPermissions = getRequiredPermissions()

            val allPermissionsGranted = requiredPermissions.all { permission ->
                ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (allPermissionsGranted) {
                showMainContent()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }

        private fun showMainContent() {
            setContent {
                MyContactsAppTheme {
                    ProvideWindowInsets {
                        webRTCClient?.let { client ->
                            ContactsListScreen(
                                webRTCClient = client,
                                incomingCallState = incomingCallState,
                                onAcceptCall = { acceptIncomingCall() },
                                onRejectCall = { rejectIncomingCall() }
                            )
                        } ?: run {
                            // Показываем экран загрузки, пока webRTCClient не инициализирован
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }

        private fun showPermissionDeniedScreen() {
            setContent {
                MyContactsAppTheme {
                    ProvideWindowInsets {
                        PermissionDeniedScreen(
                            onRequestPermissions = { checkPermissions() }
                        )
                    }
                }
            }
        }

        //        fun callContact(contact: Contact) {
//            val targetUserId = contact.personal_id ?: return
//            val client = webRTCClient ?: run {
////                Toast.makeText(
////                    this,
////                    "❌ WebRTC клиент не инициализирован",
////                    Toast.LENGTH_SHORT
////                ).show()
//                return
//            }
//
//            Log.d("CALL_DEBUG", "=== 🎯 CALL ATTEMPT ===")
//            Log.d("CALL_DEBUG", "📞 Target: ${contact.Name} (ID: $targetUserId)")
//
//            // 🔄 ВЫНОСИМ ПРОВЕРКУ ПОДКЛЮЧЕНИЯ В НАЧАЛО
//            val isConnected = client.isSocketConnected()
//            val connectionStatus = client.getConnectionStatus()
//
//            Log.d("CALL_DEBUG", "🔌 WebSocket: $isConnected")
//            Log.d("CALL_DEBUG", "📡 Status: $connectionStatus")
//
//            // 🔄 ЕСЛИ НЕТ ПОДКЛЮЧЕНИЯ - ПЫТАЕМСЯ ПЕРЕПОДКЛЮЧИТЬСЯ И ЖДЕМ РЕЗУЛЬТАТА
//            if (!isConnected) {
//                Log.e("CALL_DEBUG", "❌ CANNOT CALL: WebSocket not connected")
//                Log.d("CALL_DEBUG", "🔄 Attempting to reconnect...")
//
//                // Запускаем в корутине, чтобы не блокировать UI поток
//                CoroutineScope(Dispatchers.Main).launch {
//                    val reconnectSuccess = attemptReconnect()
//
//                    if (reconnectSuccess) {
//                        // ✅ ПЕРЕПОДКЛЮЧЕНИЕ УСПЕШНО - ПРОДОЛЖАЕМ ЗВОНОК
//                        proceedWithCall(contact, targetUserId)
//                    } else {
//                        // ❌ ПЕРЕПОДКЛЮЧЕНИЕ НЕ УДАЛОСЬ
//                        Toast.makeText(
//                            this@ContactsListActivity,
//                            "❌ Не удалось подключиться к серверу",
//                            Toast.LENGTH_SHORT
//                        ).show()
//                    }
//                }
//                return
//            }
//
//            // ✅ ЕСЛИ ПОДКЛЮЧЕНИЕ УЖЕ ЕСТЬ - СРАЗУ ПРОДОЛЖАЕМ
//            proceedWithCall(contact, targetUserId)
//        }
        fun callContact(contact: Contact) {
            val targetUserId = contact.personal_id ?: return
            
            Log.d("CALL_DEBUG", "=== 🎯 CALL ATTEMPT ===")
            Log.d("CALL_DEBUG", "📞 Target: ${contact.Name} (ID: $targetUserId)")
            
            // Получаем MyApp и вызываем initiateCall
            val myApp = application as MyApp
            myApp.initiateCall(targetUserId)
            
            Log.d("CALL_DEBUG", "✅ Call initiated via MyApp.initiateCall()")
            
            // Показываем уведомление пользователю
            Toast.makeText(
                this,
                "📞 Звонок ${contact.Name}...",
                Toast.LENGTH_SHORT
            ).show()
        }

            // 🔄 ФУНКЦИЯ ПЕРЕПОДКЛЮЧЕНИЯ С ОЖИДАНИЕМ РЕЗУЛЬТАТА
//        private suspend fun attemptReconnect(): Boolean {
//            val myApp = application as MyApp
//            val client = myApp.getWebRTCClient()
//            return withContext(Dispatchers.IO) {
//                try {
//                    Log.d("CALL_DEBUG", "🔄 Starting socket reconnection...")
//                    client.reconnectSocket()
//
//                    // 🔄 ЖДЕМ ПОДКЛЮЧЕНИЯ (например, 5 секунд)
//                    var attempts = 0
//                    val maxAttempts = 10 // 10 попыток по 500мс = 5 секунд
//
//                    while (attempts < maxAttempts) {
//                        if (client.isSocketConnected()) {
//                            Log.d("CALL_DEBUG", "✅ Socket reconnected successfully!")
//                            return@withContext true
//                        }
//                        delay(300) // Ждем 500мс перед следующей проверкой
//                        attempts++
//                    }
//
//                    Log.e("CALL_DEBUG", "❌ Socket reconnection timeout")
//                    false
//                } catch (e: Exception) {
//                    Log.e("CALL_DEBUG", "❌ Reconnection error: ${e.message}")
//                    false
//                }
//            }
//        }

//            // 📞 ФУНКЦИЯ ДЛЯ ПРОДОЛЖЕНИЯ ЗВОНКА ПОСЛЕ ПРОВЕРКИ ПОДКЛЮЧЕНИЯ
//            private fun proceedWithCall(contact: Contact, targetUserId: String) {
//                val myApp = application as MyApp
//                val client = myApp.getWebRTCClient()
//                val callInitiated = callManager.makeCall(targetUserId, contact.Name ?: "")
//
//                Log.d("CALL_DEBUG", "📞 Call initiated: $callInitiated")

//            if (callInitiated) {
//                val currentCall = callManager.currentCall.value
//                val callId = currentCall?.callId ?: ""
//
//                Toast.makeText(this, "Звонок $targetUserId...", Toast.LENGTH_SHORT).show()
//                redirectToCallActivity(targetUserId, callId, false)
//            } else {
//                val msg = if (!client.isSocketConnected()) {
//                    "Нет подключения/регистрации"
//                } else {
//                    "Не удалось инициировать вызов"
//                }
//                Toast.makeText(this, "❌ $msg", Toast.LENGTH_SHORT).show()
//            }
            }
            //    fun callContact(contact: Contact) {
            //        val targetUserId = contact.personal_id ?: return
            //
            //        Log.d("CALL_DEBUG", "=== 🎯 CALL ATTEMPT ===")
            //        Log.d("CALL_DEBUG", "📞 Target: ${contact.Name} (ID: $targetUserId)")
            //
            //        val isConnected = webRTCClient.isSocketConnected()
            //        val connectionStatus = webRTCClient.getConnectionStatus()
            //
            //        Log.d("CALL_DEBUG", "🔌 WebSocket: $isConnected")
            //        Log.d("CALL_DEBUG", "📡 Status: $connectionStatus")
            //
            //        if (!isConnected) {
            //            Log.e("CALL_DEBUG", "❌ CANNOT CALL: WebSocket not connected")
            //            Log.d("CALL_DEBUG", "🔄 Attempting to reconnect...")
            //            webRTCClient.reconnectSocket()
            //            return
            //        }
            //
            //        val callInitiated = callManager.makeCall(targetUserId, contact.Name ?: "")
            //
            //        Log.d("CALL_DEBUG", "📞 Call initiated: $callInitiated")
            //
            //        if (callInitiated) {
            //            // ✅ ПОЛУЧАЕМ callId из текущего вызова
            //            val currentCall = callManager.currentCall.value
            //            val callId = currentCall?.callId ?: ""
            //
            //            Toast.makeText(this, "Звонок $targetUserId...", Toast.LENGTH_SHORT).show()
            //            redirectToCallActivity(targetUserId, callId, false) // ✅ Теперь с callId
            //        } else {
            //            val msg = if (!webRTCClient.isSocketConnected()) "Нет подключения/регистрации" else "Не удалось инициировать вызов"
            //            Toast.makeText(this, "❌ $msg", Toast.LENGTH_SHORT).show()
            //        }
            //    }
            //

//            override fun onRequestPermissionsResult(
//                requestCode: Int,
//                permissions: Array<out String>,
//                grantResults: IntArray
//            ) {
//                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//                checkPermissions()
//            }

        @Composable
        fun PermissionDeniedScreen(
            onRequestPermissions: () -> Unit
        ) {
            val context = LocalContext.current

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colors.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Необходимы разрешения",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Text(
                        text = "Для работы приложения необходимы разрешения на доступ к хранилищу. Это нужно для чтения и сохранения файлов контактов.",
                        fontSize = 16.sp,
                        color = MaterialTheme.colors.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Запросить снова",
                            fontSize = 16.sp,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = {
                            (context as? ContactsListActivity)?.finish()
                        }
                    ) {
                        Text(
                            text = "Выйти",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        @Composable
        fun ContactsListScreen(
            webRTCClient: WebRTCClient,
            incomingCallState: ContactsListActivity.IncomingCallState,
            onAcceptCall: () -> Unit,
            onRejectCall: () -> Unit
        ) {
            val context = LocalContext.current
            val activity = context as? ContactsListActivity
            val vibrator =
                remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
            val myApp = MyApp.getInstance(context)
            val contacts by myApp.contactsLiveData.observeAsState(initial = emptyList())
            val serverConnectionState by myApp.serverConnectionState.observeAsState(initial = MyApp.ServerConnectionState.Disconnected)
            var showRootContactDialog by remember { mutableStateOf(false) }
            var showNewContactDialog by remember { mutableStateOf(false) }
            // CallManager больше не используется

            // 🔥 ДОБАВЛЕНО: Состояние подключения WebSocket
            var webSocketConnected by remember { mutableStateOf(false) }

            // 🔥 ДОБАВЛЕНО: Обновление состояния подключения
            LaunchedEffect(Unit) {
                while (true) {
                    try {
//                    webSocketConnected = webRTCClient.isSocketConnected()
                    } catch (e: Exception) {
                        webSocketConnected = false
                    }
                    delay(1000) // Проверяем каждую секунду
                }
            }

            val sortedContacts = remember(contacts) {
                contacts.sortedBy { it.Name ?: "" }
            }

            val rootContact = remember(sortedContacts) {
                sortedContacts.find { it.root_contact == true }
            }

            val animatedBackgroundColor by animateColorAsState(
                targetValue = if (rootContact != null) {
                    when (serverConnectionState) {
                        is MyApp.ServerConnectionState.Connected -> Color(0xE400BE4F)
                        else -> Color(0xFF414141)
                    }
                } else {
                    MaterialTheme.colors.primary
                },
                animationSpec = tween(durationMillis = 500),
                label = "backgroundColor"
            )

            fun performVibration() {
                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                15,
                                VibrationEffect.DEFAULT_AMPLITUDE
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(30)
                    }
                }
            }

            fun saveContact(updatedContact: Contact) {
                try {
                    myApp.updateContactByListId(updatedContact)
                    println("Contact successfully updated: ${updatedContact.Name} (ID: ${updatedContact.personal_id})")
                } catch (e: Exception) {
                    e.printStackTrace()
                    println("Error updating contact: ${e.message}")
                }
            }

            fun createNewContact(newContact: Contact) {
                try {
                    myApp.addContact(newContact)
                    println("New contact successfully created: ${newContact.Name}")
                } catch (e: Exception) {
                    e.printStackTrace()
                    println("Error creating new contact: ${e.message}")
                }
            }

            val activeContacts by myApp.myActiveContacts.observeAsState(initial = emptyList())

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colors.background
            ) {
                Scaffold(
                    modifier = Modifier.statusBarsPadding(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable {
                                            if (rootContact != null) {
                                                performVibration()
                                                showRootContactDialog = true
                                            }
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    rootContact?.let { contact ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = contact.Name!!,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White,
                                                modifier = Modifier
                                                    .padding(start = 4.dp)
                                                    .weight(1f)
                                            )

                                            // 🔥 ДОБАВЛЕНО: Индикатор подключения WebSocket
//                                        WebSocketIndicator(
//                                            isConnected = webSocketConnected,
//                                            onReconnect = {
//                                                webRTCClient.reconnectSocket()
//                                            }
//                                        )

                                            Text(
                                                text = "👤",
                                                fontSize = 21.sp,
                                                modifier = Modifier.offset(x = (-10).dp)
                                            )
                                        }
                                    } ?: run {
                                        Text(
                                            text = "Нет root контакта",
                                            fontSize = 16.sp,
                                            color = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                            },
                            backgroundColor = animatedBackgroundColor,
                            contentColor = Color.White,
                            elevation = 4.dp
                        )
                    },
                    floatingActionButton = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 30.dp),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            FloatingActionButton(
                                onClick = {
                                    performVibration()
                                    showNewContactDialog = true
                                },
                                backgroundColor = MaterialTheme.colors.primary,
                                contentColor = Color.White,
                                modifier = Modifier.padding(end = 16.dp, bottom = 16.dp)
                            ) {
                                Text(
                                    text = "+",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    },
                    floatingActionButtonPosition = FabPosition.End,
                    isFloatingActionButtonDocked = false,
                    content = { paddingValues ->
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            MyContactsList(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(paddingValues),
                                rootContact = rootContact,
                                serverConnectionState = serverConnectionState,
                                activeContacts = activeContacts,
                                webRTCClient = webRTCClient,
                                onCallContact = { contact ->
                                    activity?.callContact(contact)
                                }
                            )
                        }
                    }
                )
            }

            if (showRootContactDialog && rootContact != null) {
                ContactEditDialog(
                    contact = rootContact,
                    onDismiss = { showRootContactDialog = false },
                    onSave = { updatedContact ->
                        saveContact(updatedContact)
                    },
                    onDelete = { contactToDelete ->
                        myApp.deleteContactByListId(contactToDelete)
                    }
                )
            }

            if (showNewContactDialog) {
                ContactEditDialog(
                    contact = Contact(
                        personal_id = null,
                        Name = "",
                        email = "",
                        group_id = null,
                        root_contact = false,
                        list_id = null
                    ),
                    onDismiss = { showNewContactDialog = false },
                    onSave = { newContact ->
                        createNewContact(newContact)
                    },
                    onDelete = { }
                )
            }
        }

        // 🔥 ДОБАВЛЕНО: Компонент индикатора WebSocket
        @Composable
        fun WebSocketIndicator(
            isConnected: Boolean,
            onReconnect: () -> Unit
        ) {
            val connectionColor = if (isConnected) Color(0xFF00FF00) else Color(0xFFFF0000)
            val connectionText = if (isConnected) "✅" else "🔴"
            val tooltipText =
                if (isConnected) "Подключено к серверу" else "Нет подключения к серверу"

            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .clickable(
                        enabled = !isConnected,
                        onClick = onReconnect
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(4.dp)
                ) {
                    // Анимированный индикатор
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                color = connectionColor,
                                shape = CircleShape
                            )
                    )

                    // Текстовый индикатор (можно оставить только один)
                    Text(
                        text = connectionText,
                        fontSize = 14.sp,
                        color = Color.White,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                // Tooltip при долгом нажатии
                if (!isConnected) {
                    Text(
                        text = "Нажмите для переподключения",
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 20.dp)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(4.dp)
                    )
                }
            }
        }

        // 🔥 ДОБАВЛЕНО: Простая версия индикатора (альтернатива)
        @Composable
        fun SimpleWebSocketIndicator(isConnected: Boolean) {
            val indicatorColor = if (isConnected) Color.Green else Color.Red
            val tooltip = if (isConnected) "Подключено" else "Отключено"

            TooltipArea(
                tooltip = {
                    Surface(
                        color = Color.Black.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = tooltip,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = indicatorColor,
                            shape = CircleShape
                        )
                        .padding(4.dp)
                )
            }
        }

        @Composable
        fun TooltipArea(tooltip: @Composable () -> Unit, content: @Composable () -> Unit) {
            TODO("Not yet implemented")
        }
//    @Composable
//    fun ContactsListScreen(
//        webRTCClient: WebRTCClient,
//        incomingCallState: ContactsListActivity.IncomingCallState,
//        onAcceptCall: () -> Unit,
//        onRejectCall: () -> Unit
//    ) {
//        val context = LocalContext.current
//        val activity = context as? ContactsListActivity
//        val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
//        val myApp = MyApp.getInstance(context)
//        val contacts by myApp.contactsLiveData.observeAsState(initial = emptyList())
//        val serverConnectionState by myApp.serverConnectionState.observeAsState(initial = MyApp.ServerConnectionState.Disconnected)
//        var showRootContactDialog by remember { mutableStateOf(false) }
//        var showNewContactDialog by remember { mutableStateOf(false) }
//        val currentCall by myApp.getCallManager().currentCall.observeAsState()
//
//        val sortedContacts = remember(contacts) {
//            contacts.sortedBy { it.Name ?: "" }
//        }
//
//        val rootContact = remember(sortedContacts) {
//            sortedContacts.find { it.root_contact == true }
//        }
//
//        val animatedBackgroundColor by animateColorAsState(
//            targetValue = if (rootContact != null) {
//                when (serverConnectionState) {
//                    is MyApp.ServerConnectionState.Connected -> Color(0xE400BE4F)
//                    else -> Color(0xFF414141)
//                }
//            } else {
//                MaterialTheme.colors.primary
//            },
//            animationSpec = tween(durationMillis = 500),
//            label = "backgroundColor"
//        )
//
//        fun performVibration() {
//            if (vibrator.hasVibrator()) {
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    vibrator.vibrate(
//                        VibrationEffect.createOneShot(
//                            15,
//                            VibrationEffect.DEFAULT_AMPLITUDE
//                        )
//                    )
//                } else {
//                    @Suppress("DEPRECATION")
//                    vibrator.vibrate(30)
//                }
//            }
//        }
//
//        fun saveContact(updatedContact: Contact) {
//            try {
//                myApp.updateContactByListId(updatedContact)
//                println("Contact successfully updated: ${updatedContact.Name} (ID: ${updatedContact.personal_id})")
//            } catch (e: Exception) {
//                e.printStackTrace()
//                println("Error updating contact: ${e.message}")
//            }
//        }
//
//        fun createNewContact(newContact: Contact) {
//            try {
//                myApp.addContact(newContact)
//                println("New contact successfully created: ${newContact.Name}")
//            } catch (e: Exception) {
//                e.printStackTrace()
//                println("Error creating new contact: ${e.message}")
//            }
//        }
//
//        val activeContacts by myApp.myActiveContacts.observeAsState(initial = emptyList())
//
//        Surface(
//            modifier = Modifier.fillMaxSize(),
//            color = MaterialTheme.colors.background
//        ) {
//            Scaffold(
//                modifier = Modifier.statusBarsPadding(),
//                topBar = {
//                    TopAppBar(
//                        title = {
//                            Row(
//                                verticalAlignment = Alignment.CenterVertically,
//                                modifier = Modifier
//                                    .clickable {
//                                        if (rootContact != null) {
//                                            performVibration()
//                                            showRootContactDialog = true
//                                        }
//                                    }
//                                    .padding(vertical = 8.dp)
//                            ) {
//                                rootContact?.let { contact ->
//                                    Row(
//                                        verticalAlignment = Alignment.CenterVertically,
//                                        modifier = Modifier.fillMaxWidth()
//                                    ) {
//                                        Text(
//                                            text = contact.Name!!,
//                                            fontSize = 18.sp,
//                                            fontWeight = FontWeight.Medium,
//                                            color = Color.White,
//                                            modifier = Modifier
//                                                .padding(start = 4.dp)
//                                                .weight(1f)
//                                        )
//                                        Text(
//                                            text = "👤",
//                                            fontSize = 21.sp,
//                                            modifier = Modifier.offset(x = (-10).dp)
//                                        )
//                                    }
//                                } ?: run {
//                                    Text(
//                                        text = "Нет root контакта",
//                                        fontSize = 16.sp,
//                                        color = Color.White.copy(alpha = 0.8f),
//                                        modifier = Modifier.padding(start = 4.dp)
//                                    )
//                                }
//                            }
//                        },
//                        backgroundColor = animatedBackgroundColor,
//                        contentColor = Color.White,
//                        elevation = 4.dp
//                    )
//                },
//                floatingActionButton = {
//                    Box(
//                        modifier = Modifier
//                            .fillMaxSize()
//                            .padding(bottom = 30.dp),
//                        contentAlignment = Alignment.BottomEnd
//                    ) {
//                        FloatingActionButton(
//                            onClick = {
//                                performVibration()
//                                showNewContactDialog = true
//                            },
//                            backgroundColor = MaterialTheme.colors.primary,
//                            contentColor = Color.White,
//                            modifier = Modifier.padding(end = 16.dp, bottom = 16.dp)
//                        ) {
//                            Text(
//                                text = "+",
//                                fontSize = 24.sp,
//                                fontWeight = FontWeight.Bold
//                            )
//                        }
//                    }
//                },
//                floatingActionButtonPosition = FabPosition.End,
//                isFloatingActionButtonDocked = false,
//                content = { paddingValues ->
//                    Column(
//                        modifier = Modifier.fillMaxSize()
//                    ) {
//                        MyContactsList(
//                            modifier = Modifier
//                                .weight(1f)
//                                .padding(paddingValues),
//                            rootContact = rootContact,
//                            serverConnectionState = serverConnectionState,
//                            activeContacts = activeContacts,
//                            webRTCClient = webRTCClient,
//                            onCallContact = { contact ->
//                                activity?.callContact(contact)
//                            }
//                        )
//                    }
//                }
//            )
//
//    //
//        }
//
//        if (showRootContactDialog && rootContact != null) {
//            ContactEditDialog(
//                contact = rootContact,
//                onDismiss = { showRootContactDialog = false },
//                onSave = { updatedContact ->
//                    saveContact(updatedContact)
//                },
//                onDelete = { contactToDelete ->
//                    myApp.deleteContactByListId(contactToDelete)
//                }
//            )
//        }
//
//        if (showNewContactDialog) {
//            ContactEditDialog(
//                contact = Contact(
//                    personal_id = null,
//                    Name = "",
//                    email = "",
//                    group_id = null,
//                    root_contact = false,
//                    list_id = null
//                ),
//                onDismiss = { showNewContactDialog = false },
//                onSave = { newContact ->
//                    createNewContact(newContact)
//                },
//                onDelete = { }
//            )
//        }
//    }

        @Composable
        fun IncomingCallDialog(
            fromUserId: String,
            callerId: String,
            callId: String,
            onAccept: () -> Unit,
            onReject: () -> Unit
        ) {
            val context = LocalContext.current
            val onAccept = {
                //        Log.d("Call", "✅ Принят вызов от $callerId")
                val myApp = MyApp.getInstance(context)
                myApp.acceptCall(callerId, callId)
                //        Toast.makeText(context, "Разговор начат", Toast.LENGTH_SHORT).show()
            }

            LaunchedEffect(key1 = callId) {
                delay(30000L)
                if (true) {
                    onReject()
                    //            Toast.makeText(context, "Звонок пропущен", Toast.LENGTH_SHORT).show()
                }
            }

            //    AlertDialog(
            //        onDismissRequest = {
            //        },
            //        title = {
            //            Text(
            //                text = "📞 Входящий звонок",
            //                fontSize = 20.sp,
            //                fontWeight = FontWeight.Bold
            //            )
            //        },
            //        text = {
            //            Column {
            //                Text("Вам звонит:", fontSize = 16.sp)
            //                Text(
            //                    text = fromUserId,
            //                    fontSize = 18.sp,
            //                    fontWeight = FontWeight.Bold,
            //                    color = MaterialTheme.colors.primary,
            //                    modifier = Modifier.padding(top = 8.dp)
            //                )
            //            }
            //        },
            //        confirmButton = {
            //            Button(
            //                onClick = onAccept,
            //                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50)),
            //                modifier = Modifier.fillMaxWidth()
            //            ) {
            //                Text("Принять", color = Color.White, fontSize = 16.sp)
            //            }
            //        },
            //        dismissButton = {
            //            Button(
            //                onClick = onReject,
            //                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF44336)),
            //                modifier = Modifier.fillMaxWidth()
            //            ) {
            //                Text("Отклонить", color = Color.White, fontSize = 16.sp)
            //            }
            //        }
            //    )
        }

        @Composable
        fun ActiveCallDialog(
            call: Call,
            onEndCall: () -> Unit,
            onToggleMute: () -> Unit,
            onToggleSpeaker: () -> Unit
        ) {
            val context = LocalContext.current

            AlertDialog(
                onDismissRequest = {
                },
                title = {
                    Text(
                        text = "📞 Активный разговор",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Разговор с:",
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = call.fromUserName,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colors.primary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        var callDuration by remember { mutableStateOf(0) }

                        LaunchedEffect(key1 = call.callId) {
                            while (true) {
                                delay(1000)
                                callDuration++
                            }
                        }

                        Text(
                            text = "Длительность: ${callDuration / 60}:${
                                String.format(
                                    "%02d",
                                    callDuration % 60
                                )
                            }",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                },
                buttons = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Микрофон",
                                        tint = MaterialTheme.colors.primary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Text("Выкл", fontSize = 12.sp)
                                }
                            }

                            IconButton(
                                onClick = onToggleSpeaker,
                                modifier = Modifier.size(60.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Динамик",
                                        tint = MaterialTheme.colors.primary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Text("Динамик", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = onEndCall,
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF44336)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text("Завершить звонок", color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            )
        }

        @Composable
        fun MyContactsList(
            modifier: Modifier = Modifier,
            rootContact: Contact? = null,
            serverConnectionState: MyApp.ServerConnectionState = MyApp.ServerConnectionState.Disconnected,
            activeContacts: List<Contact> = emptyList(),
            webRTCClient: WebRTCClient,
            onCallContact: (Contact) -> Unit
        ) {
            val context = LocalContext.current
            val myApp = MyApp.getInstance(context)
            val contacts by myApp.contactsLiveData.observeAsState(initial = emptyList())

            LaunchedEffect(Unit) {
                myApp.refreshContacts()
            }

            val filteredContacts = remember(contacts, rootContact, activeContacts) {
                if (rootContact != null) {
                    contacts.filter { it.personal_id != rootContact.personal_id }
                } else {
                    contacts
                }
            }

            val sortedContacts = remember(filteredContacts, activeContacts) {
                val activeContactIds = activeContacts.map { it.personal_id }.toSet()
                filteredContacts.sortedWith(
                    compareBy(
                    { contact -> !activeContactIds.contains(contact.personal_id) },
                    { contact -> contact.Name ?: "" }
                ))
            }

            if (sortedContacts.isEmpty()) {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Контакты не найдены",
                            fontSize = 16.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = "Загружаем...",
                            fontSize = 14.sp,
                            color = Color.LightGray
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(bottom = 15.dp)
                ) {
                    items(sortedContacts) { contact ->
                        ContactListItem(
                            contact = contact,
                            activeContacts = activeContacts,
                            webRTCClient = webRTCClient,
                            onCallContact = onCallContact
                        )
                        Divider(
                            color = Color.LightGray,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }

        @Composable
        fun ContactListItem(
            contact: Contact,
            activeContacts: List<Contact> = emptyList(),
            webRTCClient: WebRTCClient,
            onCallContact: (Contact) -> Unit
        ) {
            val context = LocalContext.current
            val vibrator =
                remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
            var showEditDialog by remember { mutableStateOf(false) }
            val myApp = MyApp.getInstance(context)

            val isContactActive = remember(contact, activeContacts) {
                activeContacts.any { activeContact ->
                    activeContact.personal_id == contact.personal_id
                }
            }

            fun performVibration() {
                if (vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                30,
                                VibrationEffect.DEFAULT_AMPLITUDE
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(30)
                    }
                }
            }

            fun saveContact(updatedContact: Contact) {
                try {
                    myApp.updateContactByListId(updatedContact)
                    println("Contact successfully updated: ${updatedContact.Name} (ID: ${updatedContact.personal_id})")
                } catch (e: Exception) {
                    e.printStackTrace()
                    println("Error updating contact: ${e.message}")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isContactActive) {
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .background(
                                color = Color(0xFF00FF00),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(15.dp))
                } else {
                    Spacer(modifier = Modifier.width(24.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            performVibration()
                            showEditDialog = true
                        }
                ) {
                    Text(
                        text = contact.Name!!,
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                    Text(
                        text = contact.email!!,
                        style = MaterialTheme.typography.body2,
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                IconButton(
                    onClick = {
                        performVibration()
                        onCallContact(contact)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Позвонить",
                        tint = MaterialTheme.colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(
                    onClick = {
                        performVibration()
//                    onCallContact(contact)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Message,
                        contentDescription = "Сообщение",
                        tint = MaterialTheme.colors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "📘",
                    modifier = Modifier.padding(start = 8.dp),
                    fontSize = 16.sp
                )
            }

            if (showEditDialog) {
                ContactEditDialog(
                    contact = contact,
                    onDismiss = { showEditDialog = false },
                    onSave = { updatedContact ->
                        saveContact(updatedContact)
                    },
                    onDelete = { contactToDelete ->
                        myApp.deleteContactByListId(contactToDelete)
                    }
                )
            }
        }

        @Composable
        fun MyContactsAppTheme(
            content: @Composable () -> Unit
        ) {
            MaterialTheme(
                colors = lightColors(
                    primary = Color(0xFF2196F3),
                    primaryVariant = Color(0xFF1976D2),
                    secondary = Color(0xFF03DAC6),
                    background = Color.White,
                    surface = Color.White,
                    onPrimary = Color.White,
                    onSecondary = Color.Black,
                    onBackground = Color.Black,
                    onSurface = Color.Black,
                ),
                content = content
            )
        }

        @Preview(showBackground = true)
        @Composable
        fun DefaultPreview() {
            MyContactsAppTheme {
                val dummyWebRTCClient = object : WebRTCClient.WebRTCListener {
                    override fun onCallInitiated(callId: String) {}
                    override fun onCallAccepted(callId: String) {}
                    override fun onCallRejected(callId: String) {}
                    override fun onCallEnded(callId: String) {}
                    override fun onCallFailed(callId: String, error: String) {}
                    override fun onIncomingCall(callId: String, fromUserId: String) {}
                    override fun onWebRTCConnected() {}
                    override fun onWebRTCDisconnected() {}
                    override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {}
                }

                ContactsListScreen(
                    webRTCClient = WebRTCClient(
                        context = androidx.compose.ui.platform.LocalContext.current,
                        serverUrl = "preview_server",
                        userId = "preview_user",
                        listener = dummyWebRTCClient
                    ),
                    incomingCallState = ContactsListActivity.IncomingCallState(),
                    onAcceptCall = {},
                    onRejectCall = {}
                )
            }
        }

        @Preview(showBackground = true)
        @Composable
        fun PermissionDeniedPreview() {
            MyContactsAppTheme {
                PermissionDeniedScreen(onRequestPermissions = {})
            }
        }


    //@Preview(showBackground = true)
    //@Composable
    //fun IncomingCallDialogPreview() {
    //    MyContactsAppTheme {
    //        IncomingCallDialog(
    //            fromUserId = "test_user",
    //            callId = "test_call_123",
    //            callerId = "test_call_123",
    //            onAccept = {},
    //            onReject = {}
    //        )
    //    }
    //}





