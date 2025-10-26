// CallBroadcastReceiver.kt
package com.ppnkdeapp.mycontacts.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.util.Log

class CallBroadcastReceiver : BroadcastReceiver() {

    interface CallReceiverListener {
        fun onIncomingCallReceived(call: Call)
//        fun onCallStateChanged(call: Call, state: CallState)
    }

    companion object {
        private var listener: CallReceiverListener? = null

        fun setListener(callListener: CallReceiverListener) {
            listener = callListener
        }

        fun removeListener() {
            listener = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.ppnkdeapp.mycontacts.INCOMING_CALL_UI" -> {
                val call = intent.getSerializableExtra("call") as? Call
                call?.let {
//                    Log.d("CallBroadcastReceiver", "Incoming call received: ${it.callerName}")
                    listener?.onIncomingCallReceived(it)
                }
            }
        }
    }
}