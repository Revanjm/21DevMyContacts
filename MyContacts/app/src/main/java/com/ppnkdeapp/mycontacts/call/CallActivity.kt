package com.ppnkdeapp.mycontacts.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ppnkdeapp.mycontacts.MyApp
import com.ppnkdeapp.mycontacts.call.ActualCall
import com.ppnkdeapp.mycontacts.call.CallService
import com.ppnkdeapp.mycontacts.call.WebRTCClient
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import org.webrtc.PeerConnection

class CallActivity : ComponentActivity() {

    private lateinit var app: MyApp
    private var callId: String? = null
    private var callerId: String? = null
    private var isIncomingCall: Boolean = false
    private var contactName: String = ""

    private var vibrator: Vibrator? = null
    private var ringtone: android.media.Ringtone? = null
    
    // 🔥 НОВОЕ: Переменные для отслеживания currentActualCall
    private var currentActualCall: ActualCall? = null
    private var actualCallCallback: ((ActualCall?) -> Unit)? = null

    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        private const val TAG = "CallActivity"
        
        // 🔥 SINGLETON: Запрещаем более одного CallActivity
        @Volatile
        private var instance: CallActivity? = null
        
        fun isAlreadyRunning(): Boolean {
            return instance != null
        }
        
        fun getInstance(): CallActivity? {
            return instance
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔥 SINGLETON: Проверяем, не запущена ли уже CallActivity
        if (isAlreadyRunning()) {
            Log.w(TAG, "⚠️ CallActivity already running, finishing this instance")
            finish()
            return
        }
        
        // Устанавливаем текущий экземпляр
        instance = this
        
        app = MyApp.getInstance(this)

        // ✅ УЛУЧШЕННАЯ ПРОВЕРКА ДАННЫХ
        Log.d(TAG, "📱 Intent received: ${intent?.action}")
        Log.d(TAG, "📱 Intent extras: ${intent?.extras?.keySet()}")

        // Получаем параметры вызова
        callId = intent?.getStringExtra("call_id")
        callerId = intent?.getStringExtra("caller_id")
        isIncomingCall = intent?.getBooleanExtra("is_incoming", false) ?: false
        contactName = intent?.getStringExtra("contact_name") ?: getContactName(callerId)
        
        // 🔥 НОВОЕ: Определяем тип звонка на основе ActualCall
        determineCallType()

        // ✅ ЕСЛИ callId ПУСТОЙ - пробуем получить из альтернативных источников
        if (callId.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ callId is empty, checking alternative sources")

            // CallManager больше не используется
        }

        // ✅ КРИТИЧЕСКАЯ ПРОВЕРКА
        if (callId.isNullOrEmpty() || callerId.isNullOrEmpty()) {
            Log.e(TAG, "❌ CRITICAL: Missing call data! callId: $callId, callerId: $callerId")
            Toast.makeText(this, "Ошибка: данные вызова отсутствуют", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupWebRTCListener()
        setupActualCallObserver()
        makeFullScreen()
        vibrator = getSystemService(Vibrator::class.java)

        setContent {
            FullScreenCallUI(
                isIncomingCall = isIncomingCall,
                contactName = contactName,
                isWebRTCConnected = isWebRTCConnected,
                onAcceptCall = { acceptCall() },
                onEndCall = { endCall() },
                onToggleMute = { toggleMute() },
                onToggleSpeaker = { toggleSpeaker() }
            )
        }

        if (isIncomingCall) {
            startRinging()
        } else {
            startOutgoingCall()
        }
    }
    // 🔥 НОВОЕ: Переменная для отслеживания состояния WebRTC соединения
    private var isWebRTCConnected = false

    private fun setupWebRTCListener() {
        app.setWebRTCActivityListener(object : WebRTCClient.WebRTCListener {
            override fun onCallAccepted(callId: String) {
                Log.d(TAG, "✅ Call accepted - connection established!")
                // Здесь можно обновить UI когда звонок подключен
            }

            override fun onCallFailed(callId: String, error: String) {
                Log.e(TAG, "❌ Call failed: $error")
                runOnUiThread {
                    // Показать ошибку пользователю
                    finish()
                }
            }

            override fun onCallEnded(callId: String) {
                Log.d(TAG, "📞 Call ended: $callId")
                runOnUiThread {
                    stopRinging()
                    finish()
                }
            }

            override fun onWebRTCConnected() {
                Log.d(TAG, "🌐 WebRTC connected - starting call timer")
                runOnUiThread {
                    isWebRTCConnected = true
                    Log.d(TAG, "🌐 isWebRTCConnected set to true in CallActivity")
                }
            }

            override fun onWebRTCDisconnected() {
                Log.d(TAG, "🌐 WebRTC disconnected")
                runOnUiThread {
                    isWebRTCConnected = false
                }
            }

            override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "🧊 ICE connection state changed: $state")
                
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            Log.d(TAG, "✅ ICE connection established - waiting for microphones")
                            // Не устанавливаем isWebRTCConnected здесь - ждем onWebRTCConnected()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            Log.w(TAG, "❌ ICE connection lost")
                            isWebRTCConnected = false
                        }
                        else -> {
                            // Другие состояния (CONNECTING, CHECKING) - не меняем isWebRTCConnected
                        }
                    }
                }
            }

