package com.hrbroadcast

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HeartRateService : LifecycleService(), SensorEventListener {

    companion object {
        const val TAG = "HeartRateService"
        const val ACTION_HEART_RATE_UPDATE = "com.hrbroadcast.HEART_RATE_UPDATE"
        const val EXTRA_HEART_RATE = "heart_rate"
        const val EXTRA_IS_WEARING = "is_wearing"
        const val CHANNEL_ID = "heart_rate_channel"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
    }

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentHeartRate: Float = 0f
    private var isWearing: Boolean = false
    private var lastHeartRateTime: Long = 0
    private var screenReceiver: ScreenReceiver? = null
    private var isSensorRegistered = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Starting service")
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())
        
        setupSensor()
        setupWakeLock()
        setupScreenReceiver()
        startWearingCheck()
        startSensorRetry()
    }

    private fun checkSensorPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "checkSensorPermission: $granted")
        return granted
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    private fun setupSensor() {
        if (!checkSensorPermission()) {
            Log.w(TAG, "setupSensor: No permission")
            return
        }
        
        Log.d(TAG, "setupSensor: Initializing sensor manager")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        
        Log.d(TAG, "setupSensor: Heart rate sensor: $heartRateSensor")
        
        if (heartRateSensor != null) {
            Log.d(TAG, "setupSensor: Sensor name: ${heartRateSensor?.name}, vendor: ${heartRateSensor?.vendor}")
            registerSensorListener()
        } else {
            Log.w(TAG, "setupSensor: No heart rate sensor found")
        }
    }

    private fun registerSensorListener(): Boolean {
        if (heartRateSensor == null || !checkSensorPermission()) {
            Log.w(TAG, "registerSensorListener: Cannot register - sensor: $heartRateSensor, permission: ${checkSensorPermission()}")
            return false
        }
        
        if (isSensorRegistered) {
            Log.d(TAG, "registerSensorListener: Already registered")
            return true
        }
        
        val delays = listOf(
            SensorManager.SENSOR_DELAY_FASTEST,
            SensorManager.SENSOR_DELAY_GAME,
            SensorManager.SENSOR_DELAY_UI,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        
        for (delay in delays) {
            val registered = sensorManager?.registerListener(
                this,
                heartRateSensor,
                delay
            ) ?: false
            
            if (registered) {
                isSensorRegistered = true
                Log.d(TAG, "registerSensorListener: Success with delay $delay")
                return true
            }
            Log.w(TAG, "registerSensorListener: Failed with delay $delay")
        }
        
        return false
    }

    private fun startSensorRetry() {
        lifecycleScope.launch {
            var retryCount = 0
            while (retryCount < 10 && !isSensorRegistered) {
                delay(3000)
                retryCount++
                Log.d(TAG, "Sensor retry attempt $retryCount")
                if (registerSensorListener()) {
                    Log.d(TAG, "Sensor registered on retry $retryCount")
                    break
                }
            }
        }
    }

    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HRBroadcast::HeartRateWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L)
        
        lifecycleScope.launch {
            while (true) {
                delay(9 * 60 * 1000)
                wakeLock?.let {
                    if (!it.isHeld) {
                        it.acquire(10 * 60 * 1000L)
                    }
                }
            }
        }
    }

    private fun setupScreenReceiver() {
        screenReceiver = ScreenReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun startWearingCheck() {
        lifecycleScope.launch {
            while (true) {
                delay(5000)
                val currentTime = System.currentTimeMillis()
                val timeSinceLastHeartRate = currentTime - lastHeartRateTime
                
                if (timeSinceLastHeartRate > 10000 && isWearing) {
                    Log.d(TAG, "Wearing check: No heart rate for ${timeSinceLastHeartRate}ms, setting not wearing")
                    isWearing = false
                    broadcastHeartRate()
                    updateNotification()
                }
                
                if (!isSensorRegistered) {
                    registerSensorListener()
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_HEART_RATE) {
                val heartRate = it.values[0]
                Log.d(TAG, "onSensorChanged: Heart rate = $heartRate")
                if (heartRate > 0) {
                    currentHeartRate = heartRate
                    isWearing = true
                    lastHeartRateTime = System.currentTimeMillis()
                    broadcastHeartRate()
                    updateNotification()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "onAccuracyChanged: sensor=$sensor, accuracy=$accuracy")
    }

    private fun broadcastHeartRate() {
        val intent = Intent(ACTION_HEART_RATE_UPDATE).apply {
            putExtra(EXTRA_HEART_RATE, currentHeartRate.toInt())
            putExtra(EXTRA_IS_WEARING, isWearing)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "broadcastHeartRate: heartRate=${currentHeartRate.toInt()}, isWearing=$isWearing")
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getNotificationContent())
            .setSmallIcon(R.drawable.ic_heart)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun getNotificationContent(): String {
        return if (isWearing && currentHeartRate > 0) {
            "${currentHeartRate.toInt()} ${getString(R.string.heart_rate_unit)}"
        } else {
            getString(R.string.watch_not_worn)
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "心率监测服务"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        isRunning = false
        sensorManager?.unregisterListener(this)
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        screenReceiver?.let {
            unregisterReceiver(it)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
