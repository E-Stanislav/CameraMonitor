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
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop:  Button
    private lateinit var tvLog:    TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val CHANNEL_ID = "camera_monitor_channel"
        private const val REQ_CODE_POST_NOTIF = 1001

        const val ACTION_CAMERA_STATUS_CHANGED = "com.example.cameramonitor.CAMERA_STATUS_CHANGED"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_CAMERA = "extra_camera"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_SOURCE_DIR = "extra_source_dir"
    }

    // Лямбда, принимающая (camera, status, pkg)
    private val cameraReceiver = CameraBroadcastReceiver { camera, status, pkg ->
        Log.d("MainActivity", "Received camera event: camera=$camera, status=$status, pkg=$pkg")
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg
        val sourceDir = intent.getStringExtra(EXTRA_SOURCE_DIR) ?: "unknown"
        val time = timeFmt.format(System.currentTimeMillis())
        val isCameraFree = status.contains("свободна", ignoreCase = true)
        val logLine = if (isCameraFree) {
            "[$time] Камера $camera: $status\n\n"
        } else {
            "[$time] Камера $camera: $status\nПриложение: $appName\n\n"
        }
        val spannable = android.text.SpannableStringBuilder(logLine)
        if (!isCameraFree) {
            val appLabel = "Приложение: "
            val start = logLine.indexOf(appLabel) + appLabel.length
            val end = start + appName.length
            if (start >= appLabel.length && end <= logLine.length) {
                spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.rgb(33, 150, 243)), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.RelativeSizeSpan(1.2f), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        runOnUiThread {
            tvLog.append(spannable)
            val layout = tvLog.layout ?: return@runOnUiThread
            val diff = layout.getLineBottom(tvLog.lineCount - 1) - tvLog.height
            if (diff > 0) tvLog.scrollTo(0, diff)
        }
        val appInfo = AppData(appName, pkg, -1, sourceDir)
        sendCameraNotification(camera, status, appInfo)
    }

    private data class AppData(
        val name: String,
        val packageName: String,
        val targetSdkVersion: Int,
        val sourceDir: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Добавляем меню
        // (см. onCreateOptionsMenu/onOptionsItemSelected ниже)

        // Set the default color of the status circle to red when the app is opened
        val statusCircle = findViewById<ImageView>(R.id.ivStatus)
        statusCircle.setColorFilter(ContextCompat.getColor(this, R.color.stop_button_color))

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

        // Восстанавливаем лог при перевороте экрана
        if (savedInstanceState != null) {
            tvLog.text = savedInstanceState.getCharSequence("log_text")
        }

        btnStart.setOnClickListener {
            startClicked()
            updateStatusCircle(true)
        }
        btnStop.setOnClickListener {
            stopService(Intent(this, CameraMonitorService::class.java))
            btnStart.isEnabled = true
            btnStop.isEnabled  = false
            updateStatusCircle(false)
        }

        // 2) Создаём канал уведомлений (Android 8+)
        createNotificationChannel()

        // 3) Регистрируем BroadcastReceiver для получения обновлений о камере
        registerReceiver(cameraReceiver, CameraBroadcastReceiver.intentFilter())
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(cameraReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver", e)
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence("log_text", tvLog.text)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_main -> {
                // Главная — просто ничего не делаем (или можно сбросить фрагмент)
                return true
            }
            R.id.menu_stats -> {
                // Статистика по дням (заглушка)
                Toast.makeText(this, "Статистика по дням (в разработке)", Toast.LENGTH_SHORT).show()
                return true
            }
            R.id.menu_settings -> {
                // Настройка (заглушка)
                Toast.makeText(this, "Настройки (в разработке)", Toast.LENGTH_SHORT).show()
                return true
            }
            R.id.menu_about -> {
                // О приложении (заглушка)
                AlertDialog.Builder(this)
                    .setTitle("О приложении")
                    .setMessage("CameraMonitor\nВерсия 1.0\n\nМониторинг использования камеры.")
                    .setPositiveButton("OK", null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
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

    /**
     * Посылаем уведомление через NotificationManager
     * @param camera  – например, "Front" / "Back"
     * @param status  – "Открыл камеру" / "Закрыл камеру"
     * @param pkg     – package name приложения
     */
    private fun sendCameraNotification(camera: String, status: String, appInfo: AppData) {
        val title = "Камера: $camera"
        val text = "Приложение \"${appInfo.name}\"\n" +
                  "Package: ${appInfo.packageName}\n" +
                  "Статус: $status"

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

    // Added logic to change the color of the status circle based on monitoring state
    private fun updateStatusCircle(isMonitoring: Boolean) {
        val statusCircle = findViewById<ImageView>(R.id.ivStatus)
        val color = if (isMonitoring) R.color.start_button_color else R.color.stop_button_color
        statusCircle.setColorFilter(ContextCompat.getColor(this, color))
    }
}
