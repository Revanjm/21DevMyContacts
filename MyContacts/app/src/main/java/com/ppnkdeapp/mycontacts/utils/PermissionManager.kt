// PermissionManager.kt
package com.ppnkdeapp.mycontacts.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionManager {

    companion object {
        fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.WAKE_LOCK,
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.WAKE_LOCK,
                    Manifest.permission.FOREGROUND_SERVICE
                )
            }
        }

        fun hasAllPermissions(context: Context): Boolean {
            return getRequiredPermissions().all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun requestPermissions(activity: Activity, requestCode: Int) {
            ActivityCompat.requestPermissions(activity, getRequiredPermissions(), requestCode)
        }

        fun shouldShowRationale(activity: Activity): Boolean {
            return getRequiredPermissions().any { permission ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            }
        }

        fun getMissingPermissions(context: Context): List<String> {
            return getRequiredPermissions().filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            }
        }

        // Специальный метод для проверки разрешения на уведомления
        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                // Для Android 12 и ниже разрешение на уведомления не требуется
                true
            }
        }
    }
}