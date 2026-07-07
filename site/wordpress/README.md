# WordPress download page

`rocket-locator-page.html` is the source of truth for the app's page on the WordPress site.

## Publishing / updating the page

1. WordPress admin → Pages → Add New (or edit the existing page).
2. Add a single **Custom HTML** block.
3. Paste the entire contents of `rocket-locator-page.html` into it.
4. Preview, publish.

All styles are scoped under `.rl26`, so the block won't fight the theme. The page adapts to light/dark automatically.

## How the download button stays current

The button links to:

```
https://github.com/mtnmanak/rocket_locator/releases/latest/download/rocket-locator-26.apk
```

GitHub resolves `releases/latest` to the newest release, and the release workflow (`.github/workflows/release.yml`) always names the APK asset `rocket-locator-26.apk`. Result: **publishing a release updates the website's download automatically** — the WordPress page never needs editing for a version bump.

Cutting a release is just:

```
git tag v0.03 && git push origin v0.03
```

(CI builds, tests, signs, and publishes the release.)

## Private-repo caveat (beta period)

`releases/latest/download/...` only works publicly on a **public** repo. While the main repo is private, either:

- **Option A (recommended during beta):** create a public repo `mtnmanak/rocket-locator-releases`, upload each APK to a release there (1 minute, drag-and-drop), and change the two `github.com/mtnmanak/rocket_locator` URLs in the page to `github.com/mtnmanak/rocket-locator-releases`.
- **Option B:** flip the main repo public early.

When the main repo goes public at the first stable release, point the URLs back (or retire the releases repo with a note).

## Editing guidance

Update this file and the page together when: supported hardware changes, minimum Android version changes, or permissions change (the install steps mention them). Keep the page's claims in sync with `CHANGELOG.md`.
