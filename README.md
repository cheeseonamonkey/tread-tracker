# Tread Tracker

A small, offline Android step counter. No ads, analytics, accounts, or network permission.

- Hardware step counter, with accelerometer fallback.
- Daily goal, hourly/day/week/month history, distance, calories, active time.
- Persistent foreground health service for background counting (including screen-off and after reboot), resize-aware home-screen widget (compact 1×1 to expanded layouts).
- Light/dark utilitarian UI, side-profile sneaker icon, Android themed icon.
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

All step data is stored locally in SQLite. There is no `INTERNET` or `ACCESS_NETWORK_STATE` permission, advertising SDK, analytics SDK, or telemetry endpoint. Counting requires activity recognition on Android 10+. The app requests the Android health foreground-service and wake-lock permissions for continuous operation; it does not request internet or battery-exemption permissions. No other personal data is uploaded.

## Background counting (v1.0.3+)

The hardware step counter remains registered in a persistent **health foreground service**, including when the app is closed. The service restarts after boot or an app update when permission has already been granted and counting is enabled. It is designed to be low-power on devices with a hardware counter. Accelerometer-only devices use a partial wake lock and consume more battery.

**Android requires an ongoing foreground-service notification.** We keep it silent, low-priority, and unchanged during counting. Android 13+ can hide it from the notification drawer if notifications are disabled, but it remains visible in the system foreground-service task manager. Android 12 and earlier generally show the notification. This is the OS tradeoff for reliable background tracking; the app does not bypass it.

Android may terminate services after force-stop, power restrictions, or vendor-specific battery policies; continuous tracking is best-effort within Android's normal lifecycle rules. Hardware step-counter readings are cumulative since boot while activated, but are not timestamped per individual step: a gap spanning midnight cannot be precisely allocated to both days.

## Power and reliability (v1.0.4+)

- Prioritize the hardware **step counter** (low-power, cumulative readings) or hardware **step detector**. Only fall back to the CPU-driven accelerometer if neither is available.
- Request 60-second hardware sensor batching to minimize processor wakeups. If hardware buffering isn't supported, Android delivers readings when available. Sensor timestamps preserve when events actually occurred.
- Refresh display data when the app opens; throttle background widget updates and UI refresh broadcasts, rather than writing or waking for every step. Batched step events are grouped for fewer database writes.
- **Settings → Background reliability** shows the detected sensor and opens system settings for battery restrictions and the foreground-service notification channel. For maximum persistence, allow background activity and select Unrestricted when offered. On Android 12, set the tracking notification to Silent / Minimize when supported; hiding it entirely is not normally possible. On Android 13+, notification permission may be denied without stopping the foreground service.
- Software accelerometer fallback needs continuous sensor processing and can consume substantial battery; that hardware limitation cannot be solved just by hiding the notification.

## Installation and data

Package ID: `dev.treadtracker.app`. This is a **new app**, not an in-place update to `com.tayu.tau.pedometer`. The original app's database and preferences are **not migrated**. Back up data from the original app before uninstalling it.

The widget supports both horizontal and vertical resizing, with compact layouts for small launcher cells and size-aware layouts on Android 12+. Available sizes depend on the launcher.

The installed app also shows its development and QA credits under **Settings → About**.

## Credits

- Development: [@cheeseonamonkey](https://github.com/cheeseonamonkey)
- QA: Stormy (meow)

## Provenance

Rebranded from the user-provided clean Kotlin pedometer rebuild. It replaces the original proprietary app's ad/telemetry-heavy distribution and native step detector with an offline Kotlin implementation. Review upstream rights/attribution before redistributing publicly. No license is inferred from the supplied archive.
