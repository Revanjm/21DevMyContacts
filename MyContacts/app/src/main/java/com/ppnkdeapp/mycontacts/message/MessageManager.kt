package com.ppnkdeapp.mycontacts.message

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.*
import kotlin.collections.HashMap

class MessageManager(
    private val context: Context,
    private val serverUrl: String,
    private val userId: String

) {
        private var socket: Socket? = null
        private var isConnected = false
        private var isRegistered = false

        private val messageListeners = mutableListOf<MessageListener>()
        private val chatHistoryListeners = mutableListOf<ChatHistoryListener>()
        private val connectionListeners = mutableListOf<ConnectionListener>()

        companion object {
            private const val TAG = "MessageManager"
        }

        interface MessageListener {
            fun onNewMessageReceived(message: ChatMessage)
            fun onMessageSent(message: ChatMessage)
            fun onMessageFailed(error: String)
        }

        interface ChatHistoryListener {
            fun onChatHistoryLoaded(chatId: String, messages: List<ChatMessage>)
            fun onChatHistoryError(chatId: String, error: String)
        }

        interface ConnectionListener {
            fun onConnected()
            fun onDisconnected()
            fun onConnectionError(error: String)
            fun onRegistrationSuccess(userId: String)
        }

        data class ChatMessage(
            val chatId: String,
            val messageId: String,
            val body: String,
            val fromUserId: String,
            val toUserId: String?,
            val timeSend: String,
            val timestamp: String,
            val status: String = "sent"
        )

        fun initialize() {
            // ❌ УБРАНО: Автоматическое подключение к WebSocket
            // WebSocket подключается только при необходимости
            Log.d(TAG, "ℹ️ MessageManager initialized - WebSocket will connect when needed")
        }
        
        fun connectForMessaging() {
            if (socket?.connected() == true) {
                Log.d(TAG, "✅ MessageManager WebSocket already connected")
                return
            }
            
            try {
                val opts = IO.Options().apply {
                    forceNew = true
                    reconnection = false      // ❌ ОТКЛЮЧЕНО: Автоматическое переподключение
                    reconnectionAttempts = 0 // ❌ ОТКЛЮЧЕНО: Попытки переподключения
                    reconnectionDelay = 0     // ❌ ОТКЛЮЧЕНО: Задержка переподключения
                    reconnectionDelayMax = 0 // ❌ ОТКЛЮЧЕНО: Максимальная задержка
                    timeout = 20000
                }

                socket = IO.socket(serverUrl, opts)
                setupSocketListeners()
                socket?.connect()

                Log.d(TAG, "🔌 MessageManager connecting to: $serverUrl")
            } catch (e: Exception) {
                Log.e(TAG, "❌ MessageManager connection error: ${e.message}")
                notifyConnectionError("Connection failed: ${e.message}")
            }
        }

        private fun setupSocketListeners() {
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "✅ MessageManager socket connected")
                isConnected = true
                notifyConnected()
                registerUser()
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "🔴 MessageManager socket disconnected")
                isConnected = false
                isRegistered = false
                notifyDisconnected()
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = args.joinToString(", ")
                Log.e(TAG, "❌ MessageManager connection error: $error")
                notifyConnectionError(error)
            }

            socket?.on("registration_success") { args ->
                try {
                    val data = args[0] as? JSONObject
                    data?.let {
                        val registeredUserId = it.optString("userId")
                        Log.d(TAG, "✅ MessageManager registration successful: $registeredUserId")
                        isRegistered = true
                        notifyRegistrationSuccess(registeredUserId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error parsing registration_success: ${e.message}")
                }
            }

            socket?.on("new_message") { args ->
                try {
                    val data = args[0] as? JSONObject
                    data?.let {
                        val message = parseMessageFromJson(it)
                        Log.d(TAG, "📨 New message received in chat ${message.chatId}: ${message.body.take(50)}...")
                        notifyNewMessageReceived(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error parsing new_message: ${e.message}")
                }
            }

            socket?.on("message_sent") { args ->
                try {
                    val data = args[0] as? JSONObject
                    data?.let {
                        val message = parseMessageFromJson(it)
                        Log.d(TAG, "✅ Message sent successfully to chat ${message.chatId}")
                        notifyMessageSent(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error parsing message_sent: ${e.message}")
                }
            }

            socket?.on("message_failed") { args ->
                try {
                    val data = args[0] as? JSONObject
                    data?.let {
                        val error = it.optString("error", "Unknown error")
                        Log.e(TAG, "❌ Message failed: $error")
                        notifyMessageFailed(error)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error parsing message_failed: ${e.message}")
                }
            }

            socket?.on("chat_history_loaded") { args ->
                try {
                    val data = args[0] as? JSONObject
                    data?.let {
                        val chatId = it.optString("chat_id")
                        val messagesArray = it.optJSONArray("messages")
                        val messages = parseMessagesFromJsonArray(messagesArray)

                        Log.d(TAG, "📂 Chat history loaded for $chatId: ${messages.size} messages")
                        notifyChatHistoryLoaded(chatId, messages)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error parsing chat_history_loaded: ${e.message}")
                }
            }

            socket?.on("chat_history_error") { args ->
                try {
                    val data = args[0] as? JSONObject
                    data?.let {
                        val chatId = it.optString("chat_id")
                        val error = it.optString("error", "Unknown error")
                        Log.e(TAG, "❌ Chat history error for $chatId: $error")
                        notifyChatHistoryError(chatId, error)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error parsing chat_history_error: ${e.message}")
                }
            }
        }

        // 📤 ОСНОВНЫЕ МЕТОДЫ ДЛЯ ОТПРАВКИ СООБЩЕНИЙ

        /**
         * Отправка сообщения через WebSocket (рекомендуется для онлайн)
         */
        fun getCurrentUserId(): String {
            return userId
        }
        fun sendMessage(chatId: String, messageText: String, toUserId: String): Boolean {
            if (!isConnected || !isRegistered) {
                Log.e(TAG, "❌ Cannot send message: not connected or not registered")
                notifyMessageFailed("Not connected to server")
                return false
            }

            return try {
                val messageData = JSONObject().apply {
                    put("chat_id", chatId)
                    put("time_send", System.currentTimeMillis().toString())
                    put("body", messageText)
                    put("toUserId", toUserId)
                }

                emitSafe("send_message", messageData)
                Log.d(TAG, "💬 Message sent to $toUserId in chat $chatId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending message: ${e.message}")
                notifyMessageFailed("Send failed: ${e.message}")
                false
            }
        }

        /**
         * Отправка сообщения через HTTP (fallback метод)
         */
        suspend fun sendMessageViaHttp(
            chatId: String,
            messageText: String,
            toUserId: String
        ): Boolean {
            return try {
                val client = okhttp3.OkHttpClient()
                val requestBody = JSONObject().apply {
                    put("chat_id", chatId)
                    put("time_send", System.currentTimeMillis().toString())
                    put("body", messageText)
                    put("fromUserId", userId)
                    put("toUserId", toUserId)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = okhttp3.Request.Builder()
                    .url("$serverUrl/messages/send")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val isSuccess = response.isSuccessful

                if (isSuccess) {
                    Log.d(TAG, "✅ Message sent via HTTP to $toUserId")
                } else {
                    Log.e(TAG, "❌ HTTP message send failed: ${response.code}")
                }

                isSuccess
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending message via HTTP: ${e.message}")
                false
            }
        }

        /**
         * Загрузка истории чата
         */
        fun loadChatHistory(chatId: String): Boolean {
            if (!isConnected || !isRegistered) {
                Log.e(TAG, "❌ Cannot load chat history: not connected")
                return false
            }

            return try {
                val requestData = JSONObject().apply {
                    put("chat_id", chatId)
                }

                emitSafe("load_chat_history", requestData)
                Log.d(TAG, "📂 Loading chat history for: $chatId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading chat history: ${e.message}")
                false
            }
        }

        /**
         * Загрузка истории чата через HTTP
         */
        suspend fun loadChatHistoryViaHttp(chatId: String): List<ChatMessage> {
            return try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url("$serverUrl/messages/$chatId")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(responseBody)

                    if (jsonResponse.optString("status") == "Success") {
                        val messagesArray = jsonResponse.optJSONArray("messages")
                        parseMessagesFromJsonArray(messagesArray).also {
                            Log.d(TAG, "✅ Loaded ${it.size} messages via HTTP for chat $chatId")
                        }
                    } else {
                        Log.e(TAG, "❌ Server error loading chat history")
                        emptyList()
                    }
                } else {
                    Log.e(TAG, "❌ HTTP error loading chat history: ${response.code}")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading chat history via HTTP: ${e.message}")
                emptyList()
            }
        }

        /**
         * Получение списка всех чатов
         */
        suspend fun getAllChats(): List<ChatInfo> {
            return try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder()
                    .url("$serverUrl/chats")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(responseBody)

                    if (jsonResponse.optString("status") == "Success") {
                        val chatsArray = jsonResponse.optJSONArray("chats")
                        parseChatsFromJsonArray(chatsArray).also {
                            Log.d(TAG, "✅ Loaded ${it.size} chats via HTTP")
                        }
                    } else {
                        Log.e(TAG, "❌ Server error loading chats")
                        emptyList()
                    }
                } else {
                    Log.e(TAG, "❌ HTTP error loading chats: ${response.code}")
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading chats via HTTP: ${e.message}")
                emptyList()
            }
        }

        // 🔧 СЛУЖЕБНЫЕ МЕТОДЫ

        private fun registerUser() {
            try {
                socket?.emit("register", userId)
                Log.d(TAG, "👤 Registering user with MessageManager: $userId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error registering user: ${e.message}")
            }
        }

        private fun emitSafe(event: String, data: JSONObject) {
            try {
                if (socket?.connected() == true) {
                    socket?.emit(event, data)
                    Log.d(TAG, "📤 Emitted $event: ${data.toString().take(100)}...")
                } else {
                    Log.w(TAG, "⚠️ Socket not connected, cannot emit $event")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error emitting $event: ${e.message}")
            }
        }

        private fun parseMessageFromJson(json: JSONObject): ChatMessage {
            return ChatMessage(
                chatId = json.optString("chat_id"),
                messageId = json.optString("messageId", generateMessageId()),
                body = json.optString("body"),
                fromUserId = json.optString("fromUserId"),
                toUserId = json.optString("toUserId"),
                timeSend = json.optString("time_send"),
                timestamp = json.optString("timestamp", System.currentTimeMillis().toString()),
                status = json.optString("status", "received")
            )
        }

        private fun parseMessagesFromJsonArray(jsonArray: org.json.JSONArray?): List<ChatMessage> {
            val messages = mutableListOf<ChatMessage>()

            jsonArray?.let { array ->
                for (i in 0 until array.length()) {
                    try {
                        val messageJson = array.getJSONObject(i)
                        messages.add(parseMessageFromJson(messageJson))
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing message at index $i: ${e.message}")
                    }
                }
            }

            return messages.sortedBy { it.timeSend.toLongOrNull() ?: 0 }
        }

        private fun parseChatsFromJsonArray(jsonArray: org.json.JSONArray?): List<ChatInfo> {
            val chats = mutableListOf<ChatInfo>()

            jsonArray?.let { array ->
                for (i in 0 until array.length()) {
                    try {
                        val chatJson = array.getJSONObject(i)
                        val lastMessageJson = chatJson.optJSONObject("last_message")

                        chats.add(ChatInfo(
                            chatId = chatJson.optString("chat_id"),
                            messageCount = chatJson.optInt("message_count", 0),
                            lastMessage = lastMessageJson?.let {
                                LastMessage(
                                    body = it.optString("body"),
                                    timeSend = it.optString("time_send"),
                                    fromUserId = it.optString("fromUserId")
                                )
                            },
                            createdAt = chatJson.optString("created_at")
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing chat at index $i: ${e.message}")
                    }
                }
            }

            return chats.sortedByDescending { it.lastMessage?.timeSend?.toLongOrNull() ?: 0 }
        }

        private fun generateMessageId(): String {
            return "msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}"
        }

        // 👂 МЕТОДЫ ДЛЯ РЕГИСТРАЦИИ СЛУШАТЕЛЕЙ

        fun addMessageListener(listener: MessageListener) {
            messageListeners.add(listener)
        }

        fun removeMessageListener(listener: MessageListener) {
            messageListeners.remove(listener)
        }

        fun addChatHistoryListener(listener: ChatHistoryListener) {
            chatHistoryListeners.add(listener)
        }

        fun removeChatHistoryListener(listener: ChatHistoryListener) {
            chatHistoryListeners.remove(listener)
        }

        fun addConnectionListener(listener: ConnectionListener) {
            connectionListeners.add(listener)
        }

        fun removeConnectionListener(listener: ConnectionListener) {
            connectionListeners.remove(listener)
        }

        // 🔔 УВЕДОМЛЕНИЯ ДЛЯ СЛУШАТЕЛЕЙ

        private fun notifyNewMessageReceived(message: ChatMessage) {
            messageListeners.forEach { listener ->
                try {
                    listener.onNewMessageReceived(message)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in message listener: ${e.message}")
                }
            }
        }

        private fun notifyMessageSent(message: ChatMessage) {
            messageListeners.forEach { listener ->
                try {
                    listener.onMessageSent(message)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in message listener: ${e.message}")
                }
            }
        }

        private fun notifyMessageFailed(error: String) {
            messageListeners.forEach { listener ->
                try {
                    listener.onMessageFailed(error)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in message listener: ${e.message}")
                }
            }
        }

        private fun notifyChatHistoryLoaded(chatId: String, messages: List<ChatMessage>) {
            chatHistoryListeners.forEach { listener ->
                try {
                    listener.onChatHistoryLoaded(chatId, messages)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in chat history listener: ${e.message}")
                }
            }
        }

        private fun notifyChatHistoryError(chatId: String, error: String) {
            chatHistoryListeners.forEach { listener ->
                try {
                    listener.onChatHistoryError(chatId, error)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in chat history listener: ${e.message}")
                }
            }
        }

        private fun notifyConnected() {
            connectionListeners.forEach { listener ->
                try {
                    listener.onConnected()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in connection listener: ${e.message}")
                }
            }
        }

        private fun notifyDisconnected() {
            connectionListeners.forEach { listener ->
                try {
                    listener.onDisconnected()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in connection listener: ${e.message}")
                }
            }
        }

        private fun notifyConnectionError(error: String) {
            connectionListeners.forEach { listener ->
                try {
                    listener.onConnectionError(error)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in connection listener: ${e.message}")
                }
            }
        }

        private fun notifyRegistrationSuccess(userId: String) {
            connectionListeners.forEach { listener ->
                try {
                    listener.onRegistrationSuccess(userId)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in connection listener: ${e.message}")
                }
            }
        }

        // 🛠️ УТИЛИТЫ

        fun isConnected(): Boolean = isConnected && isRegistered

        fun disconnect() {
            Log.d(TAG, "🔌 Disconnecting MessageManager")
            try {
                socket?.disconnect()
                socket?.off()
                socket = null
                isConnected = false
                isRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during disconnect: ${e.message}")
            }
        }

        fun reconnect() {
            Log.d(TAG, "🔄 Reconnecting MessageManager...")
            disconnect()
            initialize()
        }

        // 📊 МОДЕЛИ ДАННЫХ

        data class ChatInfo(
            val chatId: String,
            val messageCount: Int,
            val lastMessage: LastMessage?,
            val createdAt: String?
        )

        data class LastMessage(
            val body: String,
            val timeSend: String,
            val fromUserId: String
        )
}


// Extension function для удобства
fun String.toRequestBody(mediaType: okhttp3.MediaType): okhttp3.RequestBody {
    return okhttp3.RequestBody.create(mediaType, this)
}