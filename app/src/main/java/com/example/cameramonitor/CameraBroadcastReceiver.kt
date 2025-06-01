package com.example.cameramonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * CameraBroadcastReceiver принимает события от CameraMonitorService и передаёт их в UI.
 */
class CameraBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        if (intent.action == MainActivity.ACTION_CAMERA_STATUS_CHANGED) {
            val status = intent.getStringExtra(MainActivity.EXTRA_STATUS) ?: "Unknown"
            val packageName = intent.getStringExtra(MainActivity.EXTRA_PACKAGE) ?: "—"
            Log.d("CameraBroadcastReceiver", "Камера: $status, Приложение: $packageName")
        }
    }
}
