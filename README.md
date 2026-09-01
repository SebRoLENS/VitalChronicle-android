# VitalChronicle Android

[![Download latest APK](https://img.shields.io/badge/Download-latest%20APK-3DDC84?logo=android&logoColor=white)](https://github.com/SebRoLENS/VitalChronicle-android/releases/latest/download/VitalChronicle-Android.apk)

Native Android implementation of [VitalChronicle](https://github.com/SebRoLENS/VitalChronicle).

## Download

Download the **latest Android APK directly** from GitHub Releases:

**[Download VitalChronicle-Android.apk](https://github.com/SebRoLENS/VitalChronicle-android/releases/latest/download/VitalChronicle-Android.apk)**

The link always points to the APK attached to the most recent GitHub Release, so it does not need to change when the app version changes.

## Architecture

The Android UI, Google authorization, Google Health transport and Gemini Nano integration are Kotlin/Android components. The deterministic health-analysis engine is **shared with VitalChronicle desktop**, not independently rewritten.

At build time this repository retrieves the upstream `SebRoLENS/VitalChronicle` source and copies the shared `analysis.py`, `ai_insights.py`, `constants.py`, `i18n.py`, `utils.py` and localization resources into the Android package through `scripts/sync_shared_core.py`. The exact upstream commit used is recorded in `shared_core_revision.json` inside the generated app assets.

This keeps the scientific/data interpretation layer synchronized between desktop and mobile while allowing each platform to use an appropriate UI and OS integration.

## Google authorization

Android uses **Google Identity Services / `AuthorizationClient`** for client-side OAuth access. It does not embed a Google OAuth client secret, open a browser loopback callback, or run a localhost OAuth server.

Before connecting an account, configure an **Android OAuth client** in the same Google Cloud project used for the Google Health API:

1. In Google Cloud Console open **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. Choose **Android**.
3. Register package name `io.github.sebrolens.vitalchronicle.android`.
4. Register the SHA-1 signing-certificate fingerprint shown directly in **VitalChronicle → Settings → Google Health**.
5. If the OAuth consent screen is still in testing, keep the Google account you use in the configured test users.

Android Studio debug builds and production/release builds normally use different signing certificates. Create an Android OAuth client entry for every SHA-1 certificate used to sign an installed build. The app deliberately displays its actual runtime SHA-1 to make emulator/device setup unambiguous.

## AI privacy

Automatic AI uses Android's on-device Gemini Nano through ML Kit GenAI when supported. Health evidence is prepared locally by the shared deterministic core. No cloud AI endpoint is used. On unsupported devices the deterministic evidence inspector remains functional.

## Google Health

Google Health access tokens are obtained client-side from Google Play services. Google Play services manages token refresh/account authorization; VitalChronicle does not persist a refresh token or OAuth client secret. Health records are stored locally in SQLite.

General local history is limited to 90 days. High-volume raw cardiac streams (`heart-rate`, raw HRV and ECG) are limited to 15 days; daily cardiac summaries remain available for the normal 90-day deterministic baselines.

## Upstream synchronization

The canonical shared core lives in [`SebRoLENS/VitalChronicle`](https://github.com/SebRoLENS/VitalChronicle). Android-specific code lives here. CI always syncs the shared core from the upstream repository before building, so shared analysis logic is not allowed to silently diverge.

## Version

The packaged app version is read directly from the Android build configuration. Use the download link above for the current release.
