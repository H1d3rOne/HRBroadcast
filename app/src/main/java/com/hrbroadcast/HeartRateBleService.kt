package com.hrbroadcast

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseCallback
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class HeartRateBleService : Service() {

    companion object {
        const val TAG = "HeartRateBleService"
        const val CHANNEL_ID = "heart_rate_ble_channel"
        const val NOTIFICATION_ID = 1002
        
        const val ACTION_START_BROADCAST = "com.hrbroadcast.START_BROADCAST"
        const val ACTION_STOP_BROADCAST = "com.hrbroadcast.STOP_BROADCAST"
        const val ACTION_UPDATE_HEART_RATE = "com.hrbroadcast.UPDATE_HEART_RATE"
        const val ACTION_CONNECTION_STATE_CHANGED = "com.hrbroadcast.CONNECTION_STATE_CHANGED"
        const val ACTION_AUTO_STOP = "com.hrbroadcast.AUTO_STOP"
        const val EXTRA_HEART_RATE = "heart_rate"
        const val EXTRA_CONNECTION_COUNT = "connection_count"
        
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        var isAdvertising = false
            private set
        
        var connectionCount = 0
            private set
        
        private const val AUTO_STOP_DELAY_MS = 60_000L
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var heartRateCharacteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var currentHeartRate: Int = 0
    private var gattServerStarted = false
    private val connectedDevices = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var broadcastStartTime: Long = 0
    private var hasReceivedConnection: Boolean = false

    private val autoStopRunnable = Runnable {
        if (isAdvertising && connectedDevices.isEmpty()) {
            Log.d(TAG, "Auto stop: No connections for 1 minute, stopping broadcast")
            broadcastAutoStop()
            stopAdvertising()
            stopGattServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing BLE service")
        
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        
        Log.d(TAG, "onCreate: BluetoothAdapter=${bluetoothAdapter != null}, Advertiser=${bluetoothLeAdvertiser != null}")
        
        if (bluetoothLeAdvertiser == null) {
            Log.w(TAG, "onCreate: BLE advertising not supported")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, heartRate=${intent?.getIntExtra(EXTRA_HEART_RATE, -1)}")
        
        when (intent?.action) {
            ACTION_START_BROADCAST -> {
                currentHeartRate = intent.getIntExtra(EXTRA_HEART_RATE, 0)
                Log.d(TAG, "ACTION_START_BROADCAST: heartRate=$currentHeartRate")
                if (!gattServerStarted) {
                    startGattServer()
                }
                if (!isAdvertising) {
                    startAdvertising()
                    startAutoStopTimer()
                }
            }
            ACTION_STOP_BROADCAST -> {
                Log.d(TAG, "ACTION_STOP_BROADCAST")
                stopAutoStopTimer()
                stopAdvertising()
                stopGattServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_HEART_RATE -> {
                currentHeartRate = intent.getIntExtra(EXTRA_HEART_RATE, 0)
                Log.d(TAG, "ACTION_UPDATE_HEART_RATE: heartRate=$currentHeartRate, isAdvertising=$isAdvertising, gattServer=$gattServerStarted")
                notifyHeartRate()
            }
        }
        
        return START_STICKY
    }

    private fun startAutoStopTimer() {
        broadcastStartTime = System.currentTimeMillis()
        hasReceivedConnection = false
        handler.postDelayed(autoStopRunnable, AUTO_STOP_DELAY_MS)
        Log.d(TAG, "Auto stop timer started: 60 seconds")
    }

    private fun stopAutoStopTimer() {
        handler.removeCallbacks(autoStopRunnable)
        Log.d(TAG, "Auto stop timer stopped")
    }

    private fun resetAutoStopTimer() {
        handler.removeCallbacks(autoStopRunnable)
        if (isAdvertising && connectedDevices.isEmpty()) {
            handler.postDelayed(autoStopRunnable, AUTO_STOP_DELAY_MS)
            Log.d(TAG, "Auto stop timer reset: 60 seconds")
        }
    }

    private fun broadcastConnectionState() {
        connectionCount = connectedDevices.size
        val intent = Intent(ACTION_CONNECTION_STATE_CHANGED).apply {
            putExtra(EXTRA_CONNECTION_COUNT, connectionCount)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        updateNotification()
        Log.d(TAG, "Connection state broadcast: count=$connectionCount")
    }

    private fun broadcastAutoStop() {
        isAdvertising = false
        connectionCount = 0
        val intent = Intent(ACTION_AUTO_STOP).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Auto stop broadcast sent")
    }

    private fun startGattServer() {
        if (!checkBluetoothPermissions()) {
            Log.e(TAG, "startGattServer: Missing Bluetooth permissions")
            return
        }
        
        if (gattServerStarted) {
            Log.d(TAG, "startGattServer: GATT server already started")
            return
        }
        
        try {
            Log.d(TAG, "startGattServer: Creating Heart Rate characteristic")
            
            heartRateCharacteristic = BluetoothGattCharacteristic(
                HEART_RATE_MEASUREMENT_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            
            val cccd = BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            heartRateCharacteristic?.addDescriptor(cccd)
            
            Log.d(TAG, "startGattServer: Creating Heart Rate Service with UUID: $HEART_RATE_SERVICE_UUID")
            
            val heartRateService = BluetoothGattService(
                HEART_RATE_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            heartRateService.addCharacteristic(heartRateCharacteristic)
            
            Log.d(TAG, "startGattServer: Opening GATT server")
            gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
            
            if (gattServer == null) {
                Log.e(TAG, "startGattServer: Failed to open GATT server")
                return
            }
            
            val result = gattServer?.addService(heartRateService)
            Log.d(TAG, "startGattServer: Add service result: $result")
            
            gattServerStarted = true
            Log.d(TAG, "startGattServer: GATT server started successfully with Heart Rate Service (UUID: 0x180D)")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "startGattServer: Security exception", e)
        } catch (e: Exception) {
            Log.e(TAG, "startGattServer: Exception", e)
        }
    }

    private fun stopGattServer() {
        try {
            connectedDevices.clear()
            connectionCount = 0
            gattServer?.close()
            gattServer = null
            gattServerStarted = false
            Log.d(TAG, "stopGattServer: GATT server stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "stopGattServer: Security exception", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice?, status: Int, newState: Int) {
            val deviceAddress = device?.address ?: "unknown"
            Log.d(TAG, "onConnectionStateChange: device=$deviceAddress, status=$status, newState=$newState")
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected: $deviceAddress")
                connectedDevices.add(deviceAddress)
                hasReceivedConnection = true
                handler.removeCallbacks(autoStopRunnable)
                broadcastConnectionState()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: $deviceAddress")
                connectedDevices.remove(deviceAddress)
                broadcastConnectionState()
                if (connectedDevices.isEmpty()) {
                    resetAutoStopTimer()
                }
            }
        }
        
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Log.d(TAG, "onServiceAdded: status=$status, service=${service?.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Heart Rate Service added successfully")
            } else {
                Log.e(TAG, "Failed to add Heart Rate Service, status=$status")
            }
        }
        
        override fun onDescriptorWriteRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.d(TAG, "onDescriptorWriteRequest: descriptor=${descriptor?.uuid}, value=${value?.contentToString()}")
            
            if (descriptor?.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) {
                    Log.d(TAG, "Notifications enabled for heart rate by ${device?.address}")
                } else if (value?.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) == true) {
                    Log.d(TAG, "Notifications disabled for heart rate by ${device?.address}")
                }
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
        
        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(TAG, "onCharacteristicReadRequest: characteristic=${characteristic?.uuid}")
            
            if (characteristic?.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val data = createHeartRateData(currentHeartRate)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
                Log.d(TAG, "Sent heart rate data: $currentHeartRate BPM")
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    private fun notifyHeartRate() {
        if (!isAdvertising) {
            Log.d(TAG, "notifyHeartRate: Not advertising, skip")
            return
        }
        if (gattServer == null || heartRateCharacteristic == null) {
            Log.d(TAG, "notifyHeartRate: GATT server or characteristic is null")
            return
        }
        
        try {
            val data = createHeartRateData(currentHeartRate)
            heartRateCharacteristic?.value = data
            
            val devices = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
            Log.d(TAG, "notifyHeartRate: Connected devices: ${devices?.size}")
            
            devices?.forEach { device ->
                val notified = gattServer?.notifyCharacteristicChanged(device, heartRateCharacteristic, false)
                Log.d(TAG, "notifyHeartRate: Notified ${device.address}, result=$notified")
            }
            
            Log.d(TAG, "notifyHeartRate: Heart rate notified: $currentHeartRate BPM")
        } catch (e: SecurityException) {
            Log.e(TAG, "notifyHeartRate: Security exception", e)
        }
    }

    private fun startAdvertising() {
        if (!checkBluetoothPermissions()) {
            Log.e(TAG, "startAdvertising: Missing Bluetooth permissions")
            return
        }
        
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "startAdvertising: BLE advertiser not available")
            return
        }
        
        if (isAdvertising) {
            Log.d(TAG, "startAdvertising: Already advertising")
            return
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "startAdvertising: SUCCESS - Advertising Heart Rate Service (UUID: 0x180D)")
                isAdvertising = true
                updateNotification()
            }
            
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "startAdvertising: FAILED - error code: $errorCode")
                isAdvertising = false
            }
        }
        
        try {
            Log.d(TAG, "startAdvertising: Starting advertising with UUID: 0x180D")
            bluetoothLeAdvertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "startAdvertising: Security exception", e)
        }
    }

    private fun stopAdvertising() {
        if (!isAdvertising) {
            return
        }
        
        try {
            advertiseCallback?.let {
                bluetoothLeAdvertiser?.stopAdvertising(it)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "stopAdvertising: Security exception", e)
        }
        
        isAdvertising = false
        advertiseCallback = null
        Log.d(TAG, "stopAdvertising: Advertising stopped")
    }

    private fun createHeartRateData(heartRate: Int): ByteArray {
        val flags: Byte = 0x00
        val hr = heartRate.coerceIn(0, 255).toByte()
        return byteArrayOf(flags, hr)
    }

    private fun checkBluetoothPermissions(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val advertise = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val connect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "checkBluetoothPermissions: BLUETOOTH_ADVERTISE=$advertise, BLUETOOTH_CONNECT=$connect")
            advertise && connect
        } else {
            val bluetooth = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val admin = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "checkBluetoothPermissions: BLUETOOTH=$bluetooth, BLUETOOTH_ADMIN=$admin")
            bluetooth && admin
        }
        return result
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
        val connectionStr = if (connectionCount > 0) " | 连接: $connectionCount" else ""
        return if (currentHeartRate > 0) {
            "BLE广播: ${currentHeartRate} BPM$connectionStr"
        } else {
            "BLE广播中$connectionStr"
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
                "心率BLE广播",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "心率BLE广播服务"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoStopTimer()
        stopAdvertising()
        stopGattServer()
        Log.d(TAG, "onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
