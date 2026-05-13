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

## Tailscale PC Session Sync

The Android app can pull recent Codex sessions from a small HTTP server running on the PC.

1. Choose a bridge token and start the PC bridge:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787
```

2. Find the PC Tailscale IPv4 address:

```powershell
tailscale ip -4
```

3. In the Android app, tap `Server` and enter the PC URL plus the same bridge token:

```text
http://<pc-tailscale-ip>:8787
```

For this PC right now, the URL is:

```text
http://<pc-tailscale-ip>:8787
```

4. Tap `Sync`.

The bridge exposes:

- `GET /health`
- `GET /sessions`

`GET /health` is public for connectivity checks. All other bridge endpoints require `Authorization: Bearer <token>` or `X-Codex-Monitor-Token: <token>`. The Python bridge refuses to bind beyond localhost unless `--auth-token` or `CODEX_MONITOR_AUTH_TOKEN` is set.

The phone or emulator must be able to reach the PC over Tailscale. If using a physical Android device outside the LAN, install and connect the Tailscale Android app first.

### Current Mobile Bridge Features

The Python bridge now supports:

- `GET /sessions`: recent sessions with recent message snippets and `approvalPending` flag.
- `GET /sessions/<sessionId>`: session detail with recent messages.
- `POST /sessions/<sessionId>/prompt`: queues a prompt into `%USERPROFILE%\.codex\monitor_prompts.jsonl` when `CODEX_MONITOR_ALLOW_PROMPT_QUEUE=1` is set.

Android behavior:

- `Sync` refreshes sessions from the bridge.
- The app auto-refreshes every 30 seconds while open.
- Sessions with `approvalPending: true` are marked as `APPROVAL WAITING` and trigger an Android notification when notification permission is granted.
- `Open` shows recent session messages.
- `Prompt` sends text to the PC-side queue when prompt queueing is enabled on the bridge.

Important limitation: queued prompts are not injected into a live Codex session yet. Codex Monitor daemon uses a TCP backend, and this Android MVP does not currently implement that protocol.

### Codex Monitor Daemon Integration

By default, the Android HTTP bridge does not talk to the official Codex Monitor daemon. It only reads local Codex JSONL session files. This avoids changing Codex Monitor state or causing unexpected `Working` sessions.

Daemon integration is opt-in. If you intentionally want read-only daemon metadata, start the bridge with `--enable-daemon`, the daemon token, and a separate Android bridge token:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-android-bridge-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787 --enable-daemon --daemon-host 127.0.0.1 --daemon-port 4732 --daemon-token "<remote-backend-token>"
```

The bridge talks to the daemon over newline-delimited JSON-RPC:

- `auth`
- `list_workspaces`
- `local_usage_snapshot`
- `list_threads`

The Android app still talks HTTP to the bridge and sends only the Android bridge token. This keeps the daemon token off the phone and avoids implementing the daemon TCP protocol in Android.

Current behavior:

- `/daemon` reports `enabled: false` unless the bridge was started with `--enable-daemon`.
- `/sessions` reads local JSONL session files without touching the daemon by default.
- `Prompt` and new-session requests are rejected unless `CODEX_MONITOR_ALLOW_PROMPT_QUEUE=1` is set.
- The bridge intentionally does not call daemon execution RPCs such as `start_thread`, `send_user_message`, or `thread_live_subscribe`; those calls can change Codex Monitor state and may cause unexpected `Working` sessions.
