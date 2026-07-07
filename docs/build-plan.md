# GPS Rocket Locator '26 — Build Plan

*Drafted 2026-07-07. Companion to [gps-rocket-locator-26-brief.md](gps-rocket-locator-26-brief.md) and [research-findings.md](research-findings.md).*

## Locked decisions

| Decision | Choice |
|---|---|
| Hardware scope (v1.0) | Bluetooth Classic SPP (Eggfinder RX/LCD, Missile Works T3/RTx — 9600 8N1, PIN 1234) **+** BLE FFE0/FFE1 (HM-10/BT-04-style UART). Featherweight deferred (proprietary BLE, needs reverse engineering or vendor cooperation) |
| Maps | Layered: **compass-first recovery mode** (no map data required) → **MapLibre offline** (pre-downloaded regions + MBTiles/PMTiles sideload) → online extras (satellite basemap, Google Earth/Maps hand-off, KML export) |
| Platform | Native Android, **Kotlin + Jetpack Compose**; core logic in a UI-free pure-Kotlin module to keep a future iOS/KMP path open |
| License | **GPL-3.0**, developed clean-room in a **private repo during beta**, public at release. No code copied from unlicensed prior art (original RocketLocator, SimpleModelTracker); BearConsole2 (GPL-3.0) adaptable if ever useful |
| Distribution | Google Play (internal testing → production); F-Droid + GitHub releases after repo goes public |

## Design principles (lessons from the original's death)

1. **The map is an enhancement, not a dependency.** The compass/recovery screen must fully recover a rocket with zero connectivity and zero downloaded data. Tile-provider breakage — what killed the original — can never brick this app.
2. **No scraping, no unstable APIs.** Only documented, licensed tile sources; user-supplied MBTiles/PMTiles as the universal escape hatch.
3. **Phone GPS and rocket NMEA are two independent, simultaneously active position sources.** The original's "turn internal GPS on only after landing" quirk is a design bug, not a feature.
4. **Tolerant parsing.** Garbage/partial NMEA must never crash or reset the connection (original's pre-1.4.1 NPE bug). Accept all talker IDs (`$G[PNLAB]…`), validate checksums, degrade gracefully. Update position from GGA alone (original required GGA+RMC — a mistake when trackers send GGA-only).
5. **Manual coordinate entry ships in v1.0** — the original's most-requested, never-delivered feature.
6. **Testability without hardware**: a built-in flight simulator (NMEA replay + synthetic flight) as a first-class source — the spiritual successor of the original's "Random pos."
7. **Longevity**: annual target-SDK bumps budgeted; community can fork post-release (GPL).

## Architecture

```
:core        Pure Kotlin, zero Android deps (unit-testable, KMP-ready)
             ├─ nmea/        Sentence tokenizer, checksum, GGA/RMC/GSA/GSV/GLL/VTG parsers
             ├─ geo/         Haversine distance, initial bearing, relative bearing, unit conversions
             ├─ flight/      Telemetry state machine: fix quality, staleness, max altitude,
             │               path history, last-known-position, session stats
             └─ sim/         Synthetic flight generator + NMEA log replay

:transport   Android library — data sources emitting a common NmeaSource flow
             ├─ ClassicSppSource    RFCOMM (UUID 00001101-…), reconnect w/ backoff, data watchdog
             ├─ BleUartSource       GATT FFE0/FFE1, proper CCCD 0x2902 write, MTU request,
             │                      notification reassembly (original never wrote the CCCD — we do,
             │                      with fallback for clones that notify anyway)
             └─ SimulatorSource     wraps :core/sim

:app         Compose UI + services
             ├─ TrackingService     Foreground service, types: connectedDevice + location
             ├─ screens/            Map, Recovery (compass), Devices, Offline Maps, Logs, Settings
             ├─ map/                MapLibre wrapper: markers, path polyline, you→rocket line,
             │                      follow-me / rocket-compass camera modes, offline region manager
             ├─ audio/              Radar beep (rate ∝ pointing accuracy), link-health tones,
             │                      lost/reconnect sounds, optional TTS guidance
             └─ export/             KML/GPX export, geo: / Google Earth intents, share sheet
```

**Key screens**

- **Recovery (compass)** — the core: large arrow (rocket bearing relative to phone heading via rotation-vector sensor), distance, bearing, altitude delta, GPS-fix staleness indicator, radar beep, optional spoken guidance. Works with screen-off audio guidance.
- **Map** — rocket marker + travel path, phone marker, connecting line, distance/altitude/max-altitude readouts (correctly labeled…), follow-me and rocket-compass camera toggles, basemap switcher (offline region / satellite / streets).
- **Devices** — paired-device picker (SPP) + BLE scanner, connection status, raw NMEA console, disconnect button (original lacked one).
- **Offline Maps** — download region by bounding box + zoom range (est. size shown), import MBTiles/PMTiles, manage storage.
- **Settings** — units (imperial/metric), coordinate format (decimal degrees *and* DMS), sounds, keep-screen-on, log-to-file.
- **Manual entry** — type/paste coordinates (or share-target from another app) → navigate.

**Persistence**: DataStore for settings; Room (or flat files) for flight sessions — raw NMEA log + parsed track per session, survives app kill; last known rocket position always restored on launch.

**SDK targets**: minSdk 26 (Android 8.0, covers >98% of devices, keeps BT/permission code paths manageable), targetSdk = current Play requirement (35+), compileSdk latest.

## Phases

**Phase 0 — Scaffolding** *(small)*
Multi-module Gradle project, Kotlin + Compose BOM, GPL-3.0 LICENSE, README, ktlint/detekt, GitHub Actions CI (build + unit tests), private GitHub repo.

**Phase 1 — Core domain** *(medium)*
NMEA parser with an exhaustive unit-test corpus (real Eggfinder/T3 sentence samples, truncated/corrupt/multi-talker cases), geodesy math with known-answer tests, flight state machine, simulator. **Exit criterion: `:core` fully green with no Android dependency.**

**Phase 2 — Transports + service** *(medium-large)*
ClassicSppSource + BleUartSource + permissions flow (BLUETOOTH_CONNECT/SCAN, location), foreground TrackingService, Devices screen with raw console. **Exit criterion: live NMEA streaming from real hardware (or HC-06/HM-10 dev module on a bench) into the console, surviving out-of-range/reconnect cycles.**

**Phase 3 — Recovery mode** *(medium)*
Phone location + compass sensor fusion, Recovery screen, radar beep, TTS, last-position persistence, manual coordinate entry. **Exit criterion: field-walk test — hide a tracker (or use simulator + fixed coordinate), walk to it using only the Recovery screen.**

**Phase 4 — Map screen** *(medium)*
MapLibre integration, markers/path/line, camera modes, readouts, online satellite + streets styles. **Exit criterion: full simulated flight rendered live on map.**

**Phase 5 — Offline maps** *(medium)*
Region downloader (streets + NAIP/Esri satellite where licensed), MBTiles/PMTiles import, optional hillshade/contours. **Exit criterion: airplane-mode field test with pre-downloaded region.**

**Phase 6 — Polish** *(small-medium)*
KML/GPX export, Google Earth/Maps hand-off, session log browser, sounds/settings completeness, dark theme, accessibility pass, app icon/branding.

**Phase 7 — Beta → release**
Play internal testing track with rocketry-club testers across the hardware matrix (Eggfinder LCD+BT, T3, RTx, any BLE module); field-test protocol doc; crash reporting (self-hostable/privacy-respecting, e.g. ACRA); fix cycle; then: repo public, Play production, F-Droid submission, announce on TheRocketryForum (where the original's users still are).

**Deferred (post-1.0)**: Featherweight BlueRaven/GPS (needs BLE protocol reverse-engineering with real hardware), iOS/KMP evaluation, live flight telemetry extras (apogee detection from GGA altitude), multi-rocket tracking.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Exact NMEA sentence sets per tracker unconfirmed | Tolerant parser (GGA alone suffices); raw console for field diagnosis; collect real logs during beta |
| BLE module variants (BT-04 etc.) behave inconsistently | CCCD write with notify fallback; MTU negotiation; beta hardware matrix |
| Offline satellite licensing outside the US | NAIP (public domain) for US; Esri terms verification; MBTiles sideload as universal fallback |
| Play target-SDK policy churn | Annual maintenance budgeted; GPL + public repo as continuity backstop |
| Solo-dev bus factor (killed the original) | GPL-3.0, public repo at release, clean modular code, CI |

## Open questions for the user

1. **Test hardware**: which trackers/receivers do you own or can borrow (Eggfinder LCD with BT module? T3? RTx? any HM-10/BT-04 BLE module)? Phase 2's exit criterion depends on this; a $5 HC-06 + USB-serial dongle can bench-simulate a receiver if needed.
2. **Package name / identity**: need an Android application ID (e.g., reverse-domain like `com.<yourdomain>.rocketlocator26`) and the Google Play developer account holder name.
3. **Branding**: any preference for icon/visual identity (homage to the original's look vs. fresh)?
