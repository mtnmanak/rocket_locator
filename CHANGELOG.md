# Changelog

All notable changes to GPS Rocket Locator '26 are documented here.

Beta versioning: `0.NN` (versionCode = NN) until the first production release (1.0).

## [Unreleased]

### Fixed

- Connect keeps the last known rocket position; confirmed **New flight** is the only reset, and **Run simulator** asks first when a real position is held.
- Fix age updates with time, and a fix becomes stale after 10 s with an explicit last-known state.
- Only measured fixes move the rocket; dead reckoning, manual and simulated receiver fixes do not.
- Altitude plausibility checks prevent a single altitude glitch from setting the maximum.
- Stricter NMEA field parsing rejects malformed telemetry while preserving the last accepted position.
- Permanently denied Bluetooth permissions offer **Open app settings**, with permission state refreshed on return.
- BLE scan errors explain what happened and how to retry.
- TalkBack labels identify device Connect buttons and announce connection and stale-position changes.
- Devices screen outlined buttons have improved contrast in dark theme.
- Simulator pacing accounts for processing time to keep playback steady.

### Changed

- Upgraded to Gradle 9.8.2, AGP 9.5.0 and Kotlin 2.4.21, with compileSdk 37 / targetSdk 36; builds require Android SDK platform 37 and JDK 17.

### Security

- Hardened CI with pinned actions, release checks and checksum-verified tooling.
- Added [SECURITY.md](SECURITY.md) with private vulnerability reporting guidance.

## [0.03] — 2026-10-27

Mountain Man Rockets brand (mmr-brand 1.1.0). Visual only — no change to Bluetooth, GPS, parsing, geodesy, units or permissions.

### Changed
- **New launcher icon** — adaptive icon on brand navy, round-icon variant, and an Android 13+ themed (monochrome) layer; vendored from mmr-brand by `sync-brand.mjs` (`mmr-brand.json`, `docs/brand/`).
- **Brand colours** in both light and dark themes, following the system setting. Material You dynamic colour is off so the brand holds on Android 12+.
- Card titles use the brand's text-size orange; connection status uses the brand success/warning colours (the old amber was unreadable on the light theme); cards get a thin border.
- Navy window and splash background — no white flash on a cold start.
- App name shortened to **Rocket Locator**.

## [0.02] — 2026-07-07

Phase 2: real Bluetooth. Untested against hardware until the first bench session — see `docs/bench-test-protocol.md`.

### Added
- **Bluetooth Classic SPP source** (Eggfinder RX/LCD, Missile Works T3/RTx with HC-06 modules): RFCOMM on the standard SPP UUID, automatic reconnect with exponential backoff (1 s → 15 s cap), 7-second no-data watchdog.
- **Bluetooth LE UART source** (HM-10/BT-04-style FFE0/FFE1 modules): GATT notifications with proper CCCD descriptor write (plus fallback for clones that lack the descriptor), MTU negotiation, 20-byte fragment reassembly, same reconnect/watchdog behavior.
- **Foreground tracking service** (`connectedDevice` type): keeps the Bluetooth link and NMEA parsing alive with the screen off; persistent notification with live state, altitude, satellite count, and a Disconnect action.
- **Devices screen**: runtime Bluetooth permission flow (Android 12+ "Nearby devices" model, location-based fallback for Android 8–11), paired Classic device list, BLE scanner with RSSI, live raw-NMEA console.
- Process-wide telemetry repository: one pipeline shared by the UI and the service; last state and console survive disconnects.
- Navigation between Home and Devices screens; connection state chip and connect/disconnect actions on Home.

## [0.01] — 2026-07-07

Initial development snapshot.

### Added
- Multi-module project scaffold (`:core` pure Kotlin, `:transport` Android library, `:app` Compose UI), GPL-3.0 license, CI build.
- `:core` NMEA parser — GGA, RMC, GLL, VTG, GSA, GSV sentences; all GNSS talker IDs (GP/GN/GL/GA/GB); XOR checksum validation; tolerant of malformed input (bad sentences are reported, never crash parsing).
- `:core` geodesy — haversine distance, initial/relative bearing, unit conversions (metric/imperial, knots).
- `:core` flight tracker — rocket state from parsed sentences: position, altitude, max altitude, path history, fix quality, data staleness.
- `:core` flight simulator — synthetic ballistic flight NMEA generator and NMEA log replay for hardware-free testing.
- `:transport` `NmeaSource` abstraction with simulator-backed implementation (Bluetooth SPP/BLE sources arrive in 0.02).
- Minimal app shell (Compose, Material 3) showing live simulated telemetry.
