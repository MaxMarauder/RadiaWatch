# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install to connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device)
./gradlew lint                   # Run lint checks
./gradlew clean                  # Clean build artifacts
```

## Related Project: Zepp Watch App (`../zepp`)

The companion smartwatch app lives at `../zepp` and has its own `CLAUDE.md`. It is a Zepp OS app that polls the Android app's HTTP server (`http://127.0.0.1:8080/radiation`) every 500ms and displays the dose rate on the watch face.

The Android app is the data source — it owns the BLE connection to the RadiaCode device and runs `RadiationServer` on port 8080. The Zepp app is a pure consumer of that endpoint; it has no BLE access of its own. Changes to the JSON schema of `/radiation` must be coordinated across both projects.

### `/radiation` JSON schema

```json
{
  "connected": true,
  "usvh": 0.12,
  "cps": 14,
  "alarm1": 1.0,
  "alarm2": 5.0
}
```

`connected` is `false` when no RadiaCode device is connected; the watch uses this to show "No data" rather than a stale reading. `alarm1`/`alarm2` are dose rate thresholds in μSv/h read from the device (0.0 when unknown).

## Architecture Overview

RadiaWatch is an Android app that connects to a RadiaCode Geiger counter over BLE, displays radiation readings, and exposes them over HTTP for a companion Zepp Watch app.

### Core Components

**Service layer** (`RadiaWatchService.kt`) — the heart of the app, runs as a foreground service:
- Manages BLE scanning (filters for "RadiaCode" devices by name)
- Drives connection lifecycle via `RadiacodeBleClient` with up to 3 retry attempts (2s delay between attempts) to handle intermittent GATT failures
- On connect: reads alarm thresholds via `COMMAND_RD_VIRT_SFR`, then starts polling
- Polls device data at ~1 Hz, updates `AppState`, and feeds `RadiationServer`
- Sets `radiationServer.connected = false` on disconnect so the watch shows "No data"

**BLE client** (`RadiacodeBleClient.kt`) — full GATT client implementation:
- Command-response protocol with 5-bit sequence numbers
- Chunks writes into 18-byte MTU segments
- Timeout handling (12–25s per command)
- Uses a single-threaded executor for writes, scheduled executor for timeouts
- `readAlarmThresholds()` reads VSFRs 0x8000 (alarm1) and 0x8001 (alarm2) in µR/h, converts to µSv/h (×0.01)

**Protocol layer** (`RadiacodeProtocol.kt`, `RadiacodeDataBuf.kt`, `ByteReader.kt`):
- `RadiacodeProtocol` — UUIDs, command codes, request packet building; `COMMAND_RD_VIRT_SFR = 0x0824`, `VSFR_DR_ALARM1 = 0x8000L`, `VSFR_DR_ALARM2 = 0x8001L`
- `RadiacodeDataBuf` — decodes binary event/group/timestamp/value structures from the device, extracts dose rate (Sv/h) and count rate (CPS)
- `ByteReader` — little-endian binary parsing utilities

**State** (`AppState.kt`) — singleton with `StateFlow`s consumed by the ViewModel and UI:
- `ScannedDevice` (address, name, rssi, BluetoothDevice)
- `ConnectionState` sealed class: Disconnected / Connecting / Connected(doseRate) / Error
- `alarmThresholds: StateFlow<Pair<Double, Double>?>` — alarm1/alarm2 in μSv/h, null when disconnected

**HTTP server** (`RadiationServer.kt`) — NanoHTTPD on port 8080, single endpoint:
- `GET /radiation` → JSON with `connected`, `usvh`, `cps`, `alarm1`, `alarm2`
- `connected` field is set per poll (true) and cleared on disconnect (false)
- Consumed by the Zepp Watch companion app (separate project)

**UI** (Jetpack Compose, Material3):
- `ScanScreen` — shows discovered devices via BLE scan
- `DeviceScreen` — shows live dose rate with 3-tier color: green (below alarm1), yellow (alarm1–alarm2), red (above alarm2); shows alarm threshold lines below the units label
- `MainViewModel` bridges `AppState` StateFlows to the UI
- `MainActivity` handles permissions and routes between the two screens; calls `enableEdgeToEdge()` then forces `isAppearanceLightStatusBars = false` so status bar icons stay light against the dark background
- `res/drawable/ic_notification_radiation.xml` — monochrome notification icon: white circle with radiation trefoil cut out as transparent holes (`fillType="evenOdd"`); used by the foreground service notification

### Data Flow

```
BLE scanCallback → AppState.updateScanResults() → ScanScreen
User selects device → Service.connectTo() (up to 3 attempts) → RadiacodeBleClient
RadiacodeBleClient.ready() → initializeSession() → readAlarmThresholds()
  → AppState.updateAlarmThresholds() → DeviceScreen
  → polling loop → AppState.updateConnectionState() → DeviceScreen
                 → RadiationServer (HTTP /radiation)
```

### Concurrency Model

- Service coroutines run on `IO` dispatcher with a `SupervisorJob`
- BLE writes use a dedicated single-threaded `ExecutorService`
- Timeouts use a `ScheduledExecutorService`
- `AppState` updates are protected with `synchronized` blocks

### Key Constants

- BLE device name filter: `"RadiaCode"` (prefix match in service)
- HTTP server port: `8080` (hardcoded in `RadiationServer`)
- Connection retries: 3 attempts, 2s delay between attempts
- Min SDK: 31 (Android 12); Target/Compile SDK: 36
- Java compatibility: 11
