# GPS Rocket Locator '26 — Project Brief

## Background
There used to be an Android app called **GPS Rocket Locator**, originally built by **Brune Studios**. Its core function:
- Received **NMEA data** from a GPS tracker installed in a model/high-power rocket.
- Mapped that data using **Google Earth** on the user's phone.
- Once the rocket was located, the app took the **last known GPS coordinate**, drew a **blue line** from the user's phone to the rocket, and guided the user directly to it.

### How the system worked
- The **rocket** carries a GPS transmitter.
- The **user** carries a receiver that picks up GPS and telemetry data from the transmitter via an **RF transmission**.
- The receiver connects to the user's phone via **Bluetooth**, and the GPS Locator app reads the incoming data over that Bluetooth connection.

### Why a new app is needed
The original app's development stalled roughly **6 years ago**. It has not been updated for modern Android OS versions, and the source code was **never released**.

## Project Goal
Recreate the app's functionality using **modern Android** and **modern tracking hardware**, under the name:

> **GPS Rocket Locator '26**

The name is intended as an homage to the original app, while clearly differentiating it as a new, independent product.

## Target Hardware (Modern Bluetooth-Capable Trackers)
- **Eggtimer Eggfinder LCD receiver** (with Bluetooth Module)
- **Missile Works T3 and RTx series** trackers
- **Featherweight BlueRaven** tracker systems
- Possibly others (to be researched/confirmed)

## Requested Process
Before any building begins:
1. Research the original app's functionality and the target hardware's data protocols.
2. Discuss research findings with the user.
3. Present a proposed **build plan**.
4. Ask clarifying questions if information is missing or needed.
5. Outline **next steps**.

---

## Legacy App — Release Notes Reference (GPS Rocket Locator, by Brune Studios)

| Version | Release Date | Size | Notable Changes |
|---|---|---|---|
| 1.1.0 | Sep 21, 2015 | 970.5 KB | English translation completed; "Follow me" button disables when map moves; log console/log file added; improved reconnection after lost connection |
| 1.2.0 | Jun 6, 2016 | 1.7 MB | Fixed compass crash; adjusted "radar beep" delay; sound no longer maxes out at start; more visible rocket path line; added Imperial/Metric unit toggle |
| 1.2.1 | Jun 7, 2016 | 1.7 MB | Same changes as 1.2.0 (patch release) |
| 1.3 | Jul 10, 2016 | 1.7 MB | Added offline map support; added OpenStreetMap/Google Maps provider choice |
| 1.3.1 | Jul 20, 2016 | 1.7 MB | Fixed offline map issue; OSM/Google Maps provider choice |
| 1.3.2 | Aug 5, 2016 | 1.7 MB | Fixed rocket path line; fixed offline map; OSM/Google Maps provider choice |
| 1.3.3 | Aug 10, 2017 | 1.7 MB | Fixed Google Maps satellite view |
| 1.4 | Apr 27, 2019 | 1.8 MB | Fixed Google Maps; fixed null pointer issue causing Bluetooth reconnects on unparseable NMEA sentences; added location/storage permission requests |
| 1.4.1 | Jun 17, 2019 | 1.8 MB | Repaired Current Altitude, Max Altitude, and Rocket Distance readouts; carried forward Google Maps and Bluetooth NMEA fixes; location/storage permission requests |
| 1.5 (latest) | Jun 23, 2020 | — | Added "Use Bluetooth LE" setting (API 19+); implemented a Bluetooth LE GPS manager to handle incoming NMEA notifications via a custom FFE0 GATT service; improved NMEA parser to support GALILEO, GLONASS, and multi-provider data; switched tile cache storage to `.dat` files (instead of `.png`) to avoid appearing in Photos/Gallery apps; moved tile cache to the standard cache folder by default |
