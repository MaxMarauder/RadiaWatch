package com.maxmarauder.radiawatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RadiaWatchService : Service() {

    companion object {
        private const val TAG = "RadiaWatchService"
        private const val NOTIF_CHANNEL_ID = "radiawatch_bg"
        private const val NOTIF_ID = 1

        const val ACTION_START_SCAN = "com.maxmarauder.radiawatch.START_SCAN"
        const val ACTION_CONNECT = "com.maxmarauder.radiawatch.CONNECT"
        const val ACTION_DISCONNECT = "com.maxmarauder.radiawatch.DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val radiationServer = RadiationServer(8080)

    private val seenDevices = LinkedHashMap<String, ScannedDevice>()
    private var isScanning = false

    private var bleClient: RadiacodeBleClient? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification("RadiaWatch running"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, buildNotification("RadiaWatch running"))
        }
        radiationServer.start()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> startScan()
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return START_STICKY
                val scanned = seenDevices[address] ?: run {
                    val dev = getRemoteDevice(address) ?: return START_STICKY
                    ScannedDevice(address = address, name = address, rssi = 0, bluetoothDevice = dev)
                }
                connectTo(scanned)
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        disconnect()
        stopSelf()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        cancelConnection()
        radiationServer.stop()
        scope.cancel()
    }

    // ── BLE Scanning ──────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) return

        val adapter = getBluetoothAdapter() ?: return
        if (!adapter.isEnabled) return

        seenDevices.clear()
        AppState.updateScanResults(emptyList())
        AppState.setScanning(true)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            updateNotification("Scanning for RadiaCode devices…")
        } catch (t: Throwable) {
            Log.e(TAG, "Scan failed", t)
            AppState.setScanning(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        try {
            getBluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Throwable) {}
        isScanning = false
        AppState.setScanning(false)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed errorCode=$errorCode")
            isScanning = false
            AppState.setScanning(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = device.name ?: result.scanRecord?.deviceName ?: return
        if (!name.contains("RadiaCode", ignoreCase = true)) return

        val existing = seenDevices[address]
        if (existing == null) {
            seenDevices[address] = ScannedDevice(address = address, name = name, rssi = result.rssi, bluetoothDevice = device)
        } else {
            seenDevices[address] = existing.copy(rssi = result.rssi)
        }
        AppState.updateScanResults(seenDevices.values.sortedByDescending { it.rssi })
    }

    // ── BLE Connection ────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectTo(scanned: ScannedDevice) {
        stopScan()
        cancelConnection()

        AppState.updateConnectionState(ConnectionState.Connecting(scanned))
        updateNotification("Connecting to ${scanned.name}…")

        connectJob = scope.launch {
            var lastError: Throwable? = null

            for (attempt in 1..3) {
                if (!isActive) break

                if (attempt > 1) {
                    Log.d(TAG, "Connection attempt $attempt/3, waiting before retry…")
                    delay(2_000L)
                    if (!isActive) break
                }

                val client = RadiacodeBleClient(this@RadiaWatchService) { status ->
                    Log.d(TAG, "BLE[$attempt]: $status")
                }
                bleClient = client
                client.connect(scanned.bluetoothDevice)

                try {
                    client.ready().awaitResult()
                    client.initializeSession().awaitResult()

                    // ── Connected ─────────────────────────────────────────
                    lastError = null

                    try {
                        val (a1, a2) = client.readAlarmThresholds().awaitResult()
                        radiationServer.alarm1USvH = a1
                        radiationServer.alarm2USvH = a2
                        AppState.updateAlarmThresholds(a1, a2)
                        Log.d(TAG, "Alarm thresholds: alarm1=$a1 µSv/h, alarm2=$a2 µSv/h")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to read alarm thresholds", t)
                    }

                    AppState.updateConnectionState(ConnectionState.Connected(scanned, null))
                    updateNotification("Connected to ${scanned.name}")

                    // Polling loop ~1 Hz
                    pollJob = launch {
                        var consecutiveErrors = 0
                        while (isActive) {
                            try {
                                val raw = client.readDataBuf().awaitResult()
                                consecutiveErrors = 0
                                val data = RadiacodeDataBuf.decodeLatestRealTime(raw)
                                if (data != null) {
                                    val doseRateUSvH = data.doseRate * 10_000.0f
                                    AppState.updateConnectionState(ConnectionState.Connected(scanned, doseRateUSvH))
                                    updateNotification("${scanned.name}: ${"%.2f".format(doseRateUSvH)} μSv/h")
                                    radiationServer.doseRate = doseRateUSvH.toDouble()
                                    radiationServer.cps = data.countRate.toInt()
                                    radiationServer.connected = true
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                Log.e(TAG, "Poll error", t)
                                if (++consecutiveErrors >= 3) {
                                    Log.w(TAG, "Reconnecting after $consecutiveErrors consecutive poll failures")
                                    connectTo(scanned)
                                    return@launch
                                }
                            }
                            delay(1_000L)
                        }
                    }

                    return@launch  // success — exit retry loop

                } catch (e: CancellationException) {
                    client.close()
                    bleClient = null
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "Connection attempt $attempt/3 failed: ${t.message}")
                    lastError = t
                    client.close()
                    bleClient = null
                    // continue to next attempt
                }
            }

            if (lastError != null) {
                Log.e(TAG, "All connection attempts failed", lastError)
                AppState.updateConnectionState(ConnectionState.Error(scanned, lastError!!.message ?: "Connection failed"))
                updateNotification("Connection to ${scanned.name} failed")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun disconnect() {
        cancelConnection()
        AppState.updateConnectionState(ConnectionState.Disconnected)
        AppState.clearAlarmThresholds()
        startScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cancelConnection() {
        pollJob?.cancel()
        pollJob = null
        connectJob?.cancel()
        connectJob = null
        bleClient?.close()
        bleClient = null
        radiationServer.connected = false
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getBluetoothAdapter() =
        getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    private fun getRemoteDevice(address: String): BluetoothDevice? = try {
        getBluetoothAdapter()?.getRemoteDevice(address)
    } catch (_: Throwable) { null }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "RadiaWatch",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Background radiation monitoring" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("RadiaWatch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
    }
}

// Extension to convert CompletableFuture to a suspending call
private suspend fun <T> CompletableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    whenComplete { result, exception ->
        if (exception != null) {
            cont.resumeWithException(exception.cause ?: exception)
        } else {
            cont.resume(result)
        }
    }
    cont.invokeOnCancellation { cancel(true) }
}
