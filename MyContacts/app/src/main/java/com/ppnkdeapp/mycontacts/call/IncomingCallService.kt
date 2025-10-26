    //// IncomingCallService.kt
//package com.ppnkdeapp.mycontacts.call
//
//import android.app.Service
//import android.content.Intent
//import android.os.IBinder
//import android.util.Log
//
//class IncomingCallService : Service() {
//
//    companion object {
//        const val ACTION_INCOMING_CALL = "com.ppnkdeapp.mycontacts.INCOMING_CALL"
//        const val EXTRA_CALLER_ID = "caller_id"
//        const val EXTRA_CALLER_NAME = "caller_name"
//        const val EXTRA_CALL_ID = "call_id"
//    }
//
//    private val callManager: CallManager by lazy { CallManager.getInstance(this) }
//
//    override fun onBind(intent: Intent?): IBinder? = null
//
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        when (intent?.action) {
//            ACTION_INCOMING_CALL -> {
//                val callerId = intent.getStringExtra(EXTRA_CALLER_ID) ?: return START_NOT_STICKY
//                val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
//                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: System.currentTimeMillis().toString()
//
//                handleIncomingCall(callId, callerId, callerName)
//            }
//        }
//        return START_NOT_STICKY
//    }
//
//    private fun handleIncomingCall(callId: String, callerId: String, callerName: String) {
//        val call = Call(
//            id = callId,
//            targetUserId = callerId,
//            callerName = callerName,
//            isOutgoing = false,
//            startTime = System.currentTimeMillis()
//        )
//
//        callManager.currentCall = call
//
//        // Передаем данные по отдельности вместо целого объекта
//        val broadcastIntent = Intent("com.ppnkdeapp.mycontacts.INCOMING_CALL_UI")
//        broadcastIntent.putExtra("call_id", callId)
//        broadcastIntent.putExtra("caller_id", callerId)
//        broadcastIntent.putExtra("caller_name", callerName)
//        broadcastIntent.putExtra("is_outgoing", false)
//        broadcastIntent.putExtra("start_time", System.currentTimeMillis())
//        sendBroadcast(broadcastIntent)
//
//        Log.d("IncomingCallService", "Incoming call from: $callerName")
//    }
//}