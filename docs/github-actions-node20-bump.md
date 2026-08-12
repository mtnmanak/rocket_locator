# GitHub Actions: Node 20 deprecation bump

**Rev. 2 — 11 Aug 2026.** Rev. 1 (9 Aug) was materially wrong in five ways; every claim
below has been re-verified by reading each action's own `action.yml` at the pinned tag for
`runs: using:`, and by the GitHub REST API for run annotations. Corrections are marked
**[CORRECTED]**.

GitHub deprecated Node.js 20 and is force-running these actions on Node.js 24. Nothing is
broken today; when the shim is removed, every unbumped workflow fails at once.

---

## Status

| Repo | File | Status |
|---|---|---|
| `diesel_dashboard` | `deploy.yml` | ✅ **DONE** — commit `94e7331`, 10 Aug, pushed |
| `rocket_locator` | `ci.yml` | ✅ **DONE** — commit `67b6027`, 11 Aug, **verified green with zero annotations** |
| `rocket_locator` | `release.yml` | ✅ **DONE** — same commit; unexercised until a `v*` tag |
| `bp_calculator` | `deploy.yml` | ⬜ TODO |
| `motor_dashboard` | `deploy.yml` | ⬜ TODO |
| `motor_sim` | `deploy.yml` | ⬜ TODO |
| `online_open_rocket` | `deploy.yml` | ⬜ TODO |
| `online_open_rocket` | `deploy-pages.yml` | ⬜ TODO (dormant — all-or-nothing, or delete) |

Verified inventory: **8 workflow files across 6 repos.** `mountainmanrockets`,
`online_tools`, `mountainmanrockets-site-feedback` and `sandbox` have no `.github/`
directory at all, and never have (`git log --all -- .github/` is empty). There are no local
composite actions anywhere (`find . -name action.yml` returns nothing).

---

## [CORRECTED] What rev. 1 got wrong

1. **Scope miss — it omitted `rocket_locator` entirely.** Rev. 1 said "five repos, six lines
   across six files." It is **8 files across 6 repos, 21 affected `uses:` lines** (20 direct
   + 1 indirect via a composite). `rocket_locator`'s `ci.yml` and `release.yml` held 8 of
   those lines, including the single highest-consequence one — the APK publisher. Both runs
   were already carrying the annotation:

   > Node.js 20 is deprecated… actions/checkout@v4, actions/setup-java@v4,
   > gradle/actions/setup-gradle@v4, softprops/action-gh-release@v2.

   CI carried a second one rev. 1 never anticipated: *"setup-java v4 is deprecated and will
   no longer receive updates. Please migrate to actions/setup-java@v5."*

2. **`actions/upload-artifact@v5` is STILL Node 20.** Verified from its `action.yml`. The
   "bump one major" heuristic rev. 1 teaches leaves this broken. **v6 is the floor.**

3. **`actions/configure-pages@v5` is Node 20 too.** So `deploy-pages.yml` has **five**
   affected actions, not the four rev. 1 claims. Its "bump all four" instruction is short by one.

4. **`actions/upload-pages-artifact@v4` does not actually fix that file.** It is a composite
   that internally pins `actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02`
   (v4.6.2), which declares `using: 'node20'`. Rev. 1's "deleting the file is also defensible"
   option looks better than it did.

5. **"Change only the trailing `@v4`" is wrong outside the four remaining deploy repos.**
   Affected families also include `setup-java`, `setup-gradle`, `upload-artifact`,
   `configure-pages`, `deploy-pages` and `action-gh-release` — pinned at v2, v3 and v5, not
   just v4.

Minor: rev. 1 says "six lines across six files" at one point and lists six files × two lines
= twelve in its own table. Its `cd C:/git/<repo>` is wrong — repos are at `E:\git`. Its
step 5 depends on `gh run watch`; **`gh` is not installed on this machine.**

**Rev. 1 was right about one thing worth keeping:** do **not** bump
`cloudflare/wrangler-action@v4`. Now confirmed by source — its `action.yml` declares
`using: "node24"`. It was never part of this.

---

## Remaining edits

### The four deploy repos — the simple case

Two lines each, change only the trailing `@v4`:

```diff
-      - uses: actions/checkout@v4
+      - uses: actions/checkout@v7

-      - uses: actions/setup-node@v4
+      - uses: actions/setup-node@v7
```

| Repo | File | Lines |
|---|---|---|
| `bp_calculator` | `.github/workflows/deploy.yml` | 35, 37 |
| `motor_dashboard` | `.github/workflows/deploy.yml` | 39, 41 |
| `motor_sim` | `.github/workflows/deploy.yml` | 31, 33 |
| `online_open_rocket` | `.github/workflows/deploy.yml` | 37, 39 |

Line numbers re-verified 11 Aug — none have drifted. No `with:` block changes needed: no repo
has a `packageManager` field, so `setup-node@v5`'s automatic caching never triggers (that was
rev. 1's analysis and it still holds).

### `online_open_rocket/deploy-pages.yml` — dormant, five actions

`workflow_dispatch` only, `push:` commented out, has never run. Either delete it, or do all five:

