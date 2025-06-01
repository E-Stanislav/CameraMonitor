package com.example.cameramonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity позволяет:
 * 1. Стартовать/останавливать CameraMonitorService
 * 2. Отображать историю событий (последние изменения статуса камеры)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvLog: TextView

    private val cameraReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == ACTION_CAMERA_STATUS_CHANGED) {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Unknown"
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: "—"
                val camera = intent.getStringExtra(EXTRA_CAMERA) ?: "—"
                val logLine = "[${System.currentTimeMillis()}] Камера $camera: $status (App: $pkg)\n"
                tvLog.append(logLine)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvLog = findViewById(R.id.tvLog)

        // Делаем TextView прокручиваемым
        tvLog.movementMethod = ScrollingMovementMethod()

        btnStart.setOnClickListener {
            val serviceIntent = Intent(this, CameraMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        }

        btnStop.setOnClickListener {
            val serviceIntent = Intent(this, CameraMonitorService::class.java)
            stopService(serviceIntent)
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(cameraReceiver, IntentFilter(ACTION_CAMERA_STATUS_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(cameraReceiver)
    }

    companion object {
        const val ACTION_CAMERA_STATUS_CHANGED = "com.example.cameramonitor.CAMERA_STATUS_CHANGED"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_CAMERA = "extra_camera"
    }
}
