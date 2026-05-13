# Codex Monitor Android

Native Android MVP for monitoring Codex work sessions locally.

## Current Scope

- Modern dashboard UI with status metrics, filters, animated cards, and a floating add button.
- Add, edit, delete Codex sessions.
- Mark sessions as `RUNNING`, `BLOCKED`, or `DONE`.
- Persist data locally with `SharedPreferences`.
- No backend, account, or network dependency.

## Project Layout

- `app/src/main/java/com/example/codexmonitor/MainActivity.java`: single-activity MVP and programmatic UI.
- `app/src/main/res/values/`: app strings, colors, and theme.
- `app/src/main/res/drawable/ic_launcher.xml`: temporary vector launcher icon.

## Open In Android Studio

1. Open Android Studio.
2. Select `Open`.
3. Choose this folder: `/home/owner/codexmonitorandroid`.
4. Let Android Studio sync Gradle and install the requested Android SDK if prompted.
5. Run the `app` configuration on an emulator or connected device.

## CLI Build

If Java, Android SDK, and Gradle are available on PATH:

```sh
gradle assembleDebug
```

If you prefer a wrapper-based workflow, run this once from Android Studio Terminal or any shell with Gradle:

```sh
gradle wrapper
./gradlew assembleDebug
```

## Next Functional Step

The MVP is intentionally local-first. The next meaningful version should define how Android discovers real Codex activity:

- Manual monitoring only, as implemented now.
- Pull status from a local/remote Codex Monitor API.
- Receive push notifications from a companion desktop service.