| Line | From | To |
|---|---|---|
| 34 | `actions/checkout@v4` | `@v7` |
| 35 | `actions/setup-node@v4` | `@v7` |
| 41 | `actions/configure-pages@v5` | `@v6` |
| 42 | `actions/upload-pages-artifact@v3` | `@v4` — *still not clean, see correction 4* |
| 46 | `actions/deploy-pages@v4` | `@v5` |

Since Cloudflare Pages is the deploy target and GitHub Pages is not used, **deleting the file
is the cleanest option.**

---

## Verified runtimes

Read from each action's `action.yml` at the pinned major tag. Not guessed.

```
actions/checkout@v4              node20      @v5 / @v7  node24   (v8: 404)
actions/setup-node@v4            node20      @v7        node24   (v8: 404)
actions/setup-java@v4            node20      @v5        node24   (v6: 404)
actions/upload-artifact@v4       node20      @v5        node20  <-- TRAP
                                             @v6 / @v7  node24
actions/configure-pages@v5       node20      @v6        node24
actions/deploy-pages@v4          node20      @v5        node24
actions/upload-pages-artifact@v3 composite -> upload-artifact@v4      (node20)
                             @v4 composite -> upload-artifact@v4.6.2  (node20)
softprops/action-gh-release@v2   node20      @v3        node24
gradle/actions/setup-gradle@v4   node20      @v5 / @v6  node24
cloudflare/wrangler-action@v4    node24      -- leave alone
```

**Why `setup-gradle@v5` and not `@v6`:** v6 extracts caching into a separate
`gradle-actions-caching` component — a real behavioural change. v5 is a pure runtime bump.

---

## Per-repo procedure

**Each push to a tool repo IS that repo's production deploy.** Four production publishes remain.

```bash
cd E:/git/<repo>          # NOT C:/git — repos moved to E:\git on 11 Aug 2026

# 1. Edit the two lines (table above)

# 2. Stage ONLY the workflow file — every repo has unrelated untracked files
git add .github/workflows/deploy.yml
git status --short          # confirm nothing else is staged

# 3. Commit, 4. Push (this deploys)
git push origin main
```

**Verification — `gh` is not installed, so rev. 1's `gh run watch` does not work.** Use the
REST API instead. These five repos are **private**, so an unauthenticated call returns 404;
either check in a browser, or use a token:

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "User-Agent: x" \
  "https://api.github.com/repos/mtnmanak/<repo>/actions/runs?per_page=1"
```

**The success signal is not the green check — it is the annotation being gone.** Fetch
`/actions/runs/{id}/jobs`, take the job id, then `/check-runs/{job_id}/annotations`. An empty
array `[]` is the pass. That is exactly how `rocket_locator`'s bump was confirmed:

```
CONCLUSION=success
ANNOTATIONS_RAW_LEN=2        <- the array is literally "[]"
RESULT: NODE20 ANNOTATION GONE
```

### Suggested order

`bp_calculator` first — no lockfile, no caching, smallest build; a surprise shows up there most
cheaply. Then `motor_dashboard`, `motor_sim`, `online_open_rocket`. (Rev. 1's ordering advice is
otherwise stale: `diesel_dashboard`, a lockfile repo, was done first and out of order, and it
worked fine.)

---

## Uncommitted work — do not sweep these in

Stage the workflow file by path. **Avoid `git add -A` and `git commit -a`.** State at
19:30 on 11 Aug 2026 — these repos are under active edit, so re-check before you commit:

| Repo | Untracked |
|---|---|
| `bp_calculator` | 2 session notes |
| `diesel_dashboard` | 2 session notes + **`body.txt`** — 363 bytes, holds *motor_dashboard's* v1.8.0 release-note JSON, sitting in the wrong repo. Not gitignored. Delete or ignore it. |
| `motor_dashboard` | 2 session notes + `rocket-motor-dashboard_16.html` — verified byte-identical to `git show v16:index.html`; pure clutter, safe to delete |
| `motor_sim` | 2 session notes + **`version.json`** — still untracked while its three sibling repos all track theirs. Commit it; `check-tools.mjs` expects to read it from the repo. |
| `online_open_rocket` | *(clean as of 19:26 — everything was committed and pushed in `ba5ac72`/`8f84077`)* |
| `rocket_locator` | `docs/feedback-2026-08-11a.md` |

Rev. 1's claim that `engine-java/patches/rocketcomponent/` is untracked in `online_open_rocket`
is stale — that path no longer exists on disk.

---

## Not covered here

**`mountainmanrockets` has no workflow, and rev. 1's open question is now answered: its deploy
IS a manual step Eric has been carrying.** `package.json:11` is
`"deploy": "npm run build && npx wrangler pages deploy dist --project-name=mountainmanrockets"`,
and `docs/HANDOFF.md:1003` says *"Deploys run from a local build, not CI — nothing happens
automatically."* All six Pages projects are Direct Upload, which cannot be converted to Git
integration, so this cannot be fixed by adding a workflow without recreating the projects.

---

## Note on copies

This file was broadcast, byte-identical, to `bp_calculator`, `diesel_dashboard`,
`motor_dashboard`, `motor_sim` and `rocket_locator`. **Only this copy (in `rocket_locator`) is
rev. 2.** The other four are still rev. 1 and carry all five errors above. Copy this one over
them, or work from this one.
