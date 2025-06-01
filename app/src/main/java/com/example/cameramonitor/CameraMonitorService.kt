package com.example.cameramonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.hardware.camera2.CameraManager
import android.app.AppOpsManager
import android.app.AppOpsManager.OnOpChangedListener

/**
 * CameraMonitorService отслеживает использование камеры в реальном времени.
 */
class CameraMonitorService : Service() {

    private lateinit var cameraManager: CameraManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Камера не используется"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOpsManager.startWatchingMode(AppOpsManager.OPSTR_CAMERA, null, object : OnOpChangedListener {
                override fun onOpChanged(op: String?, packageName: String?) {
                    if (op == AppOpsManager.OPSTR_CAMERA) {
                        val status = "Камера используется"
                        updateNotification(status, packageName ?: "Неизвестно")
                    }
                }
            })
        } else {
            cameraManager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) {
                    updateNotification("Камера доступна", "—")
                }

                override fun onCameraUnavailable(cameraId: String) {
                    updateNotification("Камера используется", "—")
                }
            }, null)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Camera Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CameraMonitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String, packageName: String) {
        val notification = buildNotification("$status (App: $packageName)")
        notificationManager.notify(NOTIFICATION_ID, notification)

        val intent = Intent(MainActivity.ACTION_CAMERA_STATUS_CHANGED).apply {
            putExtra(MainActivity.EXTRA_STATUS, status)
            putExtra(MainActivity.EXTRA_PACKAGE, packageName)
        }
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "camera_monitor_channel"
        private const val NOTIFICATION_ID = 1
    }
}
