package com.maxmarauder.radiawatch

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val bluetoothDevice: BluetoothDevice,
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val device: ScannedDevice) : ConnectionState()
    data class Connected(val device: ScannedDevice, val doseRate: Float?) : ConnectionState()
    data class Error(val device: ScannedDevice, val message: String) : ConnectionState()
}

object AppState {
    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults = _scanResults.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    fun updateScanResults(results: List<ScannedDevice>) { _scanResults.value = results }
    fun updateConnectionState(state: ConnectionState) { _connectionState.value = state }
    fun setScanning(scanning: Boolean) { _isScanning.value = scanning }
}
