package com.ppnkdeapp.mycontacts.models

data class Call(
    val callId: String,
    val fromUserId: String,
    val fromUserName: String,
    val toUserId: String,
    val status: CallStatus,
    val timestamp: Long = System.currentTimeMillis()
)

enum class CallStatus {
    INITIATING,    // Вызов инициирован
    RINGING,       // Звонок поступает
    ANSWERED,      // Принят
    REJECTED,      // Отклонен
    ENDED,         // Завершен
    FAILED         // Ошибка
}