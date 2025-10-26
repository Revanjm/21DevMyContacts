package com.ppnkdeapp.mycontacts

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class PushNotificationServer(private val context: Context) {
    private val TAG = "PushNotificationServer"
    private var isRunning = false
    private var port = 8080
    private var executor: ScheduledExecutorService? = null
    
    companion object {
        private var instance: PushNotificationServer? = null
        
        fun getInstance(context: Context): PushNotificationServer {
            if (instance == null) {
                instance = PushNotificationServer(context.applicationContext)
            }
            return instance!!
        }
    }
    
    fun startServer(port: Int = 8080): Boolean {
        if (isRunning) {
            Log.w(TAG, "🚫 Server is already running")
            return true
        }
        
        try {
            this.port = port
            executor = Executors.newScheduledThreadPool(2)
            isRunning = true
            
            Log.d(TAG, "🚀 Push notification server started (simulated)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start push notification server", e)
            return false
        }
    }
    
    fun stopServer() {
        if (!isRunning) {
            Log.w(TAG, "🚫 Server is not running")
            return
        }
        
        try {
            executor?.shutdown()
            executor = null
            isRunning = false
            Log.d(TAG, "🛑 Push notification server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping push notification server", e)
        }
    }
    
    fun isServerRunning(): Boolean = isRunning
    
    fun getServerEndpoint(): String {
        // Возвращаем URL для регистрации на сервере
        return "http://localhost:$port/push-notification"
    }
    
    // Метод для обработки push-уведомлений (вызывается из MyApp)
    fun handlePushNotification(
        type: String,
        callId: String,
        fromUserId: String,
        contactName: String,
        timestamp: String,
        callData: JSONObject?
    ) {
        try {
            Log.d(TAG, "📲 Processing push notification: $type for call $callId from $fromUserId")
            
            if (type == "incoming_call") {
                Log.d(TAG, "📞 Processing incoming call push notification: $callId from $fromUserId")
                
                // ❌ ОТКЛЮЧЕНО: Уведомляем MyApp о входящем звонке - используем только ConnectionService polling
                // val app = context.applicationContext as? MyApp
                // app?.let { myApp ->
                //     myApp.handleIncomingCallPush(
                //         callId = callId,
                //         fromUserId = fromUserId,
                //         contactName = contactName,
                //         timestamp = timestamp,
                //         callData = callData
                //     )
                // }
            } else {
                Log.w(TAG, "⚠️ Unknown push notification type: $type")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing push notification", e)
        }
    }
}
