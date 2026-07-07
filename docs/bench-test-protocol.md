# Bench Test Protocol — Eggfinder Hardware (v0.02)

Goal: validate the Phase 2 exit criterion — **live NMEA streaming from real hardware into the app, surviving out-of-range/reconnect cycles** — using the Eggtimer Eggfinder receiver with its Bluetooth module.

## What you need

- Eggfinder RX or LCD receiver with the Bluetooth module installed, powered
- Eggfinder TX (or Mini/TRS) transmitter, powered, ideally near a window so it gets GPS lock
- Android phone with `app-debug.apk` (v0.02) sideloaded
- Optional: a second person's patience

## 1. Identify which Bluetooth module you have

Power the receiver and open **Android Settings → Bluetooth → Pair new device**:

| What you see | Module type | Connect via |
|---|---|---|
| `HC-06` (or `HC-05`, `linvor`) | Bluetooth **Classic** SPP | Pair first (PIN **1234**), then app → Devices → *Paired devices* |
| `BT-04`, `BT05`, `MLT-BT05`, `HM-10`, `HMSoft` | **BLE** UART (FFE0/FFE1) | Do **not** pair in Settings; app → Devices → *BLE devices* → Scan |
| Both listed | Dual-mode clone | Try Classic path first |

Note which one it is — this tells us whether your Eggfinder units exercise the SPP path, the BLE path, or both.

## 2. Classic SPP path (HC-06)

1. Pair in Android Settings, PIN `1234`. The device shows as paired (it will say "connected: no" — normal, SPP links open on demand).
2. Open the app → **Devices**. Grant the Bluetooth permission when prompted.
3. The HC-06 appears under **Paired devices** → tap **Connect**.
4. A persistent notification appears (foreground service). Expected within ~5 s: status flips to **Connected** and the **Live NMEA console** starts scrolling sentences once per second.

**What the console should show** (TX on, even without GPS lock):
`$GPGGA,...,0,00,...` — fix quality 0 while acquiring. Once the TX has lock: `$GPGGA,...,1,05,...` and the Home screen readouts populate.

## 3. BLE path (BT-04/HM-10-style module)

1. Do not pair in Settings. App → Devices → **BLE devices** → **Scan**.
2. The module appears (name may be `BT-04`, `HMSoft`, or `(unnamed)`) → **Connect**.
3. Same expectations as Classic. If the app reports *"Device has no FFE0/FFE1 UART service"*, screenshot it and note the module name — that means a non-standard clone and I'll add its service UUID.

## 4. Robustness drills (the part that actually matters)

Run each; the app must recover **by itself** — no restarts, no crashes:

| # | Drill | Expected behavior |
|---|---|---|
| R1 | Power the **receiver** off 10 s, back on | Status → Connecting (console shows `[error] ... `), auto-reconnects, data resumes |
| R2 | Power the **TX** off (receiver stays on) | Link stays Connected; GGA shows fix quality 0 or the stream thins; position readouts freeze at last fix — and never jump |
| R3 | Walk the phone out of BT range (~10–30 m, house walls help), return | Watchdog trips within ~7 s → Connecting → auto-reconnect on return |
| R4 | Screen off / phone in pocket 5 min while connected | Notification stays; on unlock, console has continued (no gap at the end) |
| R5 | Switch to another app for 5 min | Same as R4 |
| R6 | Tap Disconnect in the notification | Service + notification gone, status Disconnected, last data still displayed |
| R7 | Kill the app from recents while connected | Service should die with it; relaunch shows last state, no crash |
| R8 | Connect with TX **on and locked**, confirm Home readouts | Lat/lon plausible (check against a maps app), altitude ≈ your elevation, sats ≥ 4 |

## 5. Capture for me

- Which module name(s) your Eggfinder Bluetooth units advertise
- ~30 seconds of raw console output with the TX locked (screenshot or transcription of a few sentences is enough) — I want the **exact sentence set** (GGA only? GGA+RMC? GSV? talker `GP` or `GN`?) to tune the parser and simulator against reality
- Any drill that misbehaved, with what the console/status showed

## Known limitations at v0.02 (not bugs)

- No map yet (Phase 4) and no compass/recovery screen yet (Phase 3) — Home shows numeric telemetry only
- Distance-to-rocket not shown (needs phone GPS wiring, Phase 3)
- The Eggfinder LCD itself keeps working normally while streaming — Bluetooth is a passive tap
