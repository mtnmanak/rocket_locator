# Feedback and issue tracking — this repo keeps its OWN Issues tab

**Status:** ✅ Settled 12 August 2026. Nothing to build, nothing to change here.
**This app's tracker:** its own Issues tab — https://github.com/mtnmanak/rocket_locator/issues
**Central tracker (for the website and the four browser tools):** https://github.com/mtnmanak/mountainmanrockets-feedback

## Why this file exists

On 11 August 2026 Eric closed **every** working session at once, because three of them were
independently designing the same issue tracker and pasting conflicting setups into the same
repos. The adjudication that followed lives in the site repo at
`docs/issue-tracking-consolidation.md` and it is the single source of truth.

This file is that decision, delivered to the repo you are actually working in, so a session
here does not have to find it — or worse, re-derive it and get a different answer.

## Why this one is different

Every other tool routes into the central tracker. This app does not, and the reason is
**not** that its repo is public — that rule was considered and rejected, because it would
produce a new tracker every time a repo opens up.

It stays separate because it is not part of the mountainmanrockets.com **product surface**.
It is a native Android app distributed through GitHub Releases and Obtainium; its users
arrive at GitHub already and need never touch the website. After DNS cutover the site's
`/rocket_locator/` path 301s away to `/online_tools/`.

**Recorded honestly:** Eric's only written words on the point say the opposite —
`docs/feedback-2026-08-11a.md`: *"It feels like this tool deserves it's own issue tracking
repo."* The adjudication agreed with the answer while replacing the reasoning. **The stakes
are low precisely because this repo is public** — issues transfer freely between public
repos, so this is cheap to reverse whenever he wants.

## What that means for you

- ✅ **Keep this repo's Issues tab enabled.** It is this app's front door. It is the one
  deliberate exception to the "disable Issues" rule.
- ✅ The README already links it (`README.md:9`), with the email fallback.
- ❌ Do not add central-tracker labels, forms, or the labeler workflow here.
- ❌ Do not create any new feedback repo.
- ℹ️ The central tracker's `config.yml` carries a contact link pointing filers here, so
  someone who lands there by mistake is redirected rather than lost.

## Standing UI rulings, if you build a feedback affordance in the app

- GitHub links open in a **new tab**; `mailto:` links do not.
- Always offer the email fallback: `admin@mountainmanrockets.com` — reading needs no
  account, filing does.
- Feature requests explicitly include content requests.

## If you think this decision is wrong

Say so to Eric — do not implement an alternative. The whole reason this document exists is
that three sessions once each implemented a different reasonable-sounding answer. The
argument, the rejected options and the reasoning are in `docs/issue-tracking-consolidation.md`
in the `mountainmanrockets` site repo. Changes to the taxonomy, the forms or the labeler are
made **there and in the tracker**, never here.
