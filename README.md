# VitalChronicle Android

Native Android implementation of [VitalChronicle](https://github.com/SebRoLENS/VitalChronicle).

## Architecture

The Android UI, OAuth flow, Google Health transport and Gemini Nano integration are Kotlin/Android components. The deterministic health-analysis engine is **shared with VitalChronicle desktop**, not independently rewritten.

At build time this repository retrieves the upstream `SebRoLENS/VitalChronicle` source and copies the shared `analysis.py`, `ai_insights.py`, `constants.py`, `i18n.py`, `utils.py` and localization resources into the Android package through `scripts/sync_shared_core.py`. The exact upstream commit used is recorded in `shared_core_revision.json` inside the generated app assets.

This keeps the scientific/data interpretation layer synchronized between desktop and mobile while allowing each platform to use an appropriate UI and OS integration.

## AI privacy

Automatic AI uses Android's on-device Gemini Nano through ML Kit GenAI when supported. Health evidence is prepared locally by the shared deterministic core. No cloud AI endpoint is used. On unsupported devices the deterministic evidence inspector remains functional.

## Google Health

Android accepts the same OAuth Web client JSON used by VitalChronicle desktop, including the `http://localhost:8765/` loopback redirect. OAuth credentials and tokens are encrypted using Android Keystore. Google Health records are stored in a SQLite schema compatible with the desktop archive.

## Upstream synchronization

The canonical shared core lives in [`SebRoLENS/VitalChronicle`](https://github.com/SebRoLENS/VitalChronicle). Android-specific code lives here. CI always syncs the shared core from the upstream repository before building, so shared analysis logic is not allowed to silently diverge.

## Version

Android app version: **0.1.0**.
