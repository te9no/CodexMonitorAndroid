# Codex Monitor Android

Codex Monitor Android is a native Android client for viewing Codex/Codex Monitor sessions from a PC, receiving approval-waiting alerts, and sending prompts back through a local PC bridge.

---

## English Manual

### Overview

- Android app shows recent Codex sessions from your PC.
- It can show session details, approval-waiting alerts, and prompt submission UI.
- The PC bridge exposes local Codex session data to Android over your own network.
- Recommended transport is Tailscale. Do not expose the bridge to the public internet.

### Architecture

```text
Android app
  -> Tailscale network
  -> PC bridge: codex-monitor-bridge.exe or tools/codex_monitor_server.py
  -> local Codex session files under ~/.codex/sessions
  -> optional Codex Monitor daemon on 127.0.0.1:4732
```

### Android App

Download the APK from GitHub Releases and install it on Android.

If Android refuses to install the Release APK, uninstall any locally built debug version first:

```powershell
adb uninstall com.example.codexmonitor
```

Android does not allow updating an installed app with another APK signed by a different key.

### PC Bridge GUI EXE

Download `codex-monitor-bridge-<version>.exe` from GitHub Releases and run it on the PC.

The EXE is a standalone GUI listener app:

- Starts the listener automatically.
- Shows the listener status.
- Shows the Android Server URL.
- Shows daemon connection status.
- Provides buttons to start/stop the listener.
- Provides buttons to copy the Server URL and bridge token.
- Shows a small runtime log.

Default listener behavior:

- Listens on `0.0.0.0:8787`.
- Requires a bridge token.
- Generates the bridge token on first run.
- Stores the bridge token at `%APPDATA%\com.dimillian.codexmonitor\bridge-token.txt`.
- Enables prompt queue submission.
- Enables live daemon send only if a daemon token is configured on the PC.

In the Android app, open `Server` and enter:

```text
Server URL: the URL shown in the PC bridge GUI
Bridge token: token copied from the PC bridge GUI
```

### Optional Daemon Token

Live prompt delivery through Codex Monitor daemon is optional. To enable it, configure the daemon token on the PC before starting the bridge:

```powershell
$env:CODEX_MONITOR_DAEMON_TOKEN = "<codex-monitor-daemon-token>"
```

or save it to:

```text
%APPDATA%\com.dimillian.codexmonitor\daemon-token.txt
```

Do not put the daemon token in the Android app. Android only needs the bridge token.

### Run The Source Bridge

Read-only mode:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787
```

Prompt queue mode:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787 --allow-prompt-queue
```

Live daemon send mode:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py `
  --host 0.0.0.0 `
  --port 8787 `
  --allow-prompt-queue `
  --enable-daemon `
  --enable-daemon-send `
  --daemon-host 127.0.0.1 `
  --daemon-port 4732 `
  --daemon-token "<codex-monitor-daemon-token>"
```

Queued prompts are written to:

```text
%USERPROFILE%\.codex\monitor_prompts.jsonl
```

Read queue files with UTF-8 encoding:

```powershell
Get-Content $HOME\.codex\monitor_prompts.jsonl -Encoding utf8
```

### API Endpoints

- `GET /health`: public connectivity check.
- `GET /sessions`: authenticated list of recent sessions.
- `GET /sessions/<sessionId>`: authenticated session detail.
- `GET /daemon`: authenticated daemon status.
- `POST /sessions/<sessionId>/prompt`: authenticated prompt submission.
- `POST /sessions`: authenticated new-session request, currently queued only.

### Troubleshooting

`unauthorized`

- The Android bridge token does not match the PC bridge token.
- Copy the bridge token from the PC GUI again and paste it into Android `Server` settings.

`Unable to resolve host`

- The Server URL is wrong, or Tailscale/MagicDNS is not connected.
- Try the PC Tailscale IPv4 address instead of the MagicDNS name.

`daemon workspace not connected`

- The target workspace is known to Codex Monitor but not currently connected.
- Open the workspace/session in Codex Monitor on the PC, then sync again.

Prompt is queued instead of sent live

- This is expected unless daemon sending is enabled and the target workspace/thread is connected.
- Check the daemon status in the PC bridge GUI.

### Security Notes

- Use Tailscale-only access whenever possible.
- Do not expose the bridge to the public internet.
- Keep the bridge token private.
- Keep the daemon token only on the PC.
- Use `--allow-no-auth` only for local testing.

---

## 日本語マニュアル

### 概要

- AndroidアプリでPC上のCodexセッション一覧を表示します。
- セッション詳細、Approval待ち通知、プロンプト送信UIを利用できます。
- PCブリッジがローカルのCodexセッション情報をAndroidへ公開します。
- 推奨の通信経路はTailscaleです。PCブリッジを公開インターネットへ露出しないでください。

### 構成

```text
Androidアプリ
  -> Tailscaleネットワーク
  -> PCブリッジ: codex-monitor-bridge.exe または tools/codex_monitor_server.py
  -> PC上の ~/.codex/sessions のセッションファイル
  -> 任意: 127.0.0.1:4732 の Codex Monitor daemon
