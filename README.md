# RadiaWatch

Real-time radiation monitoring on your wrist. RadiaWatch connects to a [RadiaCode](https://radiacode.com) radiation detector over Bluetooth and streams live dose rate readings to a Zepp smartwatch.

## Features

- **Live dose rate display** — reads μSv/h from a RadiaCode device over BLE and shows it on the watch face in real time
- **3-tier alarm coloring** — value and units turn green (safe), yellow (alarm 1 reached), or red (alarm 2 reached), using thresholds read directly from the device
- **Haptic alerts** — watch vibrates briefly when crossing alarm 1, and with a stronger pulse when crossing alarm 2
- **Connection reliability** — Android app retries BLE connection up to 3 times automatically
- **"No data" state** — watch clearly distinguishes between waiting for connection and receiving live data
- **Keep screen lit** — optional toggle on the watch face keeps the display on while the app is open

## Structure

```
RadiaWatch/
├── android/   Android app — connects to RadiaCode over BLE, serves data over HTTP
└── zepp/      Zepp OS watch app — polls Android HTTP server, displays readings
```

The two apps communicate over a local HTTP server that the Android app runs on port 8080. The watch polls `http://127.0.0.1:8080/radiation` every 500ms.

## Android App

**Requirements:** Android 12+ (API 31), a RadiaCode device

### Building

The Android app is built with **Android Studio** build and deploy tools or the CLI:

```bash
cd android
./gradlew assembleDebug       # Build debug APK
./gradlew assembleRelease     # Build release APK
./gradlew installDebug        # Build and install directly to a connected device
```

### How it works

1. Launch the app and grant Bluetooth permissions
2. Scanning starts automatically — nearby RadiaCode devices appear in the list
3. Tap a device to connect — the app reads alarm thresholds from the device and begins displaying dose rate
4. The foreground service keeps the connection alive and the HTTP server running while the app is in the background

## Zepp Watch App

**Requirements:** A Zepp OS watch with a round 480×480px display, paired with the Android phone running the Android app

### Building and installing

The watch app is built with **Zepp Studio** or the **Zepp CLI**.

```bash
cd zepp
npm install      # Install dependencies
```

Then open the project in Zepp Studio and use its build and deploy tools to push the `.zab` to your watch, or use the CLI:

```bash
zeus build       # Build .zab
zeus preview     # Deploy to watch via Zepp app
```

### Watch UI

The watch face shows:
- ☢ radiation symbol
- Current dose rate in μSv/h, color-coded by alarm level
- ● / ○ button at the bottom to toggle keeping the screen lit

## Acknowledgements

The RadiaCode BLE protocol implementation in the Android app was developed with reference to the [Open-RadiaCode-Android](https://github.com/darkmatter2222/Open-RadiaCode-Android) project by [@darkmatter2222](https://github.com/darkmatter2222). Many thanks for documenting the protocol and providing an open reference implementation.

## License

MIT License. Copyright (c) 2026 max.marauder. Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.