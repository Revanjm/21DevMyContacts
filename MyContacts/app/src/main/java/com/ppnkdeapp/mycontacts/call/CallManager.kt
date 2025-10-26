//package com.ppnkdeapp.mycontacts.call
//
//import android.content.Context
//import android.util.Log
//import androidx.lifecycle.LiveData
//import androidx.lifecycle.MutableLiveData
//import com.ppnkdeapp.mycontacts.models.Call
//import com.ppnkdeapp.mycontacts.models.CallStatus
//import org.json.JSONObject
//import java.util.*
//
//class CallManager(private val context: Context) {
//    private val TAG = "CallManager"
//
//    // Состояния вызова
//    private var isMuted = false
//    private var isSpeakerOn = false
//    private var isProcessingCall = false
//
////    fun toggleMute() {
////        isMuted = !isMuted
////        _callActions.value = CallActions.MuteStateChanged(isMuted)
////        Log.d(TAG, "🎤 Mute: $isMuted")
////    }
////
////    fun toggleSpeaker() {
////        isSpeakerOn = !isSpeakerOn
////        _callActions.value = CallActions.SpeakerStateChanged(isSpeakerOn)
////        Log.d(TAG, "🔊 Speaker: $isSpeakerOn")
////    }
//
//    fun isMuted(): Boolean = isMuted
//    fun isSpeakerOn(): Boolean = isSpeakerOn
//
//    // LiveData для текущего активного вызова
//    private val _currentCall = MutableLiveData<Call?>()
//    val currentCall: LiveData<Call?> get() = _currentCall
//
//    // LiveData для входящего вызова
//    private val _incomingCall = MutableLiveData<Call?>()
////    val incomingCall: LiveData<Call?> get() = _incomingCall
//
//    // LiveData для действий в вызове
////    private val _callActions = MutableLiveData<CallActions>()
////    val callActions: LiveData<CallActions> get() = _callActions
//
////    private var webRTCClient: WebRTCClient? = null
////    private var lastCallAttemptAt: Long = 0L
//
////    fun initialize(webRTCClient: WebRTCClient) {
////        this.webRTCClient = webRTCClient
////        setupWebRTCListeners()
////    }
////    fun makeCall(toUserId: String, toUserName: String = ""): Boolean {
////        if (isProcessingCall) {
////            Log.w(TAG, "⚠️ Already processing a call, ignoring duplicate")
////            return false
////        }
////
////        val now = System.currentTimeMillis()
////        if (now - lastCallAttemptAt < 1000L) {
////            Log.w(TAG, "⏱️ Call throttled: less than 1s since last attempt")
////            return false
////        }
////
////        isProcessingCall = true
////        lastCallAttemptAt = now
//
////        try {
////            val callId = generateCallId()
//////            val fromUserId = webRTCClient?.getCurrentUserId() ?: return false
////
////            Log.d(TAG, "📞 Making call to: $toUserId, callId: $callId")
////
////            val call = Call(
////                callId = callId,
////                fromUserId = fromUserId,
////                fromUserName = "Вы",
////                toUserId = toUserId,
////                status = CallStatus.INITIATING
////            )
////
////            _currentCall.value = call
////
////            if (webRTCClient == null) {
////                Log.e(TAG, "❌ WebRTCClient is null!")
////                return false
////            }
////
//////            val callResult = webRTCClient!!.callUser(toUserId, callId)
//////            Log.d(TAG, "📞 WebRTCClient.callUser result: $callResult")
////
////            if (!callResult) {
////                isProcessingCall = false
////            }
////
////            return callResult
////        } catch (e: Exception) {
////            isProcessingCall = false
////            Log.e(TAG, "❌ Error making call: ${e.message}")
////            return false
////        }
////    }
//    // ИНИЦИАЦИЯ ВЫЗОВА
////    fun makeCall(toUserId: String, toUserName: String = ""): Boolean {
////        if (isProcessingCall) {
////            Log.w(TAG, "⚠️ Already processing a call, ignoring duplicate")
////            return false
////        }
////
////        val now = System.currentTimeMillis()
////        if (now - lastCallAttemptAt < 1000L) {
////            Log.w(TAG, "⏱️ Call throttled: less than 1s since last attempt")
////            return false
////        }
////
////        isProcessingCall = true
////        lastCallAttemptAt = now
////
////        try {
////            val callId = generateCallId()
////            val fromUserId = webRTCClient?.getCurrentUserId() ?: return false
////
////            Log.d(TAG, "📞 Making call to: $toUserId, callId: $callId")
////
////            val call = Call(
////                callId = callId,
////                fromUserId = fromUserId,
////                fromUserName = "Вы",
////                toUserId = toUserId,
////                status = CallStatus.INITIATING
////            )
////
////            _currentCall.value = call
////
////            if (webRTCClient == null) {
////                Log.e(TAG, "❌ WebRTCClient is null!")
////                return false
////            }
////
////            val callResult = webRTCClient!!.callUser(toUserId, callId)
////            Log.d(TAG, "📞 WebRTCClient.callUser result: $callResult")
////
////            if (!callResult) {
////                // Если вызов не удался, сбрасываем флаг
////                isProcessingCall = false
////            }
////
////            return callResult
////        } catch (e: Exception) {
////            isProcessingCall = false
////            Log.e(TAG, "❌ Error making call: ${e.message}")
////            return false
////        }
////    }
//
//    // ПРИНЯТИЕ ВХОДЯЩЕГО ВЫЗОВА
////    fun acceptIncomingCall() {
////        val call = _incomingCall.value ?: return
////        Log.d(TAG, "✅ Accepting incoming call: ${call.callId}")
////
////        webRTCClient?.acceptCall(call.fromUserId, call.callId)
////        _incomingCall.value = call.copy(status = CallStatus.ANSWERED)
////        _currentCall.value = _incomingCall.value
////        _incomingCall.value = null
////
////        // Обновляем UI
////        _callActions.value = CallActions.CallConnected
////    }
//
//    // ОТКЛОНЕНИЕ ВХОДЯЩЕГО ВЫЗОВА
////    fun rejectIncomingCall() {
////        val call = _incomingCall.value ?: return
////        Log.d(TAG, "❌ Rejecting incoming call: ${call.callId}")
////
////        webRTCClient?.rejectCall(call.fromUserId, call.callId)
////        _incomingCall.value = call.copy(status = CallStatus.REJECTED)
////        showCallEnded("Вызов отклонен")
////        _incomingCall.value = null
////    }
//
//    // ЗАВЕРШЕНИЕ ТЕКУЩЕГО ВЫЗОВА
////    fun endCurrentCall() {
////        val call = _currentCall.value ?: return
////        Log.d(TAG, "📞 Ending current call: ${call.callId}")
////
////        webRTCClient?.endCall()
////        _currentCall.value = call.copy(status = CallStatus.ENDED)
////        showCallEnded("Вызов завершен")
////        _currentCall.value = null
////        isProcessingCall = false
////    }
//
//    // ДОБАВЛЕННЫЕ МЕТОДЫ ДЛЯ ОБРАБОТКИ ВЫЗОВОВ
//
//    // Когда целевой пользователь принимает вызов (вызывается из WebSocket)
////    fun handleCallAccepted(answer: JSONObject) {
////        Log.d(TAG, "✅ Call accepted by remote user - updating UI only")
////
////        // ❗ УБРАНО: webRTCClient?.handleAnswer(answer) - WebRTCClient сам обработает answer через сокет
////
////        // Только обновление UI
////        _currentCall.value = _currentCall.value?.copy(status = CallStatus.ANSWERED)
////        _callActions.value = CallActions.CallConnected
//
////        Log.d(TAG, "📞 Вызов подключен - UI updated")
////    }
//
//    // Когда целевой пользователь отклоняет вызов (вызывается из WebSocket)
////    fun handleCallRejected() {
////        Log.d(TAG, "❌ Call rejected by remote user")
////
////        _currentCall.value = _currentCall.value?.copy(status = CallStatus.REJECTED)
////        showCallEnded("Вызов отклонен")
////        _currentCall.value = null
////        isProcessingCall = false
////
////        Log.d(TAG, "📞 Вызов отклонен")
////    }
//
////    // Показать завершение вызова
////    private fun showCallEnded(reason: String) {
////        _callActions.value = CallActions.CallEnded(reason)
////        Log.d(TAG, "📞 Call ended: $reason")
////        isProcessingCall = false
////    }
//
//    // ОБРАБОТКА ВХОДЯЩЕГО ВЫЗОВА ОТ СЕРВЕРА
////    fun handleIncomingCall(fromUserId: String, fromUserName: String, callId: String) {
////        Log.d(TAG, "📞 Incoming call from: $fromUserId, callId: $callId")
////
////        val call = Call(
////            callId = callId,
////            fromUserId = fromUserId,
////            fromUserName = fromUserName,
////            toUserId = webRTCClient?.getCurrentUserId() ?: "",
////            status = CallStatus.RINGING
////        )
////
////        _incomingCall.value = call
////        _callActions.value = CallActions.IncomingCall(call)
////    }
//
////    private fun setupWebRTCListeners() {
////        webRTCClient?.setListener(object : WebRTCClient.WebRTCListener {
////            override fun onIncomingCall(fromUserId: String, callId: String) {
////                val contactName = getContactNameById(fromUserId)
////                handleIncomingCall(fromUserId, contactName, callId)
////            }
////
////            override fun onCallAccepted(toUserId: String) {
////                Log.d(TAG, "✅ Call accepted by: $toUserId - WebRTC event")
////                _currentCall.value = _currentCall.value?.copy(status = CallStatus.ANSWERED)
////                _callActions.value = CallActions.CallConnected
////                isProcessingCall = false
////            }
////
////            override fun onCallRejected(fromUserId: String) {
////                Log.d(TAG, "❌ Call rejected by: $fromUserId - WebRTC event")
////                handleCallRejected()
////            }
////
////            override fun onCallEnded(fromUserId: String) {
////                Log.d(TAG, "📞 Call ended by: $fromUserId")
////                showCallEnded("Собеседник завершил вызов")
////                _currentCall.value = null
////                _incomingCall.value = null
////                isProcessingCall = false
////            }
////
////            override fun onCallWaiting(toUserId: String, message: String) {
////                _callActions.value = CallActions.CallWaiting(message)
////            }
////
////            override fun onCallDelivered(toUserId: String, callId: String) {
////                Log.d(TAG, "✅ Call delivered to: $toUserId")
////                _currentCall.value = _currentCall.value?.copy(status = CallStatus.RINGING)
////                _callActions.value = CallActions.CallRinging
////            }
////
////            override fun onCallFailed(error: String) {
////                _currentCall.value = _currentCall.value?.copy(status = CallStatus.FAILED)
////                showCallEnded("Ошибка вызова: $error")
////                isProcessingCall = false
////            }
////
////            override fun onCallTimeout() {
////                _currentCall.value = _currentCall.value?.copy(status = CallStatus.FAILED)
////                showCallEnded("Таймаут вызова")
////                isProcessingCall = false
////            }
////
////            override fun onSocketConnected() {}
////            override fun onSocketDisconnected() {}
////            override fun onRegistrationSuccess(userId: String) {}
////            override fun onRemoteStreamAdded(stream: org.webrtc.MediaStream) {
////                _callActions.value = CallActions.RemoteStreamAdded
////            }
////            override fun onLocalStreamAdded(stream: org.webrtc.MediaStream) {
////                _callActions.value = CallActions.LocalStreamAdded
////            }
////            override fun onIceConnectionStateChanged(state: org.webrtc.PeerConnection.IceConnectionState) {
////                _callActions.value = CallActions.IceStateChanged(state)
////            }
////        })
////    }
////
////    private fun getContactNameById(userId: String): String {
////        // TODO: Реализовать получение имени контакта из базы
////        return "Контакты" // Временная заглушка
////    }
////
////    private fun generateCallId(): String {
////        return "call_${UUID.randomUUID()}_${System.currentTimeMillis()}"
////    }
////
////    // Класс для действий в вызове
////    sealed class CallActions {
////        object CallConnected : CallActions()
////        object CallRinging : CallActions()
////        object LocalStreamAdded : CallActions()
////        object RemoteStreamAdded : CallActions()
////        data class CallWaiting(val message: String) : CallActions()
////        data class CallEnded(val reason: String) : CallActions()
////        data class IceStateChanged(val state: org.webrtc.PeerConnection.IceConnectionState) : CallActions()
////        data class MuteStateChanged(val isMuted: Boolean) : CallActions()
////        data class SpeakerStateChanged(val isSpeakerOn: Boolean) : CallActions()
////        data class IncomingCall(val call: Call) : CallActions()
////    }
////}
//