```

### Androidアプリ

GitHub ReleasesからAPKをダウンロードしてAndroidへインストールします。

Release APKのインストールに失敗する場合は、先にローカルdebugビルド版をアンインストールしてください。

```powershell
adb uninstall com.example.codexmonitor
```

Androidは署名キーが違うAPKで既存アプリを上書きできません。

### PCブリッジGUI EXE

GitHub Releasesから `codex-monitor-bridge-<version>.exe` をダウンロードしてPCで実行します。

EXEは単体で起動できるGUIリスナーアプリです。

- リスナーを自動起動します。
- リスナー状態を表示します。
- Android用Server URLを表示します。
- daemon接続状態を表示します。
- リスナーの起動/停止ボタンがあります。
- Server URLとbridge tokenをコピーできます。
- 簡単な実行ログを表示します。

デフォルトのリスナー動作:

- `0.0.0.0:8787` で待ち受けます。
- bridge token認証を必須にします。
- 初回起動時にbridge tokenを自動生成します。
- bridge tokenを `%APPDATA%\com.dimillian.codexmonitor\bridge-token.txt` に保存します。
- プロンプトキュー投稿を有効にします。
- live daemon送信は、PC側にdaemon tokenが設定されている場合だけ有効です。

Androidアプリの `Server` には以下を入力します。

```text
Server URL: PCブリッジGUIに表示されたURL
Bridge token: PCブリッジGUIからコピーしたtoken
```

### 任意: daemon token

Codex Monitor daemon経由のliveプロンプト送信は任意です。有効にする場合は、PCブリッジ起動前にPC側でdaemon tokenを設定します。

```powershell
$env:CODEX_MONITOR_DAEMON_TOKEN = "<codex-monitor-daemon-token>"
```

または以下のファイルに保存します。

```text
%APPDATA%\com.dimillian.codexmonitor\daemon-token.txt
```

daemon tokenをAndroidアプリへ入力しないでください。Androidに必要なのはbridge tokenだけです。

### ソース版PCブリッジを起動する

読み取り専用モード:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787
```

プロンプトキューモード:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787 --allow-prompt-queue
```

live daemon送信モード:

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py `
  --host 0.0.0.0 `
  --port 8787 `
  --allow-prompt-queue `
  --enable-daemon `
  --enable-daemon-send `
  --daemon-host 127.0.0.1 `
  --daemon-port 4732 `
  --daemon-token "<codex-monitor-daemon-token>"
```

キューに入ったプロンプトは以下へ保存されます。

```text
%USERPROFILE%\.codex\monitor_prompts.jsonl
```

PowerShellではUTF-8指定で読んでください。

```powershell
Get-Content $HOME\.codex\monitor_prompts.jsonl -Encoding utf8
```

### APIエンドポイント

- `GET /health`: 接続確認。認証なし。
- `GET /sessions`: 最近のセッション一覧。認証あり。
- `GET /sessions/<sessionId>`: セッション詳細。認証あり。
- `GET /daemon`: daemon状態。認証あり。
- `POST /sessions/<sessionId>/prompt`: プロンプト投稿。認証あり。
- `POST /sessions`: 新規セッション要求。現在はキュー保存のみ。

### トラブルシュート

`unauthorized`

- Android側のbridge tokenとPCブリッジ側のtokenが一致していません。
- PC GUIからbridge tokenをコピーし直し、Androidの `Server` 設定へ貼り付けてください。

`Unable to resolve host`

- Server URLが間違っているか、Tailscale/MagicDNSが接続されていません。
- MagicDNS名ではなくTailscale IPv4アドレスを試してください。

`daemon workspace not connected`

- Codex Monitorはworkspaceを認識していますが、現在接続中ではありません。
- PCのCodex Monitorで対象workspace/sessionを開いてから、Androidで再度Syncしてください。

Promptがlive送信されずキューに入る

- daemon送信が有効で、対象workspace/threadがconnectedの場合だけlive送信されます。
- PCブリッジGUIのdaemon状態を確認してください。

### セキュリティ注意点

- できるだけTailscale内だけで使ってください。
- PCブリッジを公開インターネットへ露出しないでください。
- bridge tokenを秘密として扱ってください。
- daemon tokenはPC側だけに保存してください。
- `--allow-no-auth` はローカルテスト専用です。