            // Остальные методы
            override fun onIncomingCall(callId: String, fromUserId: String) {}
            override fun onCallInitiated(callId: String) {}
            override fun onCallRejected(callId: String) {}
        })
    }
    //
    private fun acceptCall() {
        Log.d(TAG, "✅ Accepting call with audio permission check")
        stopRinging()

        requestAudioPermissions { granted ->
            if (granted) {
                // 🔥 НОВОЕ: Отправляем ActualCall с step = accept_call
                sendActualCallToServer("accept_call")
                Log.d(TAG, "📤 Call acceptance sent to server")
            } else {
                Log.e(TAG, "❌ Audio permissions denied")
                finish()
            }
        }
    }

    // НОВЫЙ МЕТОД: Ожидание успешного переподключения WebSocket

    // НОВЫЙ МЕТОД: Принятие звонка после переподключения WebSocket
//    private fun acceptCall() {
//        Log.d(TAG, "✅ Accepting call directly via WebRTCClient")
//        stopRinging()
//
//        // ✅ ПРЯМОЙ ВЫЗОВ WebRTCClient - ОБХОДИМ CallManager
//        if (app.isWebRTCInitialized()) {
//            callerId?.let { callerId ->
//                callId?.let { callId ->
//                    app.getWebRTCClient().acceptCall(callerId, callId)
//                    Log.d(TAG, "📞 WebRTCClient.acceptCall() executed - waiting for audio session...")
//
//                    // UI переключится когда придет onCallAccepted
//                }
//            }
//        } else {
//            Log.e(TAG, "❌ WebRTC not initialized")
//        }
//    }
//    private fun acceptCall() {
//        Log.d(TAG, "✅ Accepting call via CallManager")
//        stopRinging()
//
//        requestAudioPermissions { granted ->
//            if (granted) {
//                callerId?.let { callerId ->
//                    callId?.let { callId ->
//                        // ✅ ПРЯМОЙ ВЫЗОВ WebRTCClient вместо CallManager
//                        if (app.isWebRTCInitialized()) {
//                            app.getWebRTCClient().acceptCall(callerId, callId)
//                            Log.d(TAG, "📞 WebRTCClient.acceptCall() executed directly")
//
//                            // Обновляем UI
//                            // isCallActive = true
//                            // callStatus = "Подключение..."
//                        } else {
//                            Log.e(TAG, "❌ WebRTC not initialized")
//                        }
//                    }
//                }
//            } else {
//                Log.e(TAG, "❌ Audio permissions denied")
//                finish()
//            }
//        }
//    }

    private fun makeFullScreen() {
        // Убираем статус бар и навигационную панель
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Для новых версий Android
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Composable
    fun FullScreenCallUI(
        isIncomingCall: Boolean,
        contactName: String,
        isWebRTCConnected: Boolean,
        onAcceptCall: () -> Unit,
        onEndCall: () -> Unit,
        onToggleMute: () -> Unit,
        onToggleSpeaker: () -> Unit
    ) {
        var callStatus by remember { mutableStateOf("") }
        var callDuration by remember { mutableLongStateOf(0L) }
        var isMuted by remember { mutableStateOf(false) }
        var isSpeakerOn by remember { mutableStateOf(false) }
        var isCallActive by remember { mutableStateOf(!isIncomingCall) }

        // 🔥 НОВОЕ: Запускаем таймер только после установления WebRTC соединения
        LaunchedEffect(isWebRTCConnected) {
            Log.d(TAG, "⏰ LaunchedEffect triggered - isWebRTCConnected: $isWebRTCConnected")
            if (isWebRTCConnected) {
                Log.d(TAG, "⏰ Starting call duration timer - WebRTC connected")
                while (true) {
                    delay(1000)
                    callDuration++
                    callStatus = formatDuration(callDuration)
                    Log.d(TAG, "⏰ Timer tick: $callStatus")
                }
            }
        }

        // 🔥 НОВОЕ: Обновляем статус в зависимости от состояния WebRTC
        LaunchedEffect(isCallActive, isWebRTCConnected) {
            Log.d(TAG, "🔄 Status update - isCallActive: $isCallActive, isWebRTCConnected: $isWebRTCConnected")
            when {
                isIncomingCall && !isCallActive -> {
                    callStatus = "Входящий звонок..."
                    Log.d(TAG, "📞 Status: Входящий звонок...")
                }
                isCallActive && !isWebRTCConnected -> {
                    callStatus = "Подключение микрофонов..."
                    Log.d(TAG, "📞 Status: Подключение микрофонов...")
                }
                isCallActive && isWebRTCConnected -> {
                    Log.d(TAG, "📞 Status: Таймер должен работать")
                    // Таймер уже запущен в LaunchedEffect выше
                }
                else -> {
                    callStatus = "Установка соединения..."
                    Log.d(TAG, "📞 Status: Установка соединения...")
                }
            }
        }

        // Черный фон для всего экрана
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Верхняя часть с информацией о контакте
                ContactInfoSection(
                    contactName = contactName,
                    callStatus = callStatus,
                    modifier = Modifier.weight(1f)
                )

                // Центральная часть с кнопками управления звонком
                if (isCallActive) {
                    ActiveCallControlsSection(
                        isMuted = isMuted,
                        isSpeakerOn = isSpeakerOn,
                        onToggleMute = {
                            isMuted = !isMuted
                            onToggleMute()
                        },
                        onToggleSpeaker = {
                            isSpeakerOn = !isSpeakerOn
                            onToggleSpeaker()
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    // Пустое пространство когда звонок не активен
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Нижняя часть с основными кнопками управления
                BottomCallControlsSection(
                    isIncomingCall = isIncomingCall,
                    isCallActive = isCallActive,
                    onAcceptCall = {
                        isCallActive = true
                        onAcceptCall()
                    },
                    onEndCall = onEndCall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    @Composable
    fun ContactInfoSection(
        contactName: String,
        callStatus: String,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Аватар контакта
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contactName.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Имя контакта
            Text(
                text = contactName,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Статус звонка
            Text(
                text = callStatus,
                color = Color(0xFFCCCCCC),
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    fun ActiveCallControlsSection(
        isMuted: Boolean,
        isSpeakerOn: Boolean,
        onToggleMute: () -> Unit,
        onToggleSpeaker: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка микрофона
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier.size(70.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMuted) "Включить микрофон" else "Выключить микрофон",
                            tint = if (isMuted) Color.Red else Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isMuted) "Вкл. звук" else "Выкл. звук",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                // Кнопка динамика
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier.size(70.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = if (isSpeakerOn) "Выключить динамик" else "Включить динамик",
                            tint = if (isSpeakerOn) Color(0xFF4CAF50) else Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isSpeakerOn) "Динамик" else "Динамик",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
    @Composable
    fun BottomCallControlsSection(
        isIncomingCall: Boolean,
        isCallActive: Boolean,
        onAcceptCall: () -> Unit,
        onEndCall: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            if (isIncomingCall && !isCallActive) {
                // Кнопки для ВХОДЯЩЕГО вызова (еще не принят)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Кнопка принятия вызова
                    FloatingActionButton(
                        onClick = onAcceptCall,
                        containerColor = Color(0xFF4CAF50),
                        modifier = Modifier.size(90.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Принять вызов",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(60.dp))

                    // Кнопка отклонения вызова
                    FloatingActionButton(
                        onClick = onEndCall,
                        containerColor = Color(0xFFF44336),
                        modifier = Modifier.size(90.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Отклонить вызов",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            } else {
                // Кнопки для АКТИВНОГО/ИСХОДЯЩЕГО вызова
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    // ❗ ТОЛЬКО ОДНА КНОПКА "СБРОСИТЬ" для активного звонка
                    Button(
                        onClick = onEndCall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336)
                        ),
                        modifier = Modifier
                            .width(200.dp)  // Фиксированная ширина
                            .height(60.dp)
                    ) {
                        Text(
                            text = "Сбросить",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // ❗ УБРАНА ВТОРАЯ КНОПКА "ПРИНЯТЬ" для активного звонка
                }
            }

            // Добавляем отступ снизу для навигационной панели
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
//    @Composable
//    fun BottomCallControlsSection(
//        isIncomingCall: Boolean,
//        isCallActive: Boolean,
//        onAcceptCall: () -> Unit,
//        onEndCall: () -> Unit,
//        modifier: Modifier = Modifier
//    ) {
//        Column(
//            modifier = modifier
//                .fillMaxWidth()
//                .wrapContentHeight(),
//            horizontalAlignment = Alignment.CenterHorizontally,
//            verticalArrangement = Arrangement.Bottom
//        ) {
//            if (isIncomingCall && !isCallActive) {
//                // Кнопки для входящего вызова
//                Row(
//                    modifier = Modifier.fillMaxWidth(),
//                    horizontalArrangement = Arrangement.SpaceEvenly
//                ) {
//                    // Кнопка принятия вызова
//                    FloatingActionButton(
//                        onClick = onAcceptCall,
//                        containerColor = Color(0xFF4CAF50),
//                        modifier = Modifier.size(90.dp)
//                    ) {
//                        Icon(
//                            imageVector = Icons.Default.Call,
//                            contentDescription = "Принять вызов",
//                            tint = Color.White,
//                            modifier = Modifier.size(40.dp)
//                        )
//                    }
//
//                    Spacer(modifier = Modifier.width(60.dp))
//
//                    // Кнопка отклонения вызова
//                    FloatingActionButton(
//                        onClick = onEndCall,
//                        containerColor = Color(0xFFF44336),
//                        modifier = Modifier.size(90.dp)
//                    ) {
//                        Icon(
//                            imageVector = Icons.Default.CallEnd,
//                            contentDescription = "Отклонить вызов",
//                            tint = Color.White,
//                            modifier = Modifier.size(40.dp)
//                        )
//                    }
//                }
//            } else {
//                // Кнопки для активного/исходящего вызова
//                Row(
//                    modifier = Modifier.fillMaxWidth(),
//                    horizontalArrangement = Arrangement.SpaceEvenly
//                ) {
//                    // Кнопка "Сбросить"
//                    Button(
//                        onClick = onEndCall,
//                        colors = ButtonDefaults.buttonColors(
//                            containerColor = Color(0xFFF44336)
//                        ),
//                        modifier = Modifier
//                            .weight(1f)
//                            .padding(horizontal = 12.dp)
//                            .height(60.dp)
//                    ) {
//                        Text(
//                            text = "Сбросить",
//                            color = Color.White,
//                            fontSize = 18.sp,
//                            fontWeight = FontWeight.Bold
//                        )
//                    }
//                    Button(
//                        onClick = onAcceptCall,
//                        colors = ButtonDefaults.buttonColors(
//                            containerColor = Color(0xFF4CAF50)
//                        ),
//                        modifier = Modifier
//                            .weight(1f)
//                            .padding(horizontal = 12.dp)
//                            .height(60.dp)
//                    ) {
//                        Text(
//                            text = "Принять",
//                            color = Color.White,
//                            fontSize = 15.sp,
//                            fontWeight = FontWeight.Bold
//                        )
//                    }
//
//
//                    // Кнопка "Принять" (только для входящих до принятия)
//                    if (!isCallActive) {
//
//                    } else if (isCallActive) {
////
//                    }
//                }
//            }
//
//            // Добавляем отступ снизу для навигационной панели
//            Spacer(modifier = Modifier.height(32.dp))
//        }
//    }
    // В CallActivity.kt заменить методы:

//    private fun acceptCall() {
//        Log.d(TAG, "✅ Accepting call via CallManager")
//        stopRinging()
//
//        requestAudioPermissions { granted ->
//            if (granted) {
//                callerId?.let { callerId ->
//                    callId?.let { callId ->
//                        app.getCallManager().acceptIncomingCall()
//                        // Обновляем UI
////                        isCallActive = true
////                        callStatus = "Подключение..."
//                    }
//                }
//            } else {
//                Log.e(TAG, "❌ Audio permissions denied")
//                finish()
//            }
//        }
//    }

    private fun endCall() {
        Log.d(TAG, "📞 Ending call")
        stopRinging()

        // 🔥 НОВОЕ: Отправляем ActualCall с соответствующим step
        if (isIncomingCall) {
            Log.d(TAG, "❌ Rejecting incoming call")
            sendActualCallToServer("reject_call")
        } else {
            Log.d(TAG, "📞 Ending active/outgoing call")
            sendActualCallToServer("end_call")
        }

        CallService.stopService(this)
        finish()
    }
    private fun startOutgoingCall() {
        Log.d(TAG, "📞 Starting outgoing call - CallId: $callId, Target: $callerId")

        if (!app.isWebRTCInitialized()) {
            Log.e(TAG, "❌ WebRTC not initialized for outgoing call")
            finish()
            return
        }

        // ✅ НЕ вызываем CallManager.makeCall() повторно - он уже вызван в ContactsListActivity
        // ✅ ТОЛЬКО запускаем аудио сессию
//        app.getWebRTCClient().startAudioSession()
        Log.d(TAG, "🎵 Audio session started for outgoing call")

        // WebRTC negotiation продолжится автоматически когда придет accept_call
    }
//    private fun startOutgoingCall() {
//        Log.d(TAG, "📞 Starting outgoing call - CallId: $callId, Target: $callerId")
//
//        if (!app.isWebRTCInitialized()) {
//            Log.e(TAG, "❌ WebRTC not initialized for outgoing call")
//            Toast.makeText(this, "Ошибка инициализации звонка", Toast.LENGTH_SHORT).show()
//            finish()
//            return
//        }
//
//        // Запускаем аудио сессию
//        app.getWebRTCClient().startAudioSession()
//
//        if (callerId == null) {
//            Log.e(TAG, "❌ No targetUserId for outgoing call")
//            finish()
//            return
//        }
//
//        val success = app.getCallManager().makeCall(callerId!!, contactName)
//        if (!success) {
//            Log.e(TAG, "❌ Failed to start outgoing call via CallManager")
//
//            if (callId != null) {
//                val directCallResult = app.getWebRTCClient().callUser(callerId!!, callId!!)
//                if (!directCallResult) {
//                    Log.e(TAG, "❌ Direct WebRTC call also failed")
//                    finish()
//                }
//            } else {
//                Log.e(TAG, "❌ No callId for direct WebRTC call")
//                finish()
//            }
//        }
//    }
//    private fun startOutgoingCall() {
//        Log.d(TAG, "📞 Starting outgoing call via CallManager")
//        callerId?.let { targetUserId ->
//            val success = app.getCallManager().makeCall(targetUserId, contactName)
//            if (!success) {
//                Log.e(TAG, "❌ Failed to start outgoing call")
//                finish()
//            }
//        }
//    }
    // Остальные методы остаются без изменений...

    private fun initializeAndAcceptCall() {
        try {
            // 3. Инициализируем WebRTC если нужно
            if (!app.isWebRTCInitialized()) {
                val personalId = app.getPersonalId0()
                if (!personalId.isNullOrEmpty()) {
                    app.initializeWebRTC(personalId)
                    // Ждем инициализации
                    android.os.Handler(mainLooper).postDelayed({
                        completeCallAcceptance()
                    }, 1500)
                } else {
                    Log.e(TAG, "❌ Personal ID not found")
                    finish()
                }
            } else {
                completeCallAcceptance()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error accepting call: ${e.message}")
            finish()
        }
    }

    private fun completeCallAcceptance() {
        try {
            val webRTCClient = app.getWebRTCClient()

            // 4. Запускаем аудио сессию
//            webRTCClient.startAudioSession()

            // 5. Отправляем сигнал о принятии вызова
            callerId?.let {
//                webRTCClient.sendCallAccepted(it)
                Log.d(TAG, "📞 Call accepted signal sent to: $it")
            }

            Log.d(TAG, "🎵 Audio call session started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in completeCallAcceptance: ${e.message}")
        }
    }



    private fun toggleMute() {
        if (app.isWebRTCInitialized()) {
            app.getWebRTCClient().toggleMute()
        }
    }

    private fun toggleSpeaker() {
        if (app.isWebRTCInitialized()) {
            app.getWebRTCClient().toggleSpeaker()
        }
    }


    private fun startRinging() {
        Log.d(TAG, "🔔 Starting ringtone and vibration")

        // Вибрация
        try {
            if (vibrator?.hasVibrator() == true) {
                val pattern = longArrayOf(0, 1000, 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration: ${e.message}")
        }

        // Звук звонка
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone: ${e.message}")
        }
    }

    private fun stopRinging() {
        Log.d(TAG, "🔕 Stopping ringtone and vibration")

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration: ${e.message}")
        }

        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}")
        }
    }

    private fun requestAudioPermissions(callback: (Boolean) -> Unit) {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        val allGranted = permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            callback(true)
        } else {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                if (isIncomingCall) {
                    initializeAndAcceptCall()
                }
            } else {
                Log.e(TAG, "Audio permissions denied")
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRinging()
        app.setWebRTCActivityListener(null)
        
        // 🔥 НОВОЕ: Очищаем подписку на currentActualCall
        cleanupActualCallObserver()
        
        // 🔥 SINGLETON: Очищаем instance
        instance = null
    }

    private fun getContactName(userId: String?): String {
        if (userId.isNullOrEmpty()) return "Неизвестный"

        val contacts = app.contactsLiveData.value ?: emptyList()
        val contact = contacts.find { it.personal_id == userId }
        return contact?.Name ?: "Контакту"
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = TimeUnit.SECONDS.toMinutes(seconds)
        val remainingSeconds = seconds - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }
    
    // 🔥 НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С currentActualCall
    
    /**
     * Определение типа звонка на основе ActualCall
     */
    private fun determineCallType() {
        val currentCall = app.getCurrentActualCall()
        if (currentCall != null) {
            val personalId = app.getPersonalId0()
            // Если recipientId совпадает с personalId - это входящий звонок
            isIncomingCall = currentCall.recipientId == personalId
            callId = currentCall.callId
            callerId = if (isIncomingCall) currentCall.callerId else currentCall.recipientId
            contactName = getContactName(callerId)
            
            Log.d(TAG, "📞 Call type determined: ${if (isIncomingCall) "INCOMING" else "OUTGOING"}")
            Log.d(TAG, "📞 CallerId: $callerId, RecipientId: ${currentCall.recipientId}, PersonalId: $personalId")
        }
    }
    
    /**
     * Настройка подписки на изменения currentActualCall
     */
    private fun setupActualCallObserver() {
        actualCallCallback = { actualCall ->
            Log.d(TAG, "📞 ActualCall updated in CallActivity: ${actualCall?.callId}")
            currentActualCall = actualCall
            
            // 🔥 НОВОЕ: Переопределяем тип звонка при обновлении ActualCall
            actualCall?.let { call ->
                val personalId = app.getPersonalId0()
                isIncomingCall = call.recipientId == personalId
                callId = call.callId
                callerId = if (isIncomingCall) call.callerId else call.recipientId
                contactName = getContactName(callerId)
                
                Log.d(TAG, "📞 Call type updated: ${if (isIncomingCall) "INCOMING" else "OUTGOING"}")
                
                when (call.step) {
                    "accept_call" -> {
                        Log.d(TAG, "✅ Call accepted - updating UI")
                        // Обновляем UI для принятого звонка
                    }
                    "reject_call" -> {
                        Log.d(TAG, "❌ Call rejected - closing in 5 seconds")
                        // Закрываем CallActivity через 5 секунд
                        Handler(Looper.getMainLooper()).postDelayed({
                            finish()
                        }, 5000)
                    }
                    "end_call" -> {
                        Log.d(TAG, "📞 Call ended - closing immediately")
                        finish()
                    }
                }
            }
        }
        
        // Подписываемся на изменения
        app.subscribeToActualCallChanges(actualCallCallback!!)
    }
    
    /**
     * Отправка ActualCall на сервер с обновленным step
     */
    private fun sendActualCallToServer(step: String) {
        currentActualCall?.let { call ->
            val updatedCall = call.copy(step = step)
            app.setCurrentActualCall(updatedCall)
            Log.d(TAG, "📤 Sent ActualCall to server with step: $step")
        }
    }
    
    /**
     * Очистка подписки на currentActualCall
     */
    private fun cleanupActualCallObserver() {
        actualCallCallback?.let { callback ->
            app.unsubscribeFromActualCallChanges(callback)
            actualCallCallback = null
        }
    }
}