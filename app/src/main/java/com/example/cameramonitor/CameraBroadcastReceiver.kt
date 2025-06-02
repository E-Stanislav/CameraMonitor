package com.example.cameramonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * CameraBroadcastReceiver принимает события от CameraMonitorService и передаёт их в UI.
 */
class CameraBroadcastReceiver(
    private val listener: (camera: String, status: String, pkg: String) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getStringExtra(MainActivity.EXTRA_STATUS) ?: return
        val pkg    = intent.getStringExtra(MainActivity.EXTRA_PACKAGE) ?: return
        val camera = intent.getStringExtra(MainActivity.EXTRA_CAMERA) ?: return

        listener(camera, status, pkg)
    }

    companion object {
        fun intentFilter() = IntentFilter(MainActivity.ACTION_CAMERA_STATUS_CHANGED)
    }
}

