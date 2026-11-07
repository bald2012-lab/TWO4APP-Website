# TWO4APP Downloader 1.1 TV

Landscape-only Android TV / Google TV APK. Separate compact left panel for code input, D-pad-friendly numeric keypad, device-local named favorites with descriptions and file manager. The right panel is a persistent in-app WebView for landing pages. Known tracker/advertising hosts and obvious ad containers are blocked; not every third-party ad can be prevented. Direct HTTPS APK downloads use Android DownloadManager and require user confirmation to install.

Use existing public AFTVnews short codes. No proprietary code registry, ads, analytics SDKs, accounts, or server. Favorites are saved locally via SharedPreferences, so they are not shared with the HTML web version or other devices.

**Signing:** The CI workflow currently creates a debug-signed testing APK. It does not use the permanent signing key, and a debug APK cannot update a permanently signed release in place. Do not publish debug binaries as production upgrades.
