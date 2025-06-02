package com.example.cameramonitor

import android.Manifest
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop:  Button
    private lateinit var tvLog:    TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Лямбда, принимающая (camera, status, pkg)
    private val cameraReceiver = CameraBroadcastReceiver { camera, status, pkg ->
        tvLog.append("Ваш start\n")
        appendLog(camera, status, pkg)
        tvLog.append("Ваш end\n")
        sendCameraNotification(camera, status, pkg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Проверяем runtime-разрешение POST_NOTIFICATIONS (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_CODE_POST_NOTIF
                )
            }
        }

        // 1) Инициализируем UI
        btnStart = findViewById(R.id.btnStart)
        btnStop  = findViewById(R.id.btnStop)
        tvLog    = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()

        btnStart.setOnClickListener { startClicked() }
        btnStop.setOnClickListener {
            stopService(Intent(this, CameraMonitorService::class.java))
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
        }

        // 2) Создаём канал уведомлений (Android 8+)
        createNotificationChannel()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(cameraReceiver, CameraBroadcastReceiver.intentFilter())
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(cameraReceiver)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE_POST_NOTIF) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "Без разрешения на уведомления пуши не будут отображаться",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /* ----------------- Private helpers ----------------- */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = CHANNEL_ID
            val name = "Camera Monitor Alerts"
            val descriptionText = "Уведомления о событиях использования камеры"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Запускаем Service, если есть разрешение Usage Access */
    private fun startClicked() {
        if (hasUsagePermission()) {
            val svc = Intent(this, CameraMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)

            btnStart.isEnabled = false
            btnStop.isEnabled  = true
        } else {
            askUsageAccess()
        }
    }

    /** Проверяем разрешение через AppOpsManager */
    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Диалог + переход в Settings.ACTION_USAGE_ACCESS_SETTINGS */
    private fun askUsageAccess() {
        AlertDialog.Builder(this)
            .setTitle("Требуется доступ «Usage access»")
            .setMessage(
                "Без него приложение не сможет определить, какое приложение использует камеру.\n" +
                "Нажмите «Открыть» и включите доступ для CameraMonitor."
            )
            .setPositiveButton("Открыть") { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Логируем в TextView + автоскролл вниз */
    private fun appendLog(camera: String, status: String, pkg: String) {
        val line = "[${timeFmt.format(System.currentTimeMillis())}] $camera: $status  ← $pkg\n"
        tvLog.append(line)

        val layout = tvLog.layout ?: return
        val diff = layout.getLineBottom(tvLog.lineCount - 1) - tvLog.height
        if (diff > 0) tvLog.scrollTo(0, diff)
    }

    /**
     * Посылаем уведомление через NotificationManager
     * @param camera  – например, "Front" / "Back"
     * @param status  – "Открыл камеру" / "Закрыл камеру"
     * @param pkg     – package name приложения
     */
    private fun sendCameraNotification(camera: String, status: String, pkg: String) {
        // Пытаемся получить человекочитаемое имя приложения
        val appName = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }

        val title = "Камера: $camera"
        val text = "Приложение \"$appName\" $status"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            val notificationId = System.currentTimeMillis().toInt()
            notify(notificationId, builder.build())
        }
    }

    companion object {
        private const val CHANNEL_ID = "camera_monitor_channel"
        private const val REQ_CODE_POST_NOTIF = 1001

        const val ACTION_CAMERA_STATUS_CHANGED = "com.example.cameramonitor.CAMERA_STATUS_CHANGED"
        const val EXTRA_STATUS  = "extra_status"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_CAMERA  = "extra_camera"
    }
}
