package com.maxmarauder.radiawatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.maxmarauder.radiawatch.ui.theme.RadiaWatchTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("InlinedApi")
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            RadiaWatchTheme {
                val scanResults by viewModel.scanResults.collectAsState()
                val connectionState by viewModel.connectionState.collectAsState()
                val isScanning by viewModel.isScanning.collectAsState()
                val alarmThresholds by viewModel.alarmThresholds.collectAsState()

                var permissionsGranted by remember { mutableStateOf(hasAllPermissions()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    // POST_NOTIFICATIONS is optional; only BLE perms are required
                    @Suppress("InlinedApi")
                    val postNotifPerm = Manifest.permission.POST_NOTIFICATIONS
                    val btGranted = results.entries
                        .filter { it.key != postNotifPerm }
                        .all { it.value }
                    permissionsGranted = btGranted
                    if (permissionsGranted) {
                        startScanService()
                    } else {
                        Toast.makeText(
                            this,
                            "Bluetooth permissions are required to scan for devices.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                LaunchedEffect(Unit) {
                    if (!permissionsGranted) {
                        permissionLauncher.launch(requiredPermissions)
                    } else {
                        startScanService()
                    }
                }

                // Navigate between screens based on connection state
                val showDevice = connectionState is ConnectionState.Connecting
                    || connectionState is ConnectionState.Connected
                    || connectionState is ConnectionState.Error

                if (showDevice) {
                    DeviceScreen(
                        state = connectionState,
                        alarmThresholds = alarmThresholds,
                        onDisconnect = {
                            sendServiceAction(RadiaWatchService.ACTION_DISCONNECT)
                        }
                    )
                } else {
                    ScanScreen(
                        scanResults = scanResults,
                        isScanning = isScanning,
                        onDeviceClick = { device ->
                            sendServiceAction(RadiaWatchService.ACTION_CONNECT) {
                                putExtra(RadiaWatchService.EXTRA_DEVICE_ADDRESS, device.address)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        @Suppress("InlinedApi")
        val postNotifPerm = Manifest.permission.POST_NOTIFICATIONS
        return requiredPermissions.all {
            // POST_NOTIFICATIONS is optional — treat it as granted even if denied
            if (it == postNotifPerm) return@all true
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScanService() {
        startForegroundService(Intent(this, RadiaWatchService::class.java).apply {
            action = RadiaWatchService.ACTION_START_SCAN
        })
    }

    private fun sendServiceAction(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, RadiaWatchService::class.java).apply {
            this.action = action
            extras()
        }
        startService(intent)
    }
}
