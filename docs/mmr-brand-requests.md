# Requests to mmr-brand from Rocket Locator

**From:** Rocket Locator (`rocket_locator`), during brand adoption (mmr-brand 1.1.0), 2026-10-27.

## 1. Static TTF fonts for Android

SPEC §2.6 sets **Archivo** (display/UI) and **IBM Plex Mono** (data) and ships them in `brand/fonts/`
as web `woff2`. Android can't load woff2 from `res/font/`; it takes `.ttf` / `.otf`. Rocket Locator
therefore still uses the system fonts (Roboto, and the platform monospace for NMEA lines and values).

### What we'd need

A `properties/rocket-locator/android/res/font/` set (or a shared `brand/fonts/ttf/`) that
`sync-brand.mjs` can vendor into `app/src/main/res/font/`:

| File (Android resource names: lowercase, underscores) | Family | Weight |
|---|---|---|
| `archivo_regular.ttf` | Archivo | 400 |
| `archivo_medium.ttf` | Archivo | 500 |
| `archivo_semibold.ttf` | Archivo | 600 |
| `archivo_extrabold.ttf` | Archivo | 800 |
| `ibm_plex_mono_regular.ttf` | IBM Plex Mono | 400 |
| `ibm_plex_mono_medium.ttf` | IBM Plex Mono | 500 |
| `ibm_plex_mono_semibold.ttf` | IBM Plex Mono | 600 |

- **Static instances, not the variable font.** One file per weight maps directly onto Compose's
  `Font(resId, FontWeight)`. A variable file would need `FontVariation` settings per weight (and the
  `wdth` axis pinned), which is more code and more room for error than four static files. Archivo at
  the default width (100 %).
- Subset to Latin + Latin-1 + the punctuation the app uses (`°`, `·`, `—`, `…`, `’`) to keep the APK small.
- Include the OFL licence text alongside (both families are SIL OFL 1.1), so the app can list it on
  an About/licences screen.

Once shipped, the app will add a Compose `Typography` mapping display/title roles to Archivo and
label/data roles to Plex Mono, with `tabular-nums` (`fontFeatureSettings = "tnum"`) on numeric readouts.

## 2. Checklist: dark error colour is out of date

`properties/rocket-locator/CHECKLIST.md` §2 gives the dark error colour as `#FF8A93`. Since 1.0.3 it is
`#FF9EA5` (`tokens.json` `theme.dark.danger`); the app uses `#FF9EA5`. Please correct the checklist.

## 3. Opaque tints for native platforms

The `-soft` tokens are 8-digit hex with alpha (e.g. `accent-soft` `#FF650926`). Material 3 container roles
(`primaryContainer`, `secondaryContainer`, `tertiaryContainer`, `errorContainer`) must be opaque, so the app
composites each tint over `surface` itself:

| Tint over surface | Light (over `#FFFFFF`) | Dark (over `#123859`) |
|---|---|---|
| accent-soft | `#FFE8DA` | `#363F4D` |
| link-soft | `#D9E9F2` | `#1F4D70` |
| warning-soft | `#EDE5D9` | `#364A55` |
| danger-soft | `#F5E5E7` | `#2E4462` |

Suggest `tokens.json` ship these pre-composited values (e.g. `theme.light.accent-soft-on-surface`) so every
native consumer gets identical numbers, and SPEC §5.4 gives the Material 3 role mapping.

## 4. Checklist: splash / window background wording

The checklist says to set the platform window background to navy "if the app defaults to dark". Android apps
follow the system setting, so the app sets navy in both modes (and the Android 12+ splash), giving a short
navy→paper fade on a light-mode cold start. Suggest the checklist say this explicitly, so the light-mode fade
is a known, accepted trade-off rather than a surprise.

## 5. `brandInclude: ["marks"]` vendors every product's marks

The checklist's `mmr-brand.json` copies all 30 marks (every product) into `docs/brand/marks`. Rocket Locator
needs only its own and the parent's. Consider a per-property marks filter in `sync-brand.mjs`.

## 6. Play Store feature graphic

Still to be made (1024×500), per SPEC §5.4 and the checklist §4.
