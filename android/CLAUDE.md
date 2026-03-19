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

The companion smartwatch app lives at `../zepp` and has its own `CLAUDE.md`. It is a Zepp OS app that polls the Android app's HTTP server (`http://127.0.0.1:8080/radiation`) every second and displays the dose rate on the watch face.

The Android app is the data source — it owns the BLE connection to the RadiaCode device and runs `RadiationServer` on port 8080. The Zepp app is a pure consumer of that endpoint; it has no BLE access of its own. Changes to the JSON schema of `/radiation` (currently `{"usvh": ..., "cps": ...}`) must be coordinated across both projects.

## Architecture Overview

RadiaWatch is an Android app that connects to a RadiaCode Geiger counter over BLE, displays radiation readings, and exposes them over HTTP for a companion Zepp Watch app.

### Core Components

**Service layer** (`RadiaWatchService.kt`) — the heart of the app, runs as a foreground service:
- Manages BLE scanning (filters for "RadiaCode" devices by name)
- Drives connection lifecycle via `RadiacodeBleClient`
- Polls device data at ~1 Hz, updates `AppState`, and feeds `RadiationServer`

**BLE client** (`RadiacodeBleClient.kt`) — full GATT client implementation:
- Command-response protocol with 5-bit sequence numbers
- Chunks writes into 18-byte MTU segments
- Timeout handling (12–25s per command)
- Uses a single-threaded executor for writes, scheduled executor for timeouts

**Protocol layer** (`RadiacodeProtocol.kt`, `RadiacodeDataBuf.kt`, `ByteReader.kt`):
- `RadiacodeProtocol` — UUIDs, command codes, request packet building
- `RadiacodeDataBuf` — decodes binary event/group/timestamp/value structures from the device, extracts dose rate (Sv/h) and count rate (CPS)
- `ByteReader` — little-endian binary parsing utilities

**State** (`AppState.kt`) — singleton with `StateFlow`s consumed by the ViewModel and UI:
- `ScannedDevice` (address, name, rssi, BluetoothDevice)
- `ConnectionState` sealed class: Disconnected / Connecting / Connected / Error

**HTTP server** (`RadiationServer.kt`) — NanoHTTPD on port 8080, single endpoint:
- `GET /radiation` → `{"usvh": <float>, "cps": <float>}`
- Consumed by the Zepp Watch companion app (separate project)

**UI** (Jetpack Compose, Material3):
- `ScanScreen` — shows discovered devices via BLE scan
- `DeviceScreen` — shows live dose rate (μSv/h) for connected device
- `MainViewModel` bridges `AppState` StateFlows to the UI
- `MainActivity` handles permissions and routes between the two screens

### Data Flow

```
BLE scanCallback → AppState.updateScanResults() → ScanScreen
User selects device → Service.connectTo() → RadiacodeBleClient
RadiacodeBleClient.ready() + polling loop → AppState.updateConnectionState() → DeviceScreen
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
- Min SDK: 31 (Android 12); Target/Compile SDK: 36
- Java compatibility: 11
