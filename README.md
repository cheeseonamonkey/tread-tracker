# Tread Tracker

A small, offline Android step counter. No ads, analytics, accounts, or network permission.

- Hardware step counter, with accelerometer fallback.
- Daily goal, hourly/day/week/month history, distance, calories, active time.
- Background foreground-service counting, reboot restart, home-screen widget.
- Light/dark notebook-inspired UI, boot-tread icon, Android themed icon.
- Kotlin and regular Android views; no Compose, Material Components, native libraries, or ad SDKs.

## Build

Requires **JDK 17+**, Android SDK 36, and the included Gradle wrapper (Gradle 9.8.2 / Android Gradle Plugin 9.4.1).

```sh
./gradlew :app:assembleDebug                # installable debug APK
./gradlew :app:assembleRelease              # R8-shrunk unsigned APK unless signed
```

The debug APK is at `app/build/outputs/apk/debug/`. To produce a **signed** minified release APK, set all four environment variables:

```sh
export TREAD_KEYSTORE_PATH=/path/to/release.jks
export TREAD_KEYSTORE_PASSWORD=...  # use a secure secret store
export TREAD_KEY_ALIAS=...
export TREAD_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

Don't commit the signing key or passwords. The Gradle build doesn't use a debug key for release builds.

### GitHub Actions

Push to `main` or run the **Android** workflow manually to get an installable **debug APK** under workflow artifacts. The workflow also builds the shrunk release variant.

For signed release artifacts and automatic GitHub Releases on version tags, configure these repository secrets:

`TREAD_KEYSTORE_BASE64`, `TREAD_KEYSTORE_PASSWORD`, `TREAD_KEY_ALIAS`, `TREAD_KEY_PASSWORD`.

`TREAD_KEYSTORE_BASE64` is the base64-encoded binary signing keystore (use `base64 -w0 release.jks` on Linux). A tag such as `v1.0.0` then builds and publishes the signed `app-release.apk`.

## Privacy

All step data is stored locally in SQLite. There is no `INTERNET` or `ACCESS_NETWORK_STATE` permission, advertising SDK, analytics SDK, or telemetry endpoint. Background counting requires activity recognition and foreground-service permissions; Android 13+ may separately prompt for notification permission. No other personal data is uploaded.

## Installation and data

Package ID: `dev.treadtracker.app`. This is a **new app**, not an in-place update to `com.tayu.tau.pedometer`. The original app's database and preferences are **not migrated**. Back up data from the original app before uninstalling it.

## Provenance

Rebranded from the user-provided clean Kotlin pedometer rebuild. It replaces the original proprietary app's ad/telemetry-heavy distribution and native step detector with an offline Kotlin implementation. Review upstream rights/attribution before redistributing publicly. No license is inferred from the supplied archive.
