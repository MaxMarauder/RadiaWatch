# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RadiaWatch is a Zepp smartwatch app (app ID: 20275) that displays real-time radiation readings from a Radiacode detector. The watch polls a local HTTP server at `http://127.0.0.1:8080/radiation` every 500ms and displays the value in μSv/h.

## Build & Development

Build is handled by **Zepp Studio IDE** or the **Zepp CLI** — there are no npm build scripts. The output is a `.zab` (Zepp App Binary) file in `dist/`.

```bash
npm install   # Install dependencies
```

There are no lint or test commands configured.

## Architecture

```
page/index.js          — Main watch UI: 500ms polling loop → HTTP GET /radiation → display
app-side/index.js      — Companion service (phone-side); currently minimal/stub
setting/index.js       — Phone-side settings page (AppSettingsPage) — not yet wired up
app.js                 — App entry point, lifecycle hooks only
app.json               — App manifest: appId, module declarations, target platform (480px round display)
```

**Data flow**: Radiacode device → Android HTTP server (port 8080) → watch app polls every 500ms → UI update

## HTTP Endpoint

`GET http://127.0.0.1:8080/radiation` returns:

```json
{
  "connected": true,
  "usvh": 0.12,
  "cps": 14,
  "alarm1": 1.0,
  "alarm2": 5.0
}
```

When `connected` is `false`, the watch shows "No data" and hides the value/units. `alarm1`/`alarm2` are dose rate thresholds in μSv/h (0.0 when unknown); the watch caches the last non-zero values received.

## UI Layout (`page/index.js`)

480×480px round display. All widget coordinates are absolute pixels.

| Widget | Position | Description |
|--------|----------|-------------|
| `_waiting` | y:0 h:415 | "Waiting..." on launch, "No data" when disconnected |
| `_symbol` | y:90 h:90 | ☢ radiation symbol (always visible) |
| `_value` | y:185 h:130 | Dose rate number, text_size 100 (hidden until data arrives) |
| `_units` | y:320 h:60 | "μSv/h" label (hidden until data arrives) |
| `_bulb` | y:415 h:55 | Screen-on toggle: ● (on) / ○ (off), tappable |

### 3-tier color scheme

- **Green** (`0x00e676`) — dose rate below alarm1 threshold
- **Yellow** (`0xffff00`) — dose rate between alarm1 and alarm2
- **Red** (`0xff0000`) — dose rate above alarm2 threshold

Applied to `_value` and `_units`. `_waiting`, `_symbol`, and `_bulb` always use yellow.

### Vibration on alarm crossings

Uses `Vibrator` from `@zos/sensor`. Fires on upward threshold crossings only:
- Alarm1 crossed: `VIBRATOR_SCENE_SHORT_MIDDLE`
- Alarm2 crossed: `VIBRATOR_SCENE_SHORT_STRONG`

`VIBRATOR_SCENE_STRONG_REMINDER` causes infinite vibration on this device — do not use.

### Keep-screen-lit toggle

The `_bulb` button (● / ○) toggles the "keep screen lit" feature:
- **ON**: `setPageBrightTime({ brightTime: 2147483000 })` + `pauseDropWristScreenOff({ duration: 0 })`
- **OFF**: `resetPageBrightTime()` + `resetDropWristScreenOff()`

State persisted to `localStorage` (key: `'keepScreenLit'`, value: `'true'`/`'false'`). Defaults to **off**. Requires `device:os.local_storage` permission in `app.json`.

## Key Framework Details

Uses `@zeppos/zml` for:
- `BaseApp` / `BasePage` / `BaseSideService` — lifecycle management; messaging between phone and watch is wired automatically via BLE — `this.call()` / `onCall()` / `this.httpRequest()` are available without explicit setup
- `createWidget()` / `widget` / `prop` / `align` / `event` — UI widgets and events
- `.httpRequest()` — HTTP client (proxied via phone-side app-side service)
- `.setProperty(prop.MORE, { text, color })` — update multiple widget properties atomically
- `.addEventListener(event.CLICK_DOWN, fn)` — reliable tap handler; `click_func` property on TEXT widgets is unreliable

### Widget gotchas

- **Emoji characters** (💡, ☀ U+2600, etc.) are rendered with built-in emoji colors; the `color` property has no effect on them. Use non-emoji Unicode (e.g. geometric shapes like ● U+25CF, ○ U+25CB) when color control is needed.
- **`prop.MORE { visible: true }`** does not reliably show widgets hidden at runtime via `setProperty(prop.VISIBLE, false)`. Always use a separate `setProperty(prop.VISIBLE, true)` call.
- **`createWidget({ visible: false })`** may not be reliable on this runtime. Follow up with explicit `setProperty(prop.VISIBLE, false)` calls in `build()`.
- **Full-screen overlay widgets** intercept touches for underlying widgets even when invisible. Keep overlay widgets (like `_waiting`) shorter than the full 480px if tappable widgets must coexist below them.

## Permissions (`app.json`)

```json
["device:os.vibrator", "device:os.sensor", "device:os.local_storage"]
```

## i18n

Translation files use `.po` (gettext) format in `page/i18n/` and `app-side/i18n/`. Currently only `en-US` is populated (with placeholder strings).
