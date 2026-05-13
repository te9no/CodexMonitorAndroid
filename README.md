# Codex Monitor Android

Codex Monitor Android is a native Android client for viewing Codex/Codex Monitor sessions from a PC, receiving approval-waiting alerts, and sending prompts back through a local PC bridge.

日本語版は後半にあります。

## English Manual

### What This App Does

- Shows recent Codex sessions from your PC.
- Displays session status such as `RUNNING`, `BLOCKED`, and `DONE`.
- Opens recent session messages on Android.
- Highlights sessions that appear to be waiting for approval.
- Can show Android notifications for approval-waiting sessions.
- Can submit prompts through a PC bridge when the bridge is explicitly configured for it.

### Architecture

```text
Android app
  -> Tailscale network
  -> PC bridge: tools/codex_monitor_server.py
  -> local Codex session files under ~/.codex/sessions
  -> optional Codex Monitor daemon on 127.0.0.1:4732
```

Recommended transport is Tailscale. Tailscale encrypts traffic between your Android device and PC. The bridge also supports an application-level bearer token.

### Requirements

- Android device or emulator.
- Tailscale installed and connected on the Android device.
- Tailscale installed and connected on the PC.
- Python 3 on the PC.
- This repository on the PC.
- Optional: Codex Monitor daemon running if you want live prompt delivery.

### Build And Install The Android App

Open the project in Android Studio and run the `app` configuration, or build from the command line:

```powershell
.\gradlew.bat assembleDebug
```

If a device is connected, install the APK with Android Studio or ADB:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Start The PC Bridge: Read-Only Mode

Use this mode first. It reads local Codex session files and does not touch the Codex Monitor daemon.

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787
```

Find your PC Tailscale IP or MagicDNS name:

```powershell
tailscale ip -4
```

In the Android app, open `Server` and enter:

```text
Server URL: http://<pc-tailscale-ip-or-name>:8787
Bridge token: <long-random-bridge-token>
```

Then tap `Sync`.

### Start The PC Bridge: Prompt Queue Mode

This mode accepts prompt submissions from Android, but only writes them to a queue file on the PC. It does not inject them into a live Codex session.

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<long-random-bridge-token>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787 --allow-prompt-queue
```

Queued prompts are written to:

```text
%USERPROFILE%\.codex\monitor_prompts.jsonl
```

Read it with UTF-8 encoding:

```powershell
Get-Content $HOME\.codex\monitor_prompts.jsonl -Encoding utf8
```

### Start The PC Bridge: Live Daemon Send Mode

Use this only after read-only mode and queue mode work correctly.

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

Live send is guarded. The bridge sends a prompt to the daemon only when all of these are true:

- The Android request has a valid bridge token.
- The prompt is not empty.
- The target workspace is `connected` in Codex Monitor daemon metadata.
- The target thread exists in daemon `list_threads`.

If any condition fails, the prompt is queued instead when `--allow-prompt-queue` is enabled.

The bridge sends the prompt as both `message` and `text` to handle daemon request variants.

### Optional HTTPS With Tailscale Certificates

Tailscale already encrypts traffic inside the tailnet. If you also want HTTPS at the HTTP layer, generate a Tailscale certificate and pass it to the bridge:

```powershell
tailscale cert <pc-name>.<tailnet>.ts.net
python tools\codex_monitor_server.py `
  --host 0.0.0.0 `
  --port 8787 `
  --auth-token "<long-random-bridge-token>" `
  --tls-cert ".\<pc-name>.<tailnet>.ts.net.crt" `
  --tls-key ".\<pc-name>.<tailnet>.ts.net.key"
```

Use `https://<pc-name>.<tailnet>.ts.net:8787` in the Android app.

### Android App Usage

1. Open the app.
2. Tap `Server`.
3. Enter the bridge URL and bridge token.
4. Tap `Sync`.
5. Tap `Open` on a session to view recent messages.
6. Tap `Prompt` to submit text to the PC bridge.
7. If notifications are requested, allow them to receive approval-waiting alerts.

### API Endpoints

- `GET /health`: public connectivity check.
- `GET /sessions`: authenticated list of recent sessions.
- `GET /sessions/<sessionId>`: authenticated session detail.
- `GET /daemon`: authenticated daemon status.
- `POST /sessions/<sessionId>/prompt`: authenticated prompt submission.
- `POST /sessions`: authenticated new-session request, currently queued only.

### Security Notes

- Do not expose the bridge to the public internet.
- Prefer Tailscale-only access.
- Always use a long random bridge token when binding to `0.0.0.0`.
- Do not put the Codex Monitor daemon token into the Android app.
- The Android app only needs the bridge token.
- Use `--allow-no-auth` only for local testing.
- Live daemon sending is opt-in with `--enable-daemon-send`.

### Troubleshooting

`unauthorized`

- The Android bridge token does not match the PC bridge token.
- Reopen `Server` in the app and re-enter the token.

`Unable to resolve host`

