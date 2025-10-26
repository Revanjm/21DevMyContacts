// SignalClient.kt
package com.ppnkdeapp.mycontacts.call

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class SignalClient {

    companion object {
        const val SERVER_URL = "https://8f791583-9af0-4ccb-b92f-170da7902e03-00-271lvl54jws4c.sisko.replit.dev"
    }

    interface ConnectionCallback {
        fun onResult(result: String) // "Success" или "Failed"
    }

    private var socket: Socket? = null

    fun connectToSignalingServer(userId: UInt, callback: ConnectionCallback) { // Изменено на UInt
        try {
            val options = IO.Options().apply {
                forceNew = true
                reconnection = false
                timeout = 10000
            }

            socket = IO.socket(SERVER_URL, options).apply {
                // Обработчик успешного подключения
                on(Socket.EVENT_CONNECT) {
                    Log.d("SignalClient", "✅ Socket connected, registering user: $userId")
                    // После подключения регистрируем пользователя
                    emit("register", userId.toString()) // Конвертируем UInt в String для сервера
                }

                // Обработчик подтверждения регистрации
                on("registered") { args ->
                    try {
                        val data = args[0] as JSONObject
                        val success = data.getBoolean("success")
                        val registeredUserId = data.getString("userId")

                        Log.d("SignalClient", "✅ User $registeredUserId successfully registered on server")

                        if (success) {
                            callback.onResult("Success")
                        } else {
                            callback.onResult("Failed")
                        }
                    } catch (e: Exception) {
                        Log.e("SignalClient", "❌ Error processing registration response: ${e.message}")
                        callback.onResult("Failed")
                    }
                }

                // Обработчик ошибок подключения
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val errorMessage = args.joinToString()
                    Log.e("SignalClient", "❌ Connection error: $errorMessage")
                    disconnect()
                    callback.onResult("Failed")
                }

                // Обработчик таймаута подключения
//                on(Socket.EVENT_CONNECT_ERROR) { args ->
//                    Log.e("SignalClient", "❌ Connection timeout")
//                    disconnect()
//                    callback.onResult("Failed")
//                }

                // Обработчик общих ошибок
                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val errorMessage = args.joinToString()
                    Log.e("SignalClient", "❌ Socket error: $errorMessage")
                    disconnect()
                    callback.onResult("Failed")
                }

                // Обработчик отключения
                on(Socket.EVENT_DISCONNECT) {
                    Log.d("SignalClient", "🔌 Socket disconnected")
                }
            }

            // Подключаемся к серверу
            socket?.connect()
            Log.d("SignalClient", "🔗 Connection attempt started for user: $userId")

        } catch (e: URISyntaxException) {
            Log.e("SignalClient", "❌ URI syntax error: ${e.message}")
            callback.onResult("Failed")
        } catch (e: Exception) {
            Log.e("SignalClient", "❌ General error: ${e.message}")
            callback.onResult("Failed")
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
        Log.d("SignalClient", "🔌 SignalClient disconnected")
    }

    fun isConnected(): Boolean {
        return socket?.connected() == true
    }
}