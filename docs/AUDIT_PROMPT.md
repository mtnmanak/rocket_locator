# Reusable audit prompt — Rocket Locator (`:core`, `:transport`, `:app` and the release pipeline)

Paste the block under **The prompt** into a Claude Code session to run the full engineering audit that produces
`docs/audit/AUDIT.md`. It sets out how to slice the code, what each slice hunts for, how findings are verified, which gates to run,
how to close out the audit, and where known false positives are recorded, so that one run can be compared with the next.

**Written 2026-10-28**, adapted from the MMRocket Sim audit prompt (revised there on 2026-10-25 after nine runs). Kept from that
prompt: the cross-model finder lanes, verifiers that default to REFUTED, the severity scale with a concrete failure scenario,
measured baselines, a § Cleared list that is never re-raised, the claim check, and the worktree inventory. Dropped: the web stack
(npm, Vite, React, three.js, service worker, CSP, the API Worker, the data pipeline) and the `open-items.md` board and register,
which this repo doesn't have. Here, open items live in the newest `docs/handoff-*.md`. Added, because a field-recovery app has
failure modes a simulator doesn't:
- **field-safety invariants**, fuzzed rather than reasoned about
- **NMEA/GNSS and geodesy correctness**, including true vs magnetic north and short-range precision
- **Bluetooth Classic/BLE transport lifecycle**
- **Android process, foreground-service, Doze and permission behaviour** across API 26 to the current release
- **release signing and supply chain**
- **clean-room licence provenance**
- **Play and F-Droid policy**
- **location privacy**
- **sunlight and glove usability**
- **phase readiness**: what the next roadmap phase will break

**This file carries no baseline and no findings list.** Both live in `docs/audit/AUDIT.md` (§ Baseline, § Cleared) and are
re-measured on every run.

Keep this file current: when a run changes the method (a new slice, a new tool, a ruling), edit this prompt in the same sitting.

---

## The prompt

> Do a full engineering audit of **Rocket Locator** (this repo: an Android app in Kotlin and Jetpack Compose that reads NMEA GPS
> telemetry from model-rocket trackers over Bluetooth Classic SPP or BLE UART and guides the user on foot to the landed rocket;
> GPL-3.0, clean-room). Scope:
> - all three modules: `:core` (pure Kotlin NMEA parser, geodesy, `FlightTracker`, simulator), `:transport` (`NmeaSource`,
>   `ClassicSppSource`, `BleUartSource`, `NmeaLineAssembler`, `ReconnectPolicy`, `SimulatorSource`) and `:app` (Compose UI,
>   `TelemetryRepository`, `TrackingService`, the manifest and resources)
> - the Gradle build: root and module `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper, `proguard-rules.pro`,
>   `gradle.properties`
> - both `.github/workflows` (`ci.yml`, `release.yml`) and the signing path
> - `.gitignore` and `.gitattributes`, including the vendored-brand rule
> - the docs that steer sessions (`CLAUDE.md`, `AGENTS.md`, `docs/`, `CHANGELOG.md`, `README.md`)
> - the download contract on `site/deploy/index.html`: the asset name `rocket-locator-26.apk` only. The WordPress copy is
>   end-of-life; don't audit its design.
>
> Out of scope: the vendored brand files (`docs/brand/**`, launcher `mipmap*`/`drawable/ic_launcher_*`,
> `values/mmr_colors.xml`), which are byte-checked by `sync-brand --verify`. Report drift only; never hand-edit them. Also out of
> scope: `../mmr-brand` and the gitignored research artifacts (`appicon.png`, `eggfaq.html`, `rl_field2.html`,
> `cdx_download.txt`).
>
> Cover:
> - security and location privacy
> - field-safety: anything that crashes, hangs, freezes or misleads during a recovery
> - correctness: a wrong position, distance, bearing, altitude or staleness reading that a user would walk on
> - Bluetooth and Android lifecycle robustness
> - bad or over-complex code, and architecture
> - missing unit tests, dead code, linting and tooling gaps, and convention drift
> - accessibility and outdoor usability
> - performance and battery
> - locale and unit handling
> - release, signing and supply chain
> - licence provenance
> - store policy readiness
> - phase readiness
>
> **Threat model.** This app has no server, no account and (today) no `INTERNET` permission. The untrusted inputs are:
> - **radio bytes**: any nearby BLE peripheral advertising FFE0, any paired SPP device, a lookalike name, or a tracker emitting
>   garbage or binary at 9600 baud for hours
> - the OS itself: permission revoked mid-session, Bluetooth toggled, process killed, Doze, a notification-action intent
> - later phases add: shared or typed coordinates, `geo:` share-target intents, sideloaded MBTiles/PMTiles (an untrusted SQLite
>   file), tile servers, and KML/GPX export
>
> The assets are the user's location history, the **last-known rocket position** (losing it can lose a rocket) and the
> **release signing key** (a key change breaks `adb install -r` upgrades for every existing user; a leaked key lets anyone ship
> an "update").
>
> **Start by reading:** `CLAUDE.md` (hard rules), `docs/build-plan.md` (locked decisions, design principles, phase status),
> `docs/research-findings.md` (hardware protocols; the original app's bugs to avoid), `docs/bench-test-protocol.md`, the newest
> `docs/handoff-*.md` (its "Open in this repo" and "Do not" tables), `CHANGELOG.md`, and the memory index
> (`MEMORY.md` under the project's Claude memory folder).
> If the memory index or a named memory is unavailable on this machine, say so in § Method and use the tracked substitutes (handoff "Gotchas" and `CLAUDE.md`), without inferring missing memory contents.
> Then read the previous `docs/audit/AUDIT.md` if one exists: its
> § Baseline (the figures to measure drift against), § Cleared (refuted findings; never re-raise one unless the code changed) and
> § Recommended order of attack. Also read the previous run's working folder `.claude/audit-<date>/` (its scripts and briefs);
> copy them into `.claude/audit-<today>/`, repoint paths and dates, re-use them, and say in § Method what changed.
> **On the first run** none of this exists: say so in the head, write the scripts fresh (`baseline.sh`, `gate.sh`, `assign.sh`,
> `collect`, `verdicts`, `fuzz`), and leave every "then" column in § Baseline as `—`. An item already listed as open in the
> newest handoff is not a new finding. Cite it as `tracked: handoff-<date>` and raise it only if the audit finds it worse than
> the handoff says.
>
> **Output location: confirm before writing anything.** The repo is **public** and `docs/` is **tracked**, unlike MMSim, where
> `docs/` is local-only. A findings file in a tracked path publishes unfixed security and field-safety holes on the next push.
> Run `git check-ignore -v docs/audit/AUDIT.md .claude/audit-x/x`; if either path is not ignored, add `docs/audit/` and
> `.claude/audit-*/` to `.gitignore` first, and commit only that line. Never put an unfixed HIGH/MED security finding in a GitHub
> Issue, PR body, commit message, CHANGELOG entry or handoff. The handoff cites it as `docs/audit/AUDIT.md <id>`, nothing more.
>
> **Gates: run them; never quote earlier results.**
> - `export JAVA_HOME=/c/Users/Eric/tools/jdk17` (memory `jdk17-location`), or the JDK 17 on this machine; validate with `java -version` first.
> - Delete `*/build/test-results` first: a failed Gradle run leaves stale XML that looks like a pass.
> - Unit tests: `./gradlew :core:test :transport:test --rerun-tasks`. Count tests from the JUnit XML, as unique tests and as
>   reported (`:transport` runs ×2 variants), and compare with `CLAUDE.md` ("208+").
> - `./gradlew :app:assembleDebug`
> - `./gradlew :app:assembleRelease`: R8 minify and shrink run only here. An unsigned build is fine without `keystore.properties`;
>   say which you got.
> - `./gradlew lint`: record the counts by severity and id from `*/build/reports/lint-results-*.xml`, and list every `@SuppressLint`.
> - `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`: save the tree, then diff it against the previous run.
> - `./gradlew --warning-mode all help`: count deprecations that will break on the next Gradle or AGP major.
> - GitHub:
>   - `gh run list --limit 10` for the CI history
>   - `gh api repos/mtnmanak/rocket_locator/rulesets` and `.../branches/main/protection`
>   - `gh api repos/mtnmanak/rocket_locator/private-vulnerability-reporting`
>   - `gh secret list` (names only)
>   - `gh release view --json assets,tagName` on the latest release
> - Release artifact: download the latest `rocket-locator-26.apk` into the working folder and run
>   `apksigner verify --print-certs`. Record the signer SHA-256, which must match the previous run's; a change is HIGH. Also check
>   that `aapt2 dump badging` gives a versionCode and versionName matching the tag and `CHANGELOG.md`.
> - Afterwards, `./gradlew --stop`.
>
> Record exit codes and counts in `gate.txt`. A red gate is an orchestrator finding (`O-<n>`), verified like any other. Run one
> Gradle build at a time; the daemon and Kotlin compiler are memory-heavy.
>
> **Method: fan out by concern, not by file count.** Production code is roughly 4,000 lines (`:core` ~1.1k, `:transport` ~0.9k,
> `:app` ~1.9k), so every finder can read the **whole** production tree plus its slice's tests. Assign every tracked non-test
> `.kt` file and the manifest as *primary* to exactly one slice **by script** (`assign.sh`, keyed on package path), so that
> nothing is unowned, and print the assignment in § Method. If a module grows past ~10,000 lines, sub-slice it.
>
> **Delegation saves Claude tokens: use Codex and agy wherever they can do the work.** This is the standing rule for every run.
> It's suspended only when Eric says in the session not to use Codex and/or agy; then that lane's work moves to Claude
> subagents, and § Method says so. The two lanes and their exact invocations:
> - **Codex `gpt-6.1-sol`:**
>   `node <codex plugin>/scripts/codex-companion.mjs task --fresh --model gpt-6.1-sol --effort high "<brief>"`.
>   It is read-only unless `--write` is passed; pass `--write` only for the write tasks named below. Don't call `task --help`:
>   it starts a real task.
> - **agy `gemini-4-argon-high`:**
>   `agy-delegate --model gemini-4-argon-high --dir <repo> --timeout 20m "<brief>"`.
>   It is read-only; every prompt says "do not run commands". Never pass `--yolo`. Use `--digest` when only a summary is needed.
>
> Default to one of these two models for every bulk job:
> - finding (all slices and rounds)
> - LOW/INFO verification, and the second lens on every HIGH/MED
> - drafting the fuzz harness and repro tests (Codex `--write`, confined to `.claude/audit-<today>/`)
> - first drafts of the working-folder scripts
> - docs checking and docs writes
> - the claim check and the fix-verify pass
>
> Claude keeps the judgement calls:
> - writing briefs
> - running every gate and test itself (never trust a lane's self-reported pass; agy has been seen altering its own
>   environment to force one)
> - the first lens on every HIGH/MED, security, privacy or field-safety candidate
> - adjudicating contested rows
> - writing the report and handoff
>
> **Claude never re-reads files a lane has already digested.** It reads only the cited lines it has to judge. A lane that fails
> (quota, auth, timeout; agy exit codes 10/11/12; exit 15 means a tool was denied headless, so fix the brief, never add `--yolo`) is retried once. If the retry also fails, the work moves to the other lane, and
> to a Claude subagent only if both lanes are down; § Method records the substitution. Lanes can run in parallel: up to 4 Codex
> plus 2 agy at once, and never alongside a Gradle build.
>
> Finder lanes:
> - **Codex `gpt-6.1-sol`** is the first finder for every slice.
> - **agy `gemini-4-argon-high`** is the second finder for every slice.
> - A **second Codex round** for slices 1–4 is told what round one found and cleared, and hunts for what it missed
>   (`ROUND2.md`).
>
> Record the model ids actually used. If a model id has been retired, use the nearest successor and say so; never silently fall
> back to a default tier. Bulk input goes to agents as files in the workspace, never pasted. Each finder reads its
> files fully and traces callers, threads and guards before reporting. It reports in `COMMON.md`'s format, ranked and
> findings-only: `severity | file:line | issue | why it matters (concrete input or sequence → wrong output) | fix`. After the
> findings come "Checked and clean" with citations, then "Uncertain", then the status of every previous-audit row in its slice.
>
> **Severity scale (state it in every brief):**
> - **HIGH**: a crash, hang or frozen UI during a recovery; a wrong position, distance or bearing on a common path (one that would
>   walk someone the wrong way, or past the rocket); loss of the last-known rocket position; a stale fix shown as live; exposure of
>   the signing key or secrets; or a store or OS policy break that blocks install or update.
> - **MED**: a wrong result or crash on a plausible input or sequence (a real tracker's sentence mix, a permission revoked, a
>   Bluetooth toggle, process death), or a bounded security or privacy weakness.
> - **LOW**: a narrow edge case, or a maintainability problem with a concrete cost.
> - **INFO**: worth knowing; no defect today. Phase-readiness items go here unless the next phase is already in progress.
>
> A finding without a `file:line` at HEAD and a concrete failure scenario is not a finding. Style preferences are not findings.
> Architecture claims survive only with a concrete cost.
>
> **Slices** (these are concern families; `assign.sh` derives the primary file lists, so never hand-list files in this prompt):
> 1. **Radio bytes → rocket position (field-safety core):** the line assembler, NMEA parser and models, `FlightTracker`,
>    `RocketState`. Hunt for:
>    - any path where parsing throws, loops or allocates without bound on arbitrary bytes: overlong lines, no `\n` for hours, NUL
>      and high bytes, `$` mid-line, a CR-only terminator, a lowercase or missing checksum, `*` with no hex, a field count too
>      short or too long, `Int`/`Double` parsing of `""`, `"-"`, `"1e999"` or `"NaN"`
>    - `ddmm.mmmm` conversion: minutes ≥ 60, missing hemisphere, `S`/`W` sign, three-digit longitude degrees, ±0, and values
>      beyond ±90/±180 reaching `GeoPoint`
>    - GGA fix quality 0–8: whether 6 (dead reckoning), 7 (manual) or 8 (simulation) moves the rocket; it must not unless that is
>      decided and documented
>    - RMC status `V` and GLL status `V` moving the position
>    - a no-fix sentence anywhere in a GGA/RMC/GLL interleave moving the position
>    - GN vs GP duplicate sentences double-counting path points
>    - UTC midnight rollover and missing dates in staleness and ordering
>    - altitude: MSL vs geoid separation, units other than `M`, and max-altitude latching on a glitch fix (a single 99,999 m
>      sentence)
>    - the AGL baseline after `resetAltitudeBaseline`
>    - path-history cap behaviour, and `restoreLastPosition` vs a later no-fix sentence
>
>    **Fuzz, don't reason:** write a throwaway JUnit test in the working folder (not under `src/` unless it becomes a finding's
>    fix) that drives at least 10⁶ random and mutated sentences (seeded from the test corpus and from
>    `docs/research-findings.md` samples) through `NmeaLineAssembler` → `NmeaParser` → `FlightTracker`. Assert: no throw; the
>    position changes only on a valid fix; lat/lon stay finite and in range; the path stays at or below its cap. Report the seed
>    and the time taken.
> 2. **Geodesy, units and every displayed number:** `Geodesy`, `Units`, `GeoPoint`, every formatter in `:app`, and the simulator
>    (`SyntheticFlight`, `NmeaLogReplay`). Hunt for:
>    - haversine precision at 1–50 m (the last walk to the rocket) and at 0 m
>    - the antimeridian; within 1 km of either pole
>    - bearing normalisation to [0, 360) and relative-bearing wrap at ±180
>    - Earth-radius choice and its error budget at recovery ranges
>    - known-answer tests that came from an independent source (GeographicLib, Vincenty) rather than from the code under test
>    - metre/foot and knot conversions applied twice or never
>    - **true vs magnetic north**: GPS bearings are true and the phone compass is magnetic, so Phase 3 needs
>      `GeomagneticField` declination. Flag any code or doc that assumes they're the same.
>    - formatting: `String.format` and `"%.2f".format` without `Locale.US` give a decimal comma on a German phone, which breaks any
>      copy/paste/share path and any later re-parse. Decide which strings are display (user locale) and which are interchange
>      (`Locale.US`), and report drift.
>    - DMS vs decimal-degrees display
>    - simulator realism: does `SyntheticFlight` emit the sentence mix and rates real Eggfinder/T3 receivers send (per
>      research-findings §2), or does it only exercise the happy path?
> 3. **Transports:** `ClassicSppSource`, `BleUartSource`, `ReconnectPolicy`, `NmeaSource`, `SimulatorSource`. Hunt for:
>    - **cancellation**: a blocking RFCOMM `read()` that ignores coroutine cancellation unless the socket is closed; `callbackFlow`
>      without `awaitClose`; `gatt.disconnect()` without `gatt.close()` on **every** path (leaked GATT clients cause status-133
>      failures later)
>    - GATT callbacks on binder threads touching shared state without confinement
>    - CCCD write ordering vs MTU request
>    - the notify-without-CCCD clone fallback
>    - 20-byte fragments split mid-sentence, and mid-multibyte noise
>    - status 133/8/19/22 handling and the retry vs give-up decision
>    - `autoConnect` semantics
>    - adapter turned off and back on mid-session; a `SecurityException` when `BLUETOOTH_CONNECT` is revoked mid-session (every
>      `@SuppressLint("MissingPermission")` must have a checked caller; name it)
>    - backoff: cap, jitter, reset-on-success, and that it surfaces as CONNECTING, never DISCONNECTED (CLAUDE.md invariant)
>    - the no-data watchdog vs a tracker legitimately silent before lock
>    - `Dispatchers.IO` starvation
>    - two sources connected at once
>    - reconnect storms draining the battery
>    - **say explicitly which behaviours are verified only on the bench simulator and not on hardware** (the Hope RF Eggfinder
>      bench test is still not run)
> 4. **App pipeline, process and OS lifecycle:** `RocketLocatorApp`, `TelemetryRepository`, `TrackingService`, `MainActivity`,
>    the ViewModels, `AndroidManifest.xml`. Hunt for:
>    - **the DISCONNECTED-is-terminal invariant**: every transition into and out of it, and what `TrackingService` does on each
>    - scope ownership (Application scope vs `viewModelScope`); an uncaught coroutine exception killing the process in the field
>      (is there a `CoroutineExceptionHandler` or `SupervisorJob` where it matters?)
>    - `StateFlow` conflation where loss matters (console, path) vs where it doesn't (UI); `SharedFlow` buffer overflow policy;
>      console ring-buffer cap
>    - the foreground service:
>      - `startForegroundService` → `startForeground` within the deadline on every branch
>      - `ForegroundServiceStartNotAllowedException` when started from the background on Android 12+
>      - a manifest `foregroundServiceType` that matches what's started. Today it is `connectedDevice` only while
>        `FOREGROUND_SERVICE_LOCATION` is declared; Android 14+ requires the runtime permission **before** starting with a
>        `location` type.
>      - `POST_NOTIFICATIONS` on 13+ and what happens when it's denied
>      - notification-action `PendingIntent` flags; `stopSelf` races; `singleTask` relaunch from the notification
>    - Doze and App Standby with the screen off for an hour; battery-optimisation exemption (needed? policy-allowed?)
>    - process death:
>      - what survives (today the last-known position is in memory only; the build plan promises it "always restored on
>        launch", so check whether that has shipped)
>      - what a user sees after the OS kills the app mid-recovery
>    - `allowBackup="true"` with no `dataExtractionRules`/`fullBackupContent`: what location data would go to cloud backup or
>      device transfer once persistence lands
>    - exported components; intent extras trusted without validation
>    - logging of coordinates to logcat in release builds
>    - battery: wake behaviour, sensor and location update rates
> 5. **Compose UI, accessibility and field usability:** `HomeScreen`, `DevicesScreen`, `AppNav`, `Theme`, `res/values*`. Hunt for:
>    - recomposition at NMEA rate (1–10 Hz): unstable params, lambdas re-allocated per frame, `collectAsState` without lifecycle
>      (`collectAsStateWithLifecycle`), heavy work in composition
>    - state that's lost on rotation or theme change (portrait is locked, but a dark-mode toggle or locale change still recreates
>      the activity)
>    - **staleness truthfulness**: is a fix that is minutes old visibly different from a live one, by more than colour alone?
>    - TalkBack labels on icon buttons and the connection chip; live-region announcements for connect/lost; font scale at 200%
>      (do readouts clip?)
>    - touch targets ≥ 48 dp, and usable with gloves
>    - sunlight contrast for both themes (per handoff, dark washes out sooner; numbers, not vibes, from the brand tokens)
>    - errors that dead-end the user (Bluetooth off, permission permanently denied: is there a route to Settings?)
>    - hard-coded strings outside `strings.xml`
>    - one **TalkBack-only end-to-end pass**: grant permissions, pick a device (or simulator), connect, read distance and staleness,
>      disconnect
> 6. **Build, release, supply chain, licence and policy (whole repo):**
>    - **Signing:**
>      - `git log --all --full-history -- '*.jks' '*.keystore' 'keystore.properties' '*.p12'`, plus a grep of history for
>        `storePassword`/`SIGNING_`, to confirm nothing was ever committed
>      - `keystore.properties` and `local.properties` ignored
>      - `release.yml` decodes the keystore **before** running tests, so third-party build code runs with the key on disk; say
>        whether reordering is worth it
>      - the keystore file cleaned up after the job
>      - secrets passed only to the steps that need them
>      - `permissions: contents: write` at workflow level vs job level; `ci.yml` has no `permissions:` block
>    - **Supply chain:**
>      - actions pinned by tag, not by SHA (state which and the fix with release-tag comments)
>      - no Gradle wrapper validation (`gradle/actions/wrapper-validation`)
>      - no `gradle/verification-metadata.xml`
>      - no Dependabot or Renovate; age of AGP, Kotlin, Compose BOM, coroutines and lifecycle vs current stable
>      - plugin and repository declarations in `settings.gradle.kts` (any `mavenLocal()` or JitPack?)
>      - the `ubuntu-latest` image move noted in the handoff
>    - **Release contract:**
>      - tag ⇄ `versionName` ⇄ `versionCode` ⇄ `CHANGELOG.md` ⇄ `strings.xml footer_version` (duplicated by hand today; say
>        whether a build-time check or a `BuildConfig`/resValue source is the fix)
>      - `versionCode` monotonic across tags
>      - the asset name `rocket-locator-26.apk` stable and matching the `site/deploy` link
>      - `generate_release_notes` vs the changelog
>      - CI uploads a debug APK; does anything ever exercise the R8 release build before a tag push?
>    - **R8:** an empty `proguard-rules.pro` is correct only while the code is reflection-free. List anything that will need keep
>      rules (enum `valueOf` from strings, serialization, MapLibre later).
>    - **Policy:**
>      - `targetSdk` vs Google Play's **current** target-API deadline (check the date; don't assume)
>      - behaviour changes at the next API level (edge-to-edge enforcement, predictive back, foreground-service and Bluetooth
>        changes)
>      - `BLUETOOTH_SCAN neverForLocation` truthfulness
>      - Play's foreground-service-type and location declarations
>      - **F-Droid readiness**: no Google Play Services, Firebase or proprietary artifacts in the runtime classpath (this matters
>        when Phase 3 picks a location API); reproducible-build blockers
>    - **Licence and clean room:**
>      - GPL-3.0 notice present; every dependency licence compatible with GPL-3.0
>      - the `:core` purity check, mechanically: `rg -n '^import (android|androidx|kotlinx\.coroutines|java\.io|java\.nio)' core/src/main`
>        must be empty; also list `java.*` imports in `:core` as KMP-readiness INFO
>      - **provenance:** grep for distinctive identifiers, comments, constants and structure from `fantasiiio/RocketLocator`, its
>        BlueGPS4Droid lineage (`org.broeuschmeul`) and `bdureau/SimpleModelTracker`, reading them as specs only. Any
>        resemblance beyond what the NMEA spec forces is HIGH. Any BearConsole2-derived code needs attribution.
>      - `SECURITY.md` or private vulnerability reporting enabled: the repo is public with Issues on by design, so is there a
>        non-public route for a security report?
>    - **Tooling delta:**
>      - ktlint and detekt were planned in Phase 0 and are absent
>      - Android Lint: no `lint.xml`, no baseline, `abortOnError` defaults, `warningsAsErrors`
>      - Kotlin `allWarningsAsErrors`; explicit API mode for `:core`
>      - no coverage tool: run Kover or JaCoCo once (or say why not) and list modules by line coverage, not by sibling-file
>        presence
>      - **`:app` has zero unit tests**: name the logic in `TelemetryRepository` and the ViewModels that is testable on the JVM
>        today, and what would need Robolectric or instrumentation
>      - give a concrete minimal lint and format setup as a DELTA, with counts measured by running it
>      - check `.gitattributes`/CRLF on scripts, `gradlew` executable bit (`git ls-files -s gradlew`), and any new shell script
>        (CLAUDE.md Windows note)
> 7. **Docs continuity:** each check below, with its file:line.
>    - every item in the newest handoff's "Open in this repo" is still open, or is closed by a commit or tag (cite it)
>    - every `CHANGELOG.md` claim matches the code at its tag (`git show v0.NN:<file>`)
>    - `docs/build-plan.md` phase status vs reality (which exit criteria are met, and the stale "private repo during beta" line)
>    - `CLAUDE.md`'s own facts: the test count "208+", module list, build commands, hard rules each enforced by code or a test
>      (name it)
>    - `AGENTS.md` (~~untracked~~ tracked) drift vs `CLAUDE.md`, diffed line by line
>    - README claims vs the shipped app (name, features, install steps)
>    - `docs/bench-test-protocol.md` vs the install rule: it says "sideload `app-debug.apk`", but a debug build can't install over
>      the release-signed app without an uninstall, which wipes data (memory `phone-install-release-signed`)
>    - the naming mismatch ("Rocket Locator" vs "GPS Rocket Locator '26") listed, not resolved (it's Eric's call)
>    - memory files with stale facts or dangling `[[links]]`
>    - `docs/feedback-tracker.md` ruling respected (no new issue-tracker design; keep Issues enabled)
>
>    Rules: strike, never delete; a handoff names open items and never copies the audit; UTF-8 and emoji intact (no PowerShell
>    redirection).
> 8. **Worktrees, branches and working folders: inspect and PROPOSE, never delete unasked.** Inventory:
>    - `git worktree list --porcelain`, plus `git worktree prune --dry-run`
>    - local and remote branches with merged status (`git branch -a --merged main`; `brand-v1` was "safe to delete" per the
>      27 Oct handoff)
>    - `.claude/` working folders and Codex/agy staging (`.agy/`, `.gemini-inputs/`)
>    - Gradle debris (`build/`, `.kotlin/`, `.gradle/`) with sizes
>    - any sibling worktree root
>
>    For each entry give its kind, date, size, dirty state (`git -C <wt> status --porcelain`), what still cites it, and junctions
>    (find them with PowerShell `Get-ChildItem -LiteralPath <p> -Force | Where-Object { $_.Attributes -match 'ReparsePoint' }`;
>    an empty result counts only once the check has been shown to find a known positive). Output the table
>    `| entry | kind | date | size | branch / merged | dirty? | cited by | verdict (DELETE / KEEP / COPY-OUT-THEN-DELETE / ASK) | why |`
>    and ONE item in the handoff asking Eric to approve it. Never delete a remote branch or a tag unasked.
>
> **Optional device pass (only when Eric says a phone is connected and free to use).** Rules from memory and the handoff:
> - install with `:app:assembleRelease` + `adb install -r` **only**; never uninstall (it wipes the rocket position)
> - never toggle Eric's Bluetooth, location or Wi-Fi without asking
> - prefer the Simulator source
>
> Then, with `adb`, check each of these and record what survives and what the user sees:
> - screen off for 30 minutes with the service running (`dumpsys activity services`)
> - forced Doze (`dumpsys deviceidle force-idle`)
> - `am kill` the process mid-session, then relaunch
> - `pm revoke … BLUETOOTH_CONNECT` mid-session (with consent)
> - TalkBack on the end-to-end pass
> - font scale 2.0 (`settings put system font_scale 2.0`, restored afterwards)
>
> **Previous rows.** On a second or later run, build `PREVIOUS-ROWS.md` from the previous `docs/audit/AUDIT.md` findings tables.
> Every sweep reports, for each previous row in its slice, one of:
> - **FIXED**: name the guard, and say whether a test pins it. A FIXED row with no test is a `tests` finding.
> - **PARTIAL**: fixed on one path but not another. Also a finding, "carried: `<id>`".
> - **OPEN**: also a finding.
> - **UNSURE**
>
> Consolidate them into § The previous audit's rows.
>
> **Verification: nothing a finder wrote reaches the report unverified, and no verifier works in the finder's own thread.**
> - Merge candidates by file and line (±8) across finders and rounds, keeping both wordings.
> - Every HIGH, MED, security, privacy or field-safety candidate gets two independent lenses, both defaulting to REFUTED:
>   - a **Claude** guards-and-callers lens (including which thread or dispatcher runs it), reading only the cited code paths
>   - a **Codex `gpt-6.1-sol`** reproduce-from-source lens in a fresh thread, which must not be the finder's own thread
>
>   **Where a JVM test can reproduce it, Codex drafts the test** (`--write`, in the working folder) and **Claude runs it**. A
>   passing reproduction is the verdict.
> - Every carried previous row gets a regression lens: did the fix land on every path the row named?
> - Every LOW/INFO candidate gets two lenses in fresh threads: Codex `gpt-6.1-sol` (reproduce), then agy
>   `gemini-4-argon-high` (guards). The first verdict is shown to the second so it can disagree. The verifier sets the severity;
>   the finder's severity is only a claim.
> - When a claim is about time, size, rate or memory, measure it (time the loop, count the allocations, run the fuzz) rather
>   than reason about it.
> - Bluetooth and OS-lifecycle claims that no JVM test can reproduce are marked **UNVERIFIED-ON-DEVICE** with the exact device
>   step that would settle them. They go on the bench or device checklist, not in the findings as fact.
> - A contested row (the lenses disagree) is adjudicated by the orchestrator in writing, reading the code.
> - Docs findings get one checker per five (Codex `gpt-6.1-sol`, falling back to agy `gemini-4-argon-high`), told to refute by
>   re-measuring counts and re-reading the cited lines.
>
> **Deliberate decisions: not findings unless the code contradicts the stated reason.** Re-verify cheaply; re-raise only if the
> code changed or the reason no longer holds. Also read § Cleared in the previous audit. Standing ones:
> - Issues enabled on this repo, and no in-app "Report a problem" yet (`docs/feedback-tracker.md`)
> - dynamic colour off (brand), and the vendored brand files with `docs/brand/** -text`
> - portrait-only `MainActivity`
> - no `INTERNET` permission. **Keep it load-bearing:** any network code is a finding until a phase introduces it on purpose.
> - `NmeaLineAssembler` decodes ISO-8859-1 so that arbitrary bytes never throw
> - `@SuppressLint("MissingPermission")` on the sources, with the permission checked by the caller (verify the caller still
>   checks)
> - Featherweight deferred to after 1.0
> - Hope RF Eggfinder support never dropped or demoted for the LR series
> - the WordPress promo page end-of-life (don't redesign `site/deploy/`; raise the promo-page decision with Eric instead)
> - exports and `internal` members used only by tests (INFO at most)
>
> **Phase readiness (a separate section; INFO unless the phase is in progress).** For each upcoming phase in
> `docs/build-plan.md`, list the concrete things in today's code or config that will break or need rework when it lands, each
> with `file:line` and the scenario:
> - **Phase 3:** `location` foreground-service type and its runtime-permission order; compass sensor fusion and declination;
>   persistence of the last-known position (DataStore vs file; survives process death; backup rules); manual coordinate entry
>   (DMS and decimal comma, hemisphere letters, paste from other apps, `geo:` intents as untrusted input); TTS and audio focus
> - **Phases 4–5:** MapLibre and the `INTERNET` permission arriving; tile-source licensing and no scraping (design principle 2);
>   MBTiles/PMTiles as an untrusted SQLite or zip file (size caps, path traversal on import, SQL from the file never executed);
>   offline storage limits
> - **Phase 6:** KML/GPX export escaping (XML text and attributes in names and descriptions); share-sheet `FileProvider` scope;
>   log-to-file location and retention
> - **Phase 7:** crash reporting that respects privacy (ACRA or similar; no location in reports by default)
> - **LR series:** where a new link or parser slots into `NmeaSource` without touching tracking, and what is hard-wired to
>   NMEA-over-Bluetooth today
>
> **Output: `docs/audit/AUDIT.md`, gitignored (confirmed above), never a tracked path.** First move any previous file to
> `docs/audit/archive/AUDIT-<its date>.md` as a byte copy, and quote its SHA-256. Keep the skeleton so that runs compare:
> - **Head:** date, HEAD sha, version and versionCode, counts by severity and category, previous-rows result, the accounting of
>   finders and verifiers (model ids), patterns, orchestrator adjudications, and local state vs code.
> - **§ Baseline:** Tooling, Size (production and test lines per module), Tests (unique and reported, per module), Lint counts,
>   Coverage, Dependencies (versions and age), Release (signer SHA-256, APK size, `minSdk`/`targetSdk`), Clean signals (`:core`
>   purity grep, no `INTERNET`, no secrets in history), and Re-measure with. Every now-column figure is measured by `baseline.sh`
>   or `gate.sh` in this run. The previous column is copied from the previous audit; a now-figure is never copied forward.
> - **§ Method**, including the slice assignment and the fuzz seed and duration.
> - **§ The previous audit's rows.**
> - **Findings by category** with the severity emojis: 🔴 security and privacy, ⛑️ field-safety, 🟠 architecture and tooling and
>   release, 🟡 correctness and tests and a11y, 🟢 dead code. Each row is `Sev | Where | Issue | Scenario | Fix | Verdict — id`.
> - **§ Unverified on device:** the bench and device checklist.
> - **§ Phase readiness.**
> - **§ Cleared:** every refuted candidate with the refuting reason.
> - **§ Docs audit.**
> - **§ Worktree clean-up.**
> - **§ Recommended order of attack:** stepwise, front-loading small verified fixes and field-safety, deferring large refactors.
>   Count each step's rows and severities.
>
> Date-stamp it.
>
> **Landing it in the same sitting, or the audit did not happen:**
> - Write `docs/handoff-<date>.md` that supersedes the previous one. It carries forward that handoff's still-open items, adds the
>   order of attack as rows that cite `docs/audit/AUDIT.md <id>` (no security detail in the tracked file), and adds the worktree
>   approval row.
> - Apply the verified docs findings: two Codex `gpt-6.1-sol --write` tasks on disjoint files, then a fresh Codex thread checks
>   them against the pre-edit copies and the finding list. Claude reviews the diff, not the files.
> - Re-baseline `CLAUDE.md`'s counts (the test count) only if they're measured this run.
> - Don't touch `CHANGELOG.md`; it records shipped fixes, not findings.
> - Then **claim-check the written report and the handoff with a fresh Codex `gpt-6.1-sol` thread**: every number, id, date, sha
>   and who-did-what (`CLAIM-CHECK.md`). Apply the must-fix list, and have a fresh agy `gemini-4-argon-high` thread verify the
>   fixes.
> - Commit only the tracked docs edits and the `.gitignore` line, on a branch, and say what was committed. Don't push or open a
>   PR unless Eric asks.
> - Say at the end that `docs/audit/` and `.claude/audit-*/` are local-only and do not sync.

---

## Where the facts live (not here)

- **Baseline for drift:** `docs/audit/AUDIT.md` § Baseline. The previous now-column becomes the next run's "then" column.
- **Refuted claims not to re-raise:** `docs/audit/AUDIT.md` § Cleared.
- **Previous rows and their fix status:** `docs/audit/AUDIT.md` § The previous audit's rows; the handoff that carried them;
  `CHANGELOG.md` for the release that closed them.
- **Open items outside the audit:** the newest `docs/handoff-*.md` ("Open in this repo", "Do not").
- **The run's own working files:** `.claude/audit-<date>/` (briefs, raw reports, candidates, every verdict, fuzz and repro tests,
  scripts, logs, the downloaded release APK).
- **Standing rules the prompt relies on:** `CLAUDE.md` (hard rules, build, releases, website status); `docs/build-plan.md`
  (locked decisions, design principles); `docs/feedback-tracker.md`; memory `jdk17-location` and
  `phone-install-release-signed`.
- **Delegation:** Codex `gpt-6.1-sol` and agy `gemini-4-argon-high` do the bulk work unless Eric says otherwise in the session
  (§ Method, "Delegation saves Claude tokens").
