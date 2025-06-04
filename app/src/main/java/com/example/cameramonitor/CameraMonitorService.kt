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
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
                // Наблюдаем за всеми пакетами, использующими камеру
                appOpsManager?.startWatchingMode(AppOpsManager.OPSTR_CAMERA, null, it)
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

    // Получить пакет активного (foreground) приложения через UsageStatsManager
    private fun getForegroundAppPackage(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return null
            val time = System.currentTimeMillis()
            val appList = usm.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                time - 5000,
                time
            )
            if (!appList.isNullOrEmpty()) {
                val recentApp = appList.maxByOrNull { it.lastTimeUsed }
                return recentApp?.packageName
            }
        }
        return null
    }

    private fun onCameraStatusChanged(cameraId: String, inUse: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStatusChangeTime < 1000) return // Игнорируем повторные вызовы в течение 1 секунды
        lastStatusChangeTime = currentTime

        if (isCameraInUse == inUse && currentCameraId == cameraId) return

        isCameraInUse = inUse
        currentCameraId = cameraId

        if (inUse) {
            // Камера реально заблокирована — подставляем последний пакет, указавший OPSTR_CAMERA
            currentPackageUsingCamera = lastOpPackage
            if (currentPackageUsingCamera.isNullOrEmpty() || currentPackageUsingCamera == "Неизвестно") {
                // fallback: пробуем определить foreground app
                currentPackageUsingCamera = getForegroundAppPackage() ?: "Неизвестно"
                Log.d(logTag, "Fallback foreground app: $currentPackageUsingCamera")
            }
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
        // Определяем читаемое имя камеры
        val cameraName = when (currentCameraId) {
            null -> "Камера неизвестна"
            "0" -> "Back"
            "1" -> "Front"
            else -> "Камера ID: $currentCameraId"
        }
        Log.d(logTag, "isCameraInUse = $isCameraInUse, currentPackageUsingCamera = $currentPackageUsingCamera")
        Log.d(logTag, "Camera name: $cameraName")

        // Определяем информацию о пакете, использующем камеру
        val packageInfo = if (isCameraInUse && currentPackageUsingCamera != null && currentPackageUsingCamera != "Неизвестно") {
            try {
                val packageName = currentPackageUsingCamera!!
                val ai = packageManager.getApplicationInfo(packageName, 0)
                Log.d(logTag, "Found application info for package: $packageName")
                PackageInfo(
                    appName = packageManager.getApplicationLabel(ai).toString(),
                    packageName = packageName,
                    sourceDir = ai.sourceDir
                )
            } catch (e: SecurityException) {
                Log.w(logTag, "No permission to get package info for: $currentPackageUsingCamera", e)
                PackageInfo(
                    appName = "Защищённое приложение",
                    packageName = currentPackageUsingCamera!!,
                    sourceDir = "protected"
                )
            } catch (e: Exception) {
                Log.e(logTag, "Failed to get package info for: $currentPackageUsingCamera", e)
                PackageInfo(
                    appName = "Неопределённое приложение",
                    packageName = currentPackageUsingCamera!!,
                    sourceDir = "unknown"
                )
            }
        } else {
            PackageInfo(
                appName = if (isCameraInUse) "Неизвестное приложение" else "Не используется",
                packageName = if (isCameraInUse) "<unknown>" else "—",
                sourceDir = "not_in_use"
            )
        }

        
        val statusText = if (isCameraInUse) "Камера занята" else "Камера свободна"

        // Обновляем уведомление (foreground)
        val notif = NotificationHelper.buildNotification(
            this,
            "Камера: $statusText",
            "Приложение: ${packageInfo.appName}\n" +
            "Package: ${packageInfo.packageName}\n" +
            "Устройство: $cameraName"
        )
        NotificationHelper.notify(this, NotificationHelper.NOTIFICATION_ID, notif)

        // Шлём Intent, чтобы MainActivity получила новые данные и дописала лог
        val intent = Intent(MainActivity.ACTION_CAMERA_STATUS_CHANGED).apply {
            putExtra(MainActivity.EXTRA_STATUS, statusText)
            putExtra(MainActivity.EXTRA_PACKAGE, packageInfo.packageName)
            putExtra(MainActivity.EXTRA_CAMERA, cameraName)
            // Добавляем дополнительную информацию о приложении
            putExtra(MainActivity.EXTRA_APP_NAME, packageInfo.appName)
            putExtra(MainActivity.EXTRA_SOURCE_DIR, packageInfo.sourceDir)
        }
        sendBroadcast(intent)
    }

    private data class PackageInfo(
        val appName: String,
        val packageName: String,
        val sourceDir: String
    )
}
