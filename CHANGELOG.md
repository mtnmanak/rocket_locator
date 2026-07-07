# Changelog

All notable changes to GPS Rocket Locator '26 are documented here.

Beta versioning: `0.NN` (versionCode = NN) until the first production release (1.0).

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