- The Server URL is wrong, or Tailscale/MagicDNS is not connected.
- Try the PC Tailscale IPv4 address instead of the MagicDNS name.

`daemon workspace not connected`

- The target workspace is known to Codex Monitor but not currently connected.
- Open the workspace/session in Codex Monitor on the PC, then sync again.

Prompt is queued instead of sent live

- This is expected unless daemon sending is enabled and the target workspace/thread is connected.
- Check `/daemon` and the session card metadata.

Japanese text looks corrupted in PowerShell

- Read queue files with UTF-8:

```powershell
Get-Content $HOME\.codex\monitor_prompts.jsonl -Encoding utf8
```

---

## 日本語マニュアル

### このアプリでできること

- PC上のCodexセッション一覧をAndroidで表示します。
- `RUNNING`、`BLOCKED`、`DONE` などの状態を表示します。
- セッション内の最近のメッセージを確認できます。
- Approval待ちと思われるセッションを強調表示します。
- Approval待ちをAndroid通知として表示できます。
- PCブリッジを明示的に有効化した場合、Androidからプロンプトを投稿できます。

### 構成

```text
Androidアプリ
  -> Tailscaleネットワーク
  -> PCブリッジ: tools/codex_monitor_server.py
  -> PC上の ~/.codex/sessions のセッションファイル
  -> 任意: 127.0.0.1:4732 のCodex Monitor daemon
```

推奨通信経路はTailscaleです。Tailscale内の通信は暗号化されます。さらにPCブリッジ側でBearer token認証も使います。

### 必要なもの

- Android端末またはエミュレータ。
- Android端末側のTailscale接続。
- PC側のTailscale接続。
- PC上のPython 3。
- PC上のこのリポジトリ。
- live送信を使う場合はCodex Monitor daemon。

### Androidアプリのビルドとインストール

Android Studioでこのプロジェクトを開き、`app`構成を実行します。CLIでビルドする場合:

```powershell
.\gradlew.bat assembleDebug
```

接続済み端末へADBで入れる場合:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### PCブリッジ起動: 読み取り専用モード

最初はこのモードで動作確認してください。ローカルのCodexセッションファイルを読むだけで、Codex Monitor daemonには触りません。

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<長いランダムなブリッジトークン>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787
```

PCのTailscale IPまたはMagicDNS名を確認します。

```powershell
tailscale ip -4
```

Androidアプリの `Server` に以下を入力します。

```text
Server URL: http://<PCのTailscale IPまたは名前>:8787
Bridge token: <長いランダムなブリッジトークン>
```

その後 `Sync` を押します。

### PCブリッジ起動: プロンプトキューモード

Androidからのプロンプト投稿を受け付けます。ただしliveセッションへ直接投入せず、PC上のキューファイルへ保存します。

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<長いランダムなブリッジトークン>"
python tools\codex_monitor_server.py --host 0.0.0.0 --port 8787 --allow-prompt-queue
```

キューは以下に保存されます。

```text
%USERPROFILE%\.codex\monitor_prompts.jsonl
```

PowerShellではUTF-8指定で確認してください。

```powershell
Get-Content $HOME\.codex\monitor_prompts.jsonl -Encoding utf8
```

### PCブリッジ起動: daemon live送信モード

読み取り専用モードとキューモードが正常に動くことを確認してから使ってください。

```powershell
$env:CODEX_MONITOR_AUTH_TOKEN = "<長いランダムなブリッジトークン>"
python tools\codex_monitor_server.py `
  --host 0.0.0.0 `
  --port 8787 `
  --allow-prompt-queue `
  --enable-daemon `
  --enable-daemon-send `
  --daemon-host 127.0.0.1 `
  --daemon-port 4732 `
  --daemon-token "<Codex Monitor daemonのトークン>"
```

live送信には安全条件があります。以下をすべて満たす場合だけdaemonへ送ります。

- Androidリクエストのブリッジトークンが正しい。
- プロンプトが空ではない。
- 対象workspaceがCodex Monitor daemon上で `connected` になっている。
- 対象threadがdaemonの `list_threads` に存在する。

条件を満たさない場合、`--allow-prompt-queue` が有効ならキューへ保存します。

daemonのリクエスト差異に対応するため、プロンプト本文は `message` と `text` の両方で送ります。

### 任意: Tailscale証明書でHTTPS化

Tailscale内の通信はすでに暗号化されています。HTTPレイヤーでもHTTPSにしたい場合は、Tailscale証明書を生成してブリッジへ渡します。

```powershell
tailscale cert <PC名>.<tailnet>.ts.net
python tools\codex_monitor_server.py `
  --host 0.0.0.0 `
  --port 8787 `
  --auth-token "<長いランダムなブリッジトークン>" `
  --tls-cert ".\<PC名>.<tailnet>.ts.net.crt" `
  --tls-key ".\<PC名>.<tailnet>.ts.net.key"
```

Androidアプリでは `https://<PC名>.<tailnet>.ts.net:8787` を指定します。

