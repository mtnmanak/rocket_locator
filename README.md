# GPS Rocket Locator '26

A modern Android app for recovering model and high-power rockets carrying GPS trackers — an homage to the original *GPS Rocket Locator* by Brune Studios (2013–2020, delisted 2024), rebuilt from scratch for current Android and current tracking hardware.

**Status: pre-release beta (0.NN versions).** See [CHANGELOG.md](CHANGELOG.md).

[**Download the latest APK**](https://github.com/mtnmanak/rocket_locator/releases/latest) · [project website](https://www.mountainmanrockets.com/rocket_locator/)

**Found a bug, or want a feature?** [Open an issue](https://github.com/mtnmanak/rocket_locator/issues) — bug reports and flight logs from real launches genuinely shape what gets built next. No GitHub account? Email <admin@mountainmanrockets.com> and it gets filed for you.

Security issues: see [SECURITY.md](SECURITY.md) — please don't file them as public issues.

## How it works

Your rocket carries a GPS transmitter. A handheld RF receiver picks up its telemetry and relays standard NMEA sentences to your phone over Bluetooth. ~~This app turns that stream into recovery guidance:~~ **v0.03** provides a live telemetry dashboard, Devices with Classic SPP + BLE UART, a simulator, and a foreground tracking service. **Since v0.03, unreleased:** fix age and an explicit last-known state after 10 s without a valid fix; Connect keeps the last known position, with confirmed **New flight** as the only reset; only measured fixes move the rocket (dead reckoning, manual and simulated receiver fixes do not). Recovery features are **planned (Phases 3–5)**:

- **Recovery compass** — **planned (Phase 3)**: arrow to the rocket, distance, and accelerating "radar beep"; works with **no map data and no cell coverage**
- **Live map** — **planned (Phase 4; offline maps Phase 5)**: rocket position, flight path, and a line from you to the rocket (offline maps downloadable before launch day)
- **Manual coordinate entry** — **planned (Phase 3)**: type in coordinates from any source and navigate to them
- ~~Last-known position is always preserved, even through signal loss and app restarts~~ **Position persistence is planned (Phase 3).** In v0.03, rocket state remains in memory only and is not restored after process death.

## Supported hardware

| Receiver | Link | Status |
|---|---|---|
| Eggtimer Eggfinder RX / LCD (+ Bluetooth module) | Bluetooth Classic SPP, 9600 8N1, PIN 1234 | Primary target |
| Missile Works T3 / RTx (+ HC-06 module) | Bluetooth Classic SPP | Primary target |
| HM-10/BT-04-style BLE serial modules | BLE GATT (FFE0/FFE1) | Supported |
| Featherweight BlueRaven / GPS | Proprietary BLE | Planned (post-1.0) |
| Eggtimer LR series (LoRa; LCD-LR receiver) | Not yet known | Planned once the hardware ships (late 2026) |

Any receiver that streams standard NMEA over a Bluetooth serial link should work.

## Installation

**Download page: <https://www.mountainmanrockets.com/rocket_locator/>**

Distributed as a direct APK download (sideload) via GitHub Releases, and via F-Droid after the first stable release. Not currently on the Play Store.

## Building

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew :core:test        # domain unit tests (no Android SDK needed)
./gradlew assembleDebug     # full debug APK
```

Project layout: `:core` (pure-Kotlin domain: NMEA, geodesy, flight state, simulator), `:transport` (Bluetooth/serial data sources), `:app` (Jetpack Compose UI).

## License

[GPL-3.0](LICENSE). Clean-room implementation — no code from the original app (which was never licensed).

## Documentation

- [Project brief](docs/gps-rocket-locator-26-brief.md)
- [Research findings](docs/research-findings.md) — original app teardown, hardware protocols, prior art
- [Build plan](docs/build-plan.md)
