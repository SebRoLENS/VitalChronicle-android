# Stable Android release signing

VitalChronicle GitHub Releases must be signed with one persistent private key. Never publish release APKs signed with the ephemeral Android debug keystore created by a GitHub Actions runner.

## One-time key creation

Run locally on a trusted computer with Java installed:

```bash
keytool -genkeypair \
  -keystore vitalchronicle-release.jks \
  -alias vitalchronicle \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

Use one strong password for both the keystore and key entry. Keep the `.jks`/PKCS12 file private and backed up offline. Losing this key means future APKs cannot update installations signed with it.

## GitHub Actions secrets

Create these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 of the complete keystore file (`base64 -w 0 vitalchronicle-release.jks` on GNU/Linux).
- `ANDROID_KEYSTORE_PASSWORD` — the keystore/key password.
- `ANDROID_KEY_ALIAS` — normally `vitalchronicle`.

The workflow exposes these secrets only to trusted `main` push or manual-dispatch release jobs. Pull requests and scheduled compatibility builds use an ephemeral debug key and are never published as GitHub Releases.

## Release behavior

When all three secrets are configured, the release job:

1. restores the private keystore only inside the temporary GitHub runner;
2. builds `assembleRelease` with that stable key;
3. verifies the APK signature with `apksigner`;
4. records the certificate SHA-1 in the Actions job summary;
5. publishes both the versioned APK and stable `VitalChronicle-Android.apk` asset.

If any signing secret is missing, release publication is skipped instead of publishing an APK with a different key.

## Google OAuth

Android OAuth identifies VitalChronicle using the package name plus signing-certificate SHA-1. After introducing or rotating the release key, register the SHA-1 of the stable release certificate in the Google Cloud Android OAuth client. The installed app also displays its current signing SHA-1 in Settings.

## Important

Never commit the keystore, its password, or its base64 representation. The repository `.gitignore` excludes `*.jks` and `*.keystore` files.
