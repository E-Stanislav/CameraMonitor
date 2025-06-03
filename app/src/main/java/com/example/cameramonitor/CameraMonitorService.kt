package com.example.cameramonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.app.AppOpsManager
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Обновлённый Service для отслеживания, какое приложение реально занимает камеру.
 *
 * Основные изменения:
 * 1. Добавлено поле lastOpPackage, куда записываем каждый пакет, сообщивший об операции OPSTR_CAMERA.
 * 2. В onCameraUnavailable (когда камера реально блокируется) сразу присваиваем currentPackageUsingCamera = lastOpPackage.
 * 3. При освобождении камеры (onCameraAvailable) сбрасываем currentPackageUsingCamera.
 */
class CameraMonitorService : Service() {

    private val logTag = "CameraMonitorService"

    companion object {
        const val MAX_LOG_SIZE = 100
        val eventLog: MutableList<String> = mutableListOf()
    }

    private lateinit var cameraManager: CameraManager
    private var availabilityCallback: CameraManager.AvailabilityCallback? = null

    // ID последней захваченной камеры (например, "0" или "1")
    private var currentCameraId: String? = null

    // Флаг: действительно ли камера сейчас занята (по AvailabilityCallback)
    private var isCameraInUse = false

    // Пакет приложения, которое использует камеру (которое мы показываем в уведомлении)
    private var currentPackageUsingCamera: String? = null

    // Последний пакет, которому система сообщила o OPSTR_CAMERA (AppOpsManager)
    private var lastOpPackage: String? = null

    private var appOpsManager: AppOpsManager? = null
    private val opChangedListener: AppOpsManager.OnOpChangedListener? = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_CAMERA) {
            // Запоминаем пакет, вызвавший операцию CAMERA (независимо от того, занята камера или нет)
            lastOpPackage = packageName
            Log.d(logTag, "AppOps OPSTR_CAMERA: lastOpPackage = $packageName")

            // Если камера уже реально занята, то сразу обновляем уведомление
            if (isCameraInUse) {
                currentPackageUsingCamera = lastOpPackage
                updateNotificationAndBroadcast()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager

        // Сразу запускаем foreground-уведомление, чтобы сервис не был убит системой
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildNotification(this, "Камера не используется", "-")
        )

        registerCameraCallbacks()
        registerAppOpsCallback()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCameraCallbacks()
        unregisterAppOpsCallback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Регистрируем CameraManager.AvailabilityCallback (API 21+), чтобы знать, когда камера занята/свободна.
     */
    private fun registerCameraCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            availabilityCallback = object : CameraManager.AvailabilityCallback() {

                override fun onCameraAvailable(cameraId: String) {
                    super.onCameraAvailable(cameraId)
                    // Камера освободилась
                    onCameraStatusChanged(cameraId, false)
                }

                override fun onCameraUnavailable(cameraId: String) {
                    super.onCameraUnavailable(cameraId)
                    // Камера занята
                    onCameraStatusChanged(cameraId, true)
                }
            }
            cameraManager.registerAvailabilityCallback(availabilityCallback!!, Handler(Looper.getMainLooper()))
        } else {
            Log.w(logTag, "API < 21: невозможно отслеживать состояние камеры напрямую")
        }
    }

    private fun unregisterCameraCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && availabilityCallback != null) {
            cameraManager.unregisterAvailabilityCallback(availabilityCallback!!)
        }
    }

    /**
     * Регистрируем AppOpsManager.OnOpChangedListener (API 23+), чтобы узнавать пакет, который запросил OPSTR_CAMERA.
     */
    private fun registerAppOpsCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            opChangedListener?.let {
                // Наблюдаем только за собственной областью пакетов (имя пакета — это applicationId)
                appOpsManager?.startWatchingMode(AppOpsManager.OPSTR_CAMERA, packageName, it)
            }
        } else {
            Log.w(logTag, "API < 23: невозможно отследить пакет, использующий камеру через AppOpsManager")
        }
    }

    private fun unregisterAppOpsCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            opChangedListener?.let {
                appOpsManager?.stopWatchingMode(it)
            }
        }
    }

    /**
     * Вызывается, когда камера либо занята (inUse=true), либо освобождена (inUse=false).
     * @param cameraId — ID камеры ("0" или "1" и т. д.)
     * @param inUse — true, если камера занята; false, когда освободилась
     */
    private var lastStatusChangeTime: Long = 0

    private fun onCameraStatusChanged(cameraId: String, inUse: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStatusChangeTime < 1000) return // Игнорируем повторные вызовы в течение 1 секунды
        lastStatusChangeTime = currentTime

        if (isCameraInUse == inUse && currentCameraId == cameraId) return

        isCameraInUse = inUse
        currentCameraId = cameraId

        if (inUse) {
            // Камера реально заблокирована — подставляем последний пакет, указавший OPSTR_CAMERA
            currentPackageUsingCamera = lastOpPackage ?: "Неизвестно"
        } else {
            // Камера освободилась — сбрасываем информацию о пакете
            currentPackageUsingCamera = null
        }

        updateNotificationAndBroadcast()
    }

    /**
     * Обновляем foreground-уведомление и шлём broadcast MainActivity, чтобы обновить UI.
     */
    private fun updateNotificationAndBroadcast() {
        // Определяем читаемое имя камеры (в данном примере: "Основная" или "Фронтальная")
        val cameraName = when (currentCameraId) {
            null -> "Устройство камеры неизвестно"
            "0" -> "Основная камера"
            "1" -> "Фронтальная камера"
            else -> "Камера ID: $currentCameraId"
        }
        val statusText = if (isCameraInUse) "Камера занята" else "Камера свободна"
        val pkgText = currentPackageUsingCamera ?: if (isCameraInUse) "Приложение неизвестно" else "—"

        // Обновляем уведомление (foreground)
        val uniqueNotificationId = System.currentTimeMillis().toInt()
        val notif = NotificationHelper.buildNotification(
            this,
            "Камера: $statusText",
            "Приложение: $pkgText\nУстройство: $cameraName"
        )
        NotificationHelper.notify(this, uniqueNotificationId, notif)

        // Добавляем запись в общий лог
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(System.currentTimeMillis())
        val logLine = "[$time] $cameraName: $statusText  \u2190 $pkgText"
        synchronized(eventLog) {
            eventLog.add(logLine)
            if (eventLog.size > MAX_LOG_SIZE) eventLog.removeAt(0)
        }

        // Шлём Intent, чтобы MainActivity (если она открыта) получила новые данные и дописала лог
        val intent = Intent(MainActivity.ACTION_CAMERA_STATUS_CHANGED).apply {
            putExtra(MainActivity.EXTRA_STATUS, statusText)
            putExtra(MainActivity.EXTRA_PACKAGE, pkgText)
            putExtra(MainActivity.EXTRA_CAMERA, cameraName)
        }
        sendBroadcast(intent)
    }
}
