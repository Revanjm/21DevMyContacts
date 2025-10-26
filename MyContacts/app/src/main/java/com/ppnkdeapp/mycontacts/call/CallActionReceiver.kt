package com.ppnkdeapp.mycontacts.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ppnkdeapp.mycontacts.call.CallActivity
import com.ppnkdeapp.mycontacts.MyApp

class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action")
        val callId = intent.getStringExtra("call_id")
        val callerId = intent.getStringExtra("caller_id")

        Log.d(TAG, "Call action received: $action for call: $callId")

        when (action) {
            "accept" -> {
                // Получаем имя контакта из MyApp
                val app = context.applicationContext as? MyApp
                val contactName = callerId?.let { callerId -> 
                    app?.getContactName(callerId) ?: callerId
                } ?: "Неизвестный"
                
                Log.d(TAG, "Opening CallActivity for caller: $contactName (ID: $callerId)")
                
                // Запускаем CallActivity для принятия вызова
                val callIntent = Intent(context, CallActivity::class.java).apply {
                    putExtra("call_id", callId)
                    putExtra("caller_id", callerId)
                    putExtra("is_incoming", true)
                    putExtra("contact_name", contactName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(callIntent)
            }
            "reject" -> {
                Log.d(TAG, "Rejecting call: callId=$callId, callerId=$callerId")
                
                // Отклоняем вызов напрямую через WebRTCClient
                val app = context.applicationContext as? MyApp
                if (callId != null && callerId != null && app?.isWebRTCInitialized() == true) {
//                    app.getWebRTCClient().rejectCall(callerId, callId)
                    Log.d(TAG, "✅ Reject signal sent to server")
                } else {
                    Log.e(TAG, "❌ Cannot reject call: missing data or WebRTC not initialized")
                }
                
                // Останавливаем сервис и очищаем данные
                CallService.stopService(context)
                app?.clearCallData()
            }
        }
    }
}