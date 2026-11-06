# TWO4APP Downloader 1.0

Ad-free, remote-friendly personal Android TV/Google TV downloader. No original AFTVnews Downloader installation is required; entered public short codes are resolved through `https://go.aftvnews.com/<code>`. AFTVnews runs the public code service, not TWO4APP.

## Features
- Numeric AFTVnews codes and HTTPS URLs
- Extracts direct APK URLs from the shortener's HTML when possible
- Downloads using Android DownloadManager
- Installs APK through Android's standard installer after explicit confirmation
- Favorites with friendly names (device-local)
- Minimal browser for web landing pages with a conservative ad/tracker domain filter
- No analytics, account, tracking SDK, or ads in the app

## Build
- Android Gradle Plugin 8.9.2, Gradle 8.11.1, JDK 17, Android SDK 35
- Run `gradle :app:assembleDebug` from `android-downloader`
- First build can be signed with a debug key; **do not use debug-key builds for long-term upgrade distribution**. A permanent release keystore must be configured securely before publishing a stable release. Never commit a release private key to a public repository.

## Security and limitations
- Downloaded APKs can be malicious. Always verify the developer and destination host before install.
- Third-party pages and some dynamically generated downloads may need the built-in browser, and ad blocking is best-effort.
- Only HTTPS redirects are followed for direct resolution. Some sites use unsupported non-HTTPS links, `blob:` downloads, scripts or login walls.
- Requires approval to install unknown apps, as mandated by Android.
- Builds without a permanent signing key are for testing and cannot guarantee seamless in-place updates.
