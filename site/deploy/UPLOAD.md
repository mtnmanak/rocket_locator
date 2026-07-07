# Deploying the app page to the WordPress site

This folder is the deployable website: currently a single self-contained `index.html`
(no external assets, adapts to light/dark automatically).

## Upload (once)

1. Open your hosting control panel's **File Manager** (or connect via FTP/SFTP).
2. Navigate to the web root — usually `public_html/` (the folder containing your
   WordPress `wp-content`, `wp-admin`, etc.).
3. Create a subfolder, e.g. `rocketlocator`.
4. Upload `index.html` into it.
5. The page is now live at `https://<your-domain>/rocketlocator/`.

Add it to the site menu: WordPress admin → Appearance → Menus → Custom Link →
paste the URL above.

## Updating the page

Replace `index.html` in the subfolder with the new version from this folder.
(Releases do NOT require this — the download button always serves the newest
APK automatically. Only edit/re-upload when the page content itself changes.)

## Note

Don't create a WordPress *page* with the same slug (`rocketlocator`) — a real
folder and a WP permalink with the same name can conflict; pick one.
