# Changelog

All notable changes to GPS Rocket Locator '26 are documented here.

Beta versioning: `0.NN` (versionCode = NN) until the first production release (1.0).

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
