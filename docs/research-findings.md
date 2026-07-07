# GPS Rocket Locator '26 — Research Findings

*Compiled 2026-07-06 via multi-agent deep research (21 sources fetched, 99 claims extracted, 25 top claims adversarially verified 3-0, 0 refuted). Companion to [gps-rocket-locator-26-brief.md](gps-rocket-locator-26-brief.md).*

---

## 1. The original app — major discovery: it's on GitHub

- **Package**: `com.frankdev.rocketlocator`, developer "Brune Studios" (solo dev, forum handle **fantasiiio**, Frank/Francois, Quebec). On Google Play July 2013 → final **v1.5** June 2020 → **removed from Play March 4, 2024**. ~6,500 downloads, 3.94★.
- **Full Java source survives**: <https://github.com/fantasiiio/RocketLocator> — last push 2020-05-17, **no license file** (study-able, NOT legally copyable). The v1.5 BLE + multi-GNSS work came from community PR #2 (mihu86). The Bluetooth/NMEA layer is a fork of BlueGPS4Droid (`org.broeuschmeul.android.gps`).
- **Why it died** (author's own words): "I don't have time anymore, because of day job and familly" (2015); Google map-tile URL changes repeatedly broke it (2017); delisted 2024, almost certainly target-SDK policy.

### Verified internals (from source)

| Aspect | Detail |
|---|---|
| BT Classic | RFCOMM via standard SPP UUID `00001101-0000-1000-8000-00805F9B34FB`; 1 s reconnect retry; ~3 s no-data watchdog |
| BLE (v1.5) | HM-10-style service `FFE0` / characteristic `FFE1`, notifications; reassembles NMEA from 20-byte MTU fragments; **never writes CCCD 0x2902** (relies on HM-10 clones notifying anyway). No field reports of anyone actually using BLE mode |
| NMEA parser | Talker regex `G[PNLA]` (GP/GN/GL/GA, v1.5+; GPS-only before). Patterns: GGA, RMC, GSA, VTG, GLL. Position update requires **both GGA and RMC**; GGA fix quality must == 1. Received checksums apparently not validated |
| Logging | Raw GPS + app messages to on-screen Logs console; optional file log to `rocket/` folder; path export as Google Earth format to `rocket_path.txt` |

### UI (from developer's archived docs + forums; no screenshots survive anywhere)

Full-screen map; top-left readouts (Rocket Distance, Current Altitude, Max Altitude); bottom-left toggles: **Radar Beep** (beeps faster when pointing at rocket), **Follow Me** (camera follows *user*, auto-disables on pan), **Rocket Compass** (rotates map toward rocket). Blue my-location dot, red rocket pushpin, always a line between them, plus the rocket's travel-path line. Menu: Save path, Restart bluetooth, **Random pos** (fake data for testing), Reset Altitude, Load last pos, Settings, Logs, Map Provider (Google/OSM), Download Map (offline tiles to `/sdcard/mapCache/`). Audio link-health: a "bong" per parsed sentence, distinct lost-signal and reconnect sounds. Imperial/Metric toggle; decimal degrees only.

### Documented pain points (targets to fix)

1. **Map tile breakage was the killer** — Google tiles broke Mar 2017, again Nov 2017; OSM-only with no satellite view afterward.
2. Crashes: on BT scan on modern Android; when other BT devices connected; NPE on unparseable NMEA (fixed only 1.4.1).
3. Readout labels all said "current altitude" for ~2 years.
4. **No manual coordinate entry** — most-requested feature, never added.
5. Offline cache flakiness; 600 MB caches unmigratable; tiles flooding Gallery (fixed 1.5).
6. No BT disconnect option; unreadable dark text on white OSM tiles; "internal GPS must be turned on after you get landing coordinates" quirk — the app mishandled phone GPS + BT NMEA as simultaneous sources.
7. Never iOS; "not available for your device" on newer Android before delisting.

---

## 2. Hardware protocols (verified against vendor primary docs)

### Eggtimer Eggfinder RX / LCD ✅ verified 3-0

- **HC-06 Bluetooth Classic SPP module** (user-installed on provided 4-pin header: 3.3V/GND/TXD/RXD), **not BLE**. Pairing PIN **1234** for all HC-06 modules.
- **9600 baud 8N1** (HC-06 factory default == Eggfinder RF module settings). One-way link: only 3.3V/GND/RXD wired on RX; no commands can be sent back.
- Streams **standard NMEA** — repeating GGA/GSA/RMC groups with periodic GSV at 1 Hz (sentence pattern from forum snippets; probable, not vendor-pinned).
- Eggtimer's own app note recommended Rocket Locator: "it works OK but it's a little quirky." Eggtimer later **dropped the Bluetooth module option** because the app died.
- One 2022 forum report mentions an Eggfinder BT module advertising as "**BT-04**" (HM-10-clone family → BLE FFE0/FFE1 plausible) — needs confirmation whether Eggtimer shipped a BLE variant.
- Sources: [Eggfinder_Bluetooth.pdf](http://eggtimerrocketry.com/wp-content/uploads/2018/06/Eggfinder_Bluetooth.pdf), [LCD Users Guide 2.01c](http://eggtimerrocketry.com/wp-content/uploads/2021/05/Eggfinder-LCD-Users-Guide-2_01c1.pdf)

### Missile Works T3 / RTx ✅ verified 3-0

- **T3**: standard (non-proprietary) NMEA serial stream over Bluetooth; vendor's T3-NMEA page teaches decoding **$GPGGA**. Officially lists compatible apps: Blue GPS / Rocket Track / **Rocket Locator** / GPS Connector.
- **RTx**: "live data stream output via our HC-06 Bluetooth module" — Classic SPP only, ready for "Rocket Locator" and "Bluetooth GPS" (both now delisted — the vendor's recommendations point at dead apps).
- Sources: [missileworks.com/t3](https://www.missileworks.com/t3), [t3-nmea](https://www.missileworks.com/t3-nmea), [rtx](https://www.missileworks.com/rtx)

### Featherweight GPS / BlueRaven ⚠️ NOT verified — open gap

- **No claims about the Featherweight BLE protocol survived verification.** Proprietary BLE, locked to the Featherweight UI app (iOS v1.1.4 Jun 2026; Android `com.blue_raven_mvp.app.blue_raven_mvp`) — bearing-to-rocket, live map, voice telemetry, best-in-class UX, but no NMEA and no documented/open protocol found. Supporting it would require reverse engineering or vendor cooperation. Note: BlueRaven is Featherweight's *altimeter*; the GPS tracker is a separate product with its own ground station.

### Ecosystem summary

Every budget tracker (Eggfinder, T3, RTx) converges on **one pattern: standard NMEA, one-way, over HC-06 Bluetooth Classic SPP, 9600 8N1, PIN 1234**. That single code path covers the whole primary hardware list.

---

## 3. Open-source prior art

| Project | License | Status | What it offers |
|---|---|---|---|
| [fantasiiio/RocketLocator](https://github.com/fantasiiio/RocketLocator) | **none** | dead 2020 | The original itself — complete behavioral spec; reference only, no code reuse |
| [shanet/Osprey](https://github.com/shanet/Osprey) | GPLv3 | unmaintained | Exact target UX: rocket marker + phone position + flight path + phone→rocket route line, bearing/distance/altitude compass view. Own radio protocol, not NMEA — UX/architecture reference |
| [bdureau/SimpleModelTracker](https://github.com/bdureau/SimpleModelTracker) | **none** | active (May 2026) | Closest working reference: SPP RFCOMM (std UUID), $GPGGA-only parser **with XOR checksum validation**, osmdroid maps. Reference only, no code reuse. Its GPGGA-only parser would miss $GNGGA — a lesson |
| [bdureau/BearConsole2](https://github.com/bdureau/BearConsole2) | **GPL-3.0** | active (Feb 2025+, on Play) | Legally reusable (if we accept GPL): BT Classic SPP + USB + 3DR telemetry, parallel Google Maps / osmdroid map implementations |
| [kruland2607/RocketTrack](https://github.com/kruland2607/RocketTrack) | open | dead 2013 | Historical; broken on modern Android |
| AltosDroid (Altus Metrum) | GPL | active | Full telemetry/maps/TTS but requires TeleBT hardware |
| GPS Connector (`de.pilablu.gpsconnector`) | closed | active (Aug 2025) | What forum users actually use now — but it's an NMEA bridge with no rocket map/bearing UX; users copy lat/lon into a maps app by hand |

**None of the open-source apps implements BLE FFE0/FFE1** — the original RocketLocator's v1.5 BLE manager is the only prior art for that path.

**Market gap confirmed**: no maintained, purpose-built map-and-bearing app exists for the NMEA-over-BT segment since ~2020. Vendors either link dead apps or sell dedicated hardware around the gap. iOS is entirely unserved for serial-BT NMEA trackers.

---

## 4. Modern Android implementation notes (verified against official docs)

- **Permissions (API 31+)**: `BLUETOOTH_CONNECT` (connect to paired devices) and `BLUETOOTH_SCAN` are **runtime** permissions ("Nearby devices" prompt). Location permission (`ACCESS_FINE_LOCATION`) still needed for the phone-position/navigation feature.
- **Foreground services (API 34+)**: every FGS must declare a type + type-specific permission or it throws. Bluetooth link → `connectedDevice` type (`FOREGROUND_SERVICE_CONNECTED_DEVICE`); continuous phone tracking → `location` type (`FOREGROUND_SERVICE_LOCATION`).
- **Map library**: **osmdroid was archived Nov 20, 2024** (read-only, last release 6.1.20) — both bdureau apps depend on it; don't copy that stack. **MapLibre** is the maintained successor path (ODK Collect migrated for exactly this reason) with offline MBTiles/PMTiles support and satellite-capable raster sources. Google Maps SDK is fine online but weak offline.
- **MVP path is clear**: Classic SPP RFCOMM (UUID `00001101-…`) at 9600 baud → NMEA parser (GGA+RMC, all talker IDs `G[PNLA]`, checksum-validated) → MapLibre offline map → distance/bearing/compass guidance. BLE FFE0/FFE1 as phase 2; Featherweight as research-dependent phase 3.

---

## 5. Open questions

1. **Featherweight BlueRaven/GPS BLE protocol** — GATT services, format, any reverse-engineered integration? (Nothing survived verification.)
2. Did Eggtimer ever ship a **BLE (BT-04/HM-10-clone) module** variant, making the FFE0 path load-bearing for real hardware?
3. **Exact NMEA sentence set** emitted by Eggfinder TX and T3/RTx (GGA only, or GGA+RMC+GSA+GSV?) and whether newer units emit `$GNGGA` — matters because the original app required *both* GGA and RMC before updating position.
4. MapLibre offline tile packaging best practice (MBTiles vs PMTiles) + a satellite-imagery source with acceptable licensing for offline caching.

## 6. Unconfirmed / lost to history

No screenshots or video of the original UI survive; post-2013 line color; Logs screen layout; whether "Brune Studios" was more than one person; exact Play-removal reason.
