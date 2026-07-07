# GPS Rocket Locator '26

Android app (Kotlin, Jetpack Compose) that receives NMEA GPS telemetry from model-rocket trackers over Bluetooth and guides the user to the landed rocket. GPL-3.0. Homage to Brune Studios' defunct "GPS Rocket Locator" (2013–2020), rebuilt clean-room.

## Read first

- `docs/build-plan.md` — locked decisions, architecture, phase roadmap and where we are in it
- `docs/research-findings.md` — original-app teardown, hardware protocols (verified), prior art
- `docs/bench-test-protocol.md` — hardware validation procedure
- `CHANGELOG.md` — beta versioning is `0.NN` with `versionCode = NN` until 1.0

## Hard rules

- **Clean-room GPL-3.0**: NEVER copy code from `fantasiiio/RocketLocator` (the original app — unlicensed) or `bdureau/SimpleModelTracker` (unlicensed). They are reference/spec only. `bdureau/BearConsole2` is GPL-3.0 and may be adapted with attribution.
- **`:core` stays pure Kotlin** — no Android imports, no coroutines, no file IO. It is unit-tested and KMP-ready.
- **NMEA parsing never throws** on any input; a no-fix sentence never moves the rocket position; the map/UI must degrade gracefully — a data problem must never crash a recovery in the field.
- The repository's DISCONNECTED connection state is terminal-only; retry backoff reads as CONNECTING. `TrackingService` relies on this.

## Modules

- `:core` — NMEA parser, geodesy, `FlightTracker` state machine, flight simulator
- `:transport` — `NmeaSource` flow abstraction: `ClassicSppSource` (HC-06/SPP), `BleUartSource` (FFE0/FFE1), `SimulatorSource`
- `:app` — Compose UI, `TelemetryRepository` (process-wide pipeline, owned by Application), `TrackingService` (connectedDevice foreground service)

## Target hardware

Eggfinder RX/LCD and Missile Works T3/RTx: NMEA over HC-06 Bluetooth Classic SPP, 9600 8N1, PIN 1234. HM-10/BT-04-style BLE modules: UART service FFE0, characteristic FFE1, 20-byte notification fragments. Featherweight: proprietary BLE, deferred post-1.0.

## Build

Requires JDK 17 + Android SDK 35 (write `local.properties` with `sdk.dir=...`; forward slashes).

```
./gradlew :core:test :transport:test   # unit tests (208+, must stay green)
./gradlew :app:assembleDebug           # debug APK
```

Windows dev note: files created on Windows lack the executable bit — any new shell script needs `git update-index --chmod=+x <file>`.

## Releases

`git tag v0.NN && git push origin v0.NN` → CI tests, builds, signs, publishes a GitHub Release with asset `rocket-locator-26.apk` (stable name — the website download button depends on it). Signing uses GitHub secrets; the keystore itself lives outside the repo and must never be committed (`.gitignore` covers it). Local release signing needs `keystore.properties` at the repo root (machine-local).

Website: https://www.mountainmanrockets.com/rocket_locator/ — source of truth is `site/deploy/index.html`; changes there must be re-uploaded to the WordPress subfolder manually.
