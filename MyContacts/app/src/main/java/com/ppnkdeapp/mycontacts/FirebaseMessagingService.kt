package com.ppnkdeapp.mycontacts

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ppnkdeapp.mycontacts.call.ConnectionService
import org.json.JSONObject

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FirebaseMessaging"

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "📲 Received WebPush message: ${remoteMessage.from}")
        Log.d(TAG, "📲 Message data: ${remoteMessage.data}")

        // Обрабатываем данные уведомления
        val data = remoteMessage.data
        val type = data["type"]

        when (type) {
            "actual_call_update" -> {
                Log.d(TAG, "📞 Processing ActualCall notification")
                handleActualCallUpdate(data)
            }
            "connection_established" -> {
                Log.d(TAG, "✅ Connection established notification")
                handleConnectionEstablished(data)
            }
            else -> {
                Log.w(TAG, "⚠️ Unknown notification type: $type")
                // Попробуем обработать как ActualCall если есть callId
                if (data.containsKey("callId")) {
                    Log.d(TAG, "📞 Processing as ActualCall based on callId presence")
                    handleActualCallUpdate(data)
                }
            }
        }
    }

    private fun handleActualCallUpdate(data: Map<String, String>) {
        try {
            Log.d(TAG, "📞 Processing ActualCall data: $data")
            
            // Создаем JSON объект с данными ActualCall
            val notificationData = JSONObject().apply {
                put("type", "actual_call_update")
                put("actualCall", JSONObject().apply {
                    // Извлекаем данные ActualCall из FCM data
                    put("callId", data["callId"] ?: "")
                    put("callerId", data["callerId"] ?: "")
                    put("recipientId", data["recipientId"] ?: "")
                    put("status", data["status"] ?: "")
                    put("step", data["step"] ?: "")
                    put("createdAt", data["createdAt"]?.toLongOrNull() ?: 0L)
                    put("offerSdp", data["offerSdp"] ?: "")
                    put("answerSdp", data["answerSdp"] ?: "")
                })
            }
            
            Log.d(TAG, "📞 Created notification data: ${notificationData.toString()}")
            
            // Передаем данные в ConnectionService для обработки
            val intent = Intent(this, ConnectionService::class.java)
            intent.putExtra("action", "handle_push_notification")
            intent.putExtra("notification_data", notificationData.toString())
            startService(intent)
            
            Log.d(TAG, "✅ ActualCall data forwarded to ConnectionService")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling ActualCall update: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleConnectionEstablished(data: Map<String, String>) {
        Log.d(TAG, "✅ Connection established notification received")
        // Можно добавить дополнительную логику при необходимости
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "🔄 FCM token refreshed: ${token.take(20)}...")
        
        // Здесь можно отправить новый токен на сервер
        // Но пока что мы используем mock подписки
    }
}