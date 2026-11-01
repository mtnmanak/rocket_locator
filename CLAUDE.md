# GPS Rocket Locator '26

Android app (Kotlin, Jetpack Compose) that receives NMEA GPS telemetry from model-rocket trackers over Bluetooth and guides the user to the landed rocket. GPL-3.0. Homage to Brune Studios' defunct "GPS Rocket Locator" (2013–2020), rebuilt clean-room.

> ## 🎫 Feedback / issue tracking — this repo keeps its OWN Issues tab
>
> Unlike the four browser tools, this app does **not** route into the central tracker. Its
> own Issues tab is its front door, and that is deliberate — see `docs/feedback-tracker.md`
> for why, and for the standing UI rulings if you build a feedback affordance in the app.
>
> **Keep Issues enabled here.** It is the one deliberate exception to the "disable Issues"
> rule that applies to every other tool repo.

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

## Delegation — standing rule (Eric, 2026-10-28)

Use **Codex `gpt-6.1-sol`** and **agy `gemini-4-argon-high`** wherever they can do the work, to save Claude tokens. The rule is
suspended only when Eric says in the session not to use Codex and/or agy.

- Codex: `node <codex plugin>/scripts/codex-companion.mjs task --fresh --model gpt-6.1-sol --effort high "<brief>"`.
  It is read-only unless `--write` is passed. `task --help` starts a real task, so don't call it.
- agy: `agy-delegate --model gemini-4-argon-high --dir <repo> --timeout 20m "<brief>"`. Never pass `--yolo`.
- Hand them bulk reads, finding, drafting tests and scripts, docs checks and claim checks. Claude keeps the judgement: it writes
  the briefs, adjudicates disagreements, and runs every gate itself (never trust a lane's self-reported pass). Claude takes
  their digests and doesn't re-read the files they've already handled.
- **Delegates read `AGENTS.md`, not this file** (agy also reads `GEMINI.md`). Both hold the delegate rules: the hard rules, the
  invariants, the gates and the report format. Name any CLAUDE.md section a task needs in its brief. Both are tracked
  (unlike MMRSim), so worktrees have them. Keep the shared part of `AGENTS.md` under 24,000 bytes; Gemini doesn't load past it.
- Pass bulk input as a FILE in the workspace, not pasted into the prompt. Ask for a short reply, with the details written to a
  file. A second, fresh Codex thread verifies anything a Codex `--write` thread wrote before it ships.
- Codex desktop plugin root: `C:/Users/Eric/.claude/plugins/cache/openai-codex/codex/1.0.6` (Eric's example); on another machine, locate the equivalent (e.g. `~/.claude/plugins/cache/openai-codex/codex/<ver>/`) and verify it and `scripts/codex-companion.mjs` exist before use. After a Codex update, stop the
  companion broker (`app-server-broker.mjs serve` and its `codex app-server` child): a stale broker rejects new model ids.
- agy: one call costs ~80k Gemini tokens even for a one-line question, so batch work into fewer, larger calls. It has no write
  grant, so treat it as read-only. Exit 15 means a tool was denied headless: rephrase the brief so it needs no commands.
  Don't add `--yolo`. Exit codes 10/11/12 are quota, auth and timeout.
- Usage limits: Codex prints *"You've hit your usage limit"* / `Turn failed`, and a job cut off that way writes no report.
  Recover its findings from `~/.codex/sessions/<yyyy>/<mm>/<dd>/rollout-*`. Then follow the fallback order (retry once, the other lane,
  then Claude) without asking Eric.
- `docs/AUDIT_PROMPT.md` applies this rule to the full engineering audit.

## Modules

- `:core` — NMEA parser, geodesy, `FlightTracker` state machine, flight simulator
- `:transport` — `NmeaSource` flow abstraction: `ClassicSppSource` (HC-06/SPP), `BleUartSource` (FFE0/FFE1), `SimulatorSource`
- `:app` — Compose UI, `TelemetryRepository` (process-wide pipeline, owned by Application), `TrackingService` (connectedDevice foreground service)

## Target hardware

Eggfinder RX/LCD and Missile Works T3/RTx: NMEA over HC-06 Bluetooth Classic SPP, 9600 8N1, PIN 1234. HM-10/BT-04-style BLE modules: UART service FFE0, characteristic FFE1, 20-byte notification fragments. Featherweight: proprietary BLE, deferred post-1.0. Eggtimer LR series (LoRa, replacing Hope RF from ~end 2026): phone link unknown until the LCD-LR ships; support both generations (see `docs/research-findings.md` §2).

## Build

Requires JDK 17 + Android SDK ~~35~~ platform 37 (write `local.properties` with `sdk.dir=...`; forward slashes).

```
./gradlew :core:test :transport:test :app:testDebugUnitTest   # unit tests (~~208+~~ 354+, must stay green)
./gradlew :app:assembleDebug           # debug APK
```
CI also runs ktlint 1.8.0 and detekt 1.23.8 via checksum-verified CLIs; `config/detekt/baseline.xml` holds 103 pre-existing issues.

Windows dev note: files created on Windows lack the executable bit — any new shell script needs `git update-index --chmod=+x <file>`.

## Releases

`git tag v0.NN && git push origin v0.NN` → CI tests, builds, signs, publishes a GitHub Release with asset `rocket-locator-26.apk` (stable name — the website download button depends on it). Signing uses GitHub secrets; the keystore itself lives outside the repo and must never be committed (`.gitignore` covers it). Local release signing needs `keystore.properties` at the repo root (machine-local).

Website: https://www.mountainmanrockets.com/rocket_locator/ — source of truth is
`site/deploy/index.html`, currently served from a hand-uploaded WordPress
subfolder. **That subfolder is END-OF-LIFE**: mountainmanrockets.com is being
replatformed (Astro on Cloudflare Pages, repo `mountainmanrockets`), WordPress
dies at DNS cutover, and the new site 301s `/rocket_locator/` to its
`/online_tools/` page, where the app has an ALPHA tile. So: do NOT spend effort
on the WP copy beyond emergencies, and do NOT re-upload there as a matter of
course. The promo page's future home (a real page on the new site, or GitHub as
the canonical landing) is an open decision — raise it with Eric before
redesigning `site/deploy/`. The APK download button's contract is unchanged:
the GitHub Release asset name `rocket-locator-26.apk` must stay stable.