### Androidアプリの使い方

1. アプリを開きます。
2. `Server` を押します。
3. ブリッジURLとブリッジトークンを入力します。
4. `Sync` を押します。
5. セッションの `Open` で最近のメッセージを確認します。
6. `Prompt` でPCブリッジへプロンプトを投稿します。
7. 通知許可を求められた場合、許可するとApproval待ち通知を受け取れます。

### APIエンドポイント

- `GET /health`: 接続確認用。認証なし。
- `GET /sessions`: 最近のセッション一覧。認証あり。
- `GET /sessions/<sessionId>`: セッション詳細。認証あり。
- `GET /daemon`: daemon状態。認証あり。
- `POST /sessions/<sessionId>/prompt`: プロンプト投稿。認証あり。
- `POST /sessions`: 新規セッション要求。現在はキュー保存のみ。

### セキュリティ注意点

- PCブリッジを公開インターネットへ晒さないでください。
- Tailscale内だけで使うことを推奨します。
- `0.0.0.0` で待ち受ける場合は、長いランダムなブリッジトークンを必ず使ってください。
- Codex Monitor daemonのトークンをAndroidアプリに入れないでください。
- Androidアプリに入れるのはブリッジトークンだけです。
- `--allow-no-auth` はローカルテスト専用です。
- daemon live送信は `--enable-daemon-send` を付けた場合だけ有効です。

### トラブルシュート

`unauthorized` が出る

- Android側のBridge tokenとPCブリッジのtokenが一致していません。
- アプリの `Server` を開いてtokenを入れ直してください。

`Unable to resolve host` が出る

- Server URLが間違っているか、Tailscale/MagicDNSが接続されていません。
- MagicDNS名ではなくTailscale IPv4アドレスを試してください。

`daemon workspace not connected` が出る

- Codex Monitorはworkspaceを知っていますが、現在接続中ではありません。
- PCのCodex Monitorで対象workspace/sessionを開いてから、Androidで再度Syncしてください。

Promptがlive送信されずキューに入る

- daemon送信が有効で、対象workspace/threadがconnectedの場合だけlive送信されます。
- `/daemon` とセッションカードのmetadataを確認してください。

PowerShellで日本語が文字化けする

- キューファイルはUTF-8で読んでください。

```powershell
Get-Content $HOME\.codex\monitor_prompts.jsonl -Encoding utf8
```

### GitHub Actions Release

This repository includes a GitHub Actions workflow at `.github/workflows/android.yml`.

- Pushes and pull requests to `main` run lint and build checks.
- Tags matching `v*` build an installable debug-signed APK and publish it to a GitHub Release.
- The release also includes a SHA-256 checksum file.

Create a release by pushing a version tag:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

The generated APK is intended as a preview package. For production distribution, add a proper Android signing configuration and store signing credentials in GitHub Actions secrets.

### GitHub Actionsでのリリース

このリポジトリには `.github/workflows/android.yml` のGitHub Actions workflowがあります。

- `main` へのpushとpull requestでlintとビルドを実行します。
- `v*` に一致するタグをpushすると、インストール可能なdebug署名APKをビルドしてGitHub Releaseへ添付します。
- SHA-256チェックサムも一緒に添付します。

リリースを作成するには、バージョンタグをpushします。

```powershell
git tag v0.1.0
git push origin v0.1.0
```

生成されるAPKはプレビュー配布用です。本番配布する場合は、正式なAndroid署名設定を追加し、署名情報をGitHub Actions secretsに保存してください。

### GitHub Packages

The workflow also publishes the APK to GitHub Packages as a Maven package:

```text
io.github.te9no:codex-monitor-android:<version>:debug@apk
```

Tag builds publish automatically. To publish an already released version from the current workflow, run `Android CI` manually with `package_version` set to the desired version, for example `0.1.0`.

### GitHub Packages

workflowはAPKをMaven packageとしてGitHub Packagesにも公開します。

```text
io.github.te9no:codex-monitor-android:<version>:debug@apk
```

タグビルドでは自動公開されます。既存リリースを現在のworkflowから公開したい場合は、`Android CI` を手動実行し、`package_version` に `0.1.0` のようなバージョンを指定してください。

### Install Error: Existing Debug Build

If Android says the app cannot be installed when using a Release APK, uninstall any locally built debug version first. Android does not allow updating an installed app with another APK signed by a different key.

```powershell
adb uninstall com.example.codexmonitor
```

After installing a signed Release APK once, future Release APKs from this repository should update normally as long as the same release signing key is used.

### インストールエラー: 既存debug版がある場合

Release APKのインストール時にAndroid側で失敗する場合、先にローカルdebugビルド版をアンインストールしてください。Androidは署名鍵が違うAPKで既存アプリを上書きできません。

```powershell
adb uninstall com.example.codexmonitor
```

一度署名済みRelease APKをインストールした後は、同じrelease署名鍵で作られたAPKなら通常通り更新できます。
