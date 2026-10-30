# minimalistic app

A personal minimalist Android launcher (pure black, text only) that is also a strict guard
against addictive apps: a reason-and-countdown Gate, wall-clock sessions, daily budgets, cooldowns,
blocked hours, chats-free Instagram/Snapchat, Shorts blocking, a notification filter with a digest,
focus mode, loyalty cards with store geofences, a weekly review, a 24 h delay on loosening any rule,
and a lockdown that protects its own off switch.

Built for one phone (Xiaomi Redmi Note 13 Pro, HyperOS 3, Android 16), sideloaded via adb.
No internet permission; all data stays on the phone.

- `SPEC.md` — the full specification
- `TESTING.md` — what has been verified on the real phone
- `RECOVERY.md` — how to switch it off (adb / safe mode). Read before lockdown arms.

## Build & install

Needs the Android SDK and the JDK bundled with Android Studio (Gradle 9.8 runs on Java 25).

```sh
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk   # first time: tap Install on the phone
adb shell pm grant lt.stillhome android.permission.WRITE_SECURE_SETTINGS   # grayscale
tools/deploy.sh release   # later updates, no phone prompt
```

`tools/fetch_stores.py` refreshes the bundled OpenStreetMap store list (`app/src/main/assets/stores.json`).
