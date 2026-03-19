package com.maxmarauder.radiawatch

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {
    val scanResults: StateFlow<List<ScannedDevice>> = AppState.scanResults
    val connectionState: StateFlow<ConnectionState> = AppState.connectionState
    val isScanning: StateFlow<Boolean> = AppState.isScanning
    val alarmThresholds: StateFlow<Pair<Double, Double>?> = AppState.alarmThresholds
}
