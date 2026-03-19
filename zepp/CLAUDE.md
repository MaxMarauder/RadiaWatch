# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RadiaWatch is a Zepp smartwatch app (app ID: 20275) that displays real-time radiation readings from a Radiacode detector. The watch polls a local HTTP server at `http://127.0.0.1:8080/radiation` every second and displays the value in μSv/h.

## Build & Development

Build is handled by **Zepp Studio IDE** or the **Zepp CLI** — there are no npm build scripts. The output is a `.zab` (Zepp App Binary) file in `dist/`.

```bash
npm install   # Install dependencies
```

There are no lint or test commands configured.

## Architecture

```
page/index.js          — Main watch UI: 1s polling loop → HTTP GET /radiation → display
app-side/index.js      — Companion service (phone-side); currently minimal/stub
app.js                 — App entry point, lifecycle hooks only
app.json               — App manifest: appId, module declarations, target platform (480px round display)
```

**Data flow**: Radiacode device → HTTP server (port 8080) → watch app polls every 1s → UI update

The HTTP endpoint returns `{ usvh: number }`. The watch UI shows four text widgets (all yellow `#ffff00`): a loading indicator, the ☢ symbol, the numeric value (2 decimal places), and the "μSv/h" units label.

## Key Framework Details

Uses `@zeppos/zml` for:
- `BaseApp` / `BasePage` / `BaseSideService` — lifecycle management
- `createWidget()` / `widget` API — UI widgets
- `.httpRequest()` — HTTP client on the watch
- `.setProperty()` — dynamic widget updates

Screen target: 480×480px. Widget coordinates are absolute pixels.

## i18n

Translation files use `.po` (gettext) format in `page/i18n/` and `app-side/i18n/`. Currently only `en-US` is populated (with placeholder strings).
