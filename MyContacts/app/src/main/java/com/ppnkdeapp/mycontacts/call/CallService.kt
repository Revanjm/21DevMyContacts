package com.ppnkdeapp.mycontacts.call

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ppnkdeapp.mycontacts.ContactsListScreen
import kotlin.jvm.java

class CallService : Service() {

    companion object {
        private const val TAG = "CallService"
        private const val NOTIFICATION_ID = 1234
        private const val CHANNEL_ID = "call_service_channel"

        fun startIncomingCall(context: Context, callId: String, callerId: String, callerName: String) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra("call_id", callId)
                putExtra("caller_id", callerId)
                putExtra("caller_name", callerName)
                putExtra("is_incoming", true)
                action = "INCOMING_CALL"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startOutgoingCall(context: Context, callId: String, targetUserId: String, targetName: String) {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra("call_id", callId)
                putExtra("target_user_id", targetUserId)
                putExtra("target_name", targetName)
                putExtra("is_incoming", false)
                action = "OUTGOING_CALL"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManager
    private var currentCallId: String? = null
    private var currentCallerName: String = ""

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallService created")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CallService onStartCommand: ${intent?.action}")

        intent?.let {
            when (it.action) {
                "INCOMING_CALL" -> {
                    currentCallId = it.getStringExtra("call_id")
                    val callerId = it.getStringExtra("caller_id")
                    currentCallerName = it.getStringExtra("caller_name") ?: "Неизвестный"
                    showIncomingCallNotification(currentCallId, callerId, currentCallerName)
                }
                "OUTGOING_CALL" -> {
                    currentCallId = it.getStringExtra("call_id")
                    val targetUserId = it.getStringExtra("target_user_id")
                    currentCallerName = it.getStringExtra("target_name") ?: "Неизвестный"
                    showOutgoingCallNotification(currentCallId, targetUserId, currentCallerName)
                }
                else -> {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих и активных звонках"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun showIncomingCallNotification(callId: String?, callerId: String?, callerName: String) {
        // Intent для принятия вызова
        val acceptIntent = Intent(this, CallActionReceiver::class.java).apply {
            putExtra("call_id", callId)
            putExtra("caller_id", callerId)
            putExtra("action", "accept")
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent для отклонения вызова
        val rejectIntent = Intent(this, CallActionReceiver::class.java).apply {
            putExtra("call_id", callId)
            putExtra("caller_id", callerId)
            putExtra("action", "reject")
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent для открытия приложения
        val appIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("call_id", callId)
            putExtra("caller_id", callerId)
            putExtra("contact_name", callerName)
            putExtra("is_incoming", true) // ⚠️ ВАЖНО!
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            2,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Входящий звонок")
            .setContentText("$callerName звонит вам")
            .setSmallIcon(android.R.drawable.sym_call_incoming) // Иконка входящего звонка
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(appPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                android.R.drawable.button_onoff_indicator_on, // Иконка принятия
                "Принять",
                acceptPendingIntent
            )
            .addAction(
                android.R.drawable.button_onoff_indicator_off, // Иконка отклонения
                "Отклонить",
                rejectPendingIntent
            )
            .setContentIntent(appPendingIntent)
            .build()

        // Для Android 14+ (API 34+) нужно указывать тип foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Incoming call notification shown for: $callerName")
    }

    @SuppressLint("ForegroundServiceType")
    private fun showOutgoingCallNotification(callId: String?, targetUserId: String?, targetName: String) {
        val appIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            3,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Исходящий звонок")
            .setContentText("Звонок $targetName...")
            .setSmallIcon(android.R.drawable.sym_call_outgoing) // Иконка исходящего звонка
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .build()

        // Для Android 14+ (API 34+) нужно указывать тип foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Outgoing call notification shown for: $targetName")
    }

    fun updateToActiveCallNotification(callerName: String) {
        val appIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            4,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Активный звонок")
            .setContentText("Разговор с $callerName")
            .setSmallIcon(android.R.drawable.sym_call_incoming) // Иконка активного звонка
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Updated to active call notification: $callerName")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallService destroyed")
        stopForeground(true)
        notificationManager.cancel(NOTIFICATION_ID)
    }
}