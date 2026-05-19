from __future__ import annotations

import os
import secrets
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import ThreadingHTTPServer
from pathlib import Path
from tkinter import END, BOTH, LEFT, RIGHT, X, Tk, StringVar, BooleanVar, messagebox
from tkinter import ttk
from tkinter.scrolledtext import ScrolledText

import codex_monitor_server

APP_DIR_NAME = 'com.dimillian.codexmonitor'
BRIDGE_TOKEN_FILE = 'bridge-token.txt'
DAEMON_TOKEN_FILE = 'daemon-token.txt'
DEFAULT_PORT = '8787'
DEFAULT_HOST = '0.0.0.0'


def app_data_dir() -> Path:
    root = os.environ.get('APPDATA') or str(Path.home())
    path = Path(root) / APP_DIR_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding='utf-8').strip()
    except FileNotFoundError:
        return ''


def ensure_bridge_token(config_dir: Path) -> str:
    env_token = os.environ.get('CODEX_MONITOR_AUTH_TOKEN', '').strip()
    if env_token:
        return env_token

    token_path = config_dir / BRIDGE_TOKEN_FILE
    token = read_text(token_path)
    if token:
        return token

    token = secrets.token_urlsafe(32)
    token_path.write_text(token + '\n', encoding='utf-8')
    try:
        os.chmod(token_path, 0o600)
    except OSError:
        pass
    return token


def daemon_token(config_dir: Path) -> str:
    env_token = os.environ.get('CODEX_MONITOR_DAEMON_TOKEN', '').strip()
    if env_token:
        return env_token
    return read_text(config_dir / DAEMON_TOKEN_FILE)


def command_output(*args: str) -> str:
    try:
        completed = subprocess.run(
            args,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding='utf-8',
            errors='replace',
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
        )
    except OSError:
        return ''
    if completed.returncode != 0:
        return ''
    return completed.stdout.strip()


def suggested_host() -> str:
    tailscale_ip = command_output('tailscale', 'ip', '-4').splitlines()
    if tailscale_ip:
        return tailscale_ip[0].strip()
    return socket.gethostname()


def build_server_args(config_dir: Path) -> list[str]:
    bridge_token = ensure_bridge_token(config_dir)
    daemon = daemon_token(config_dir)
    port = os.environ.get('CODEX_MONITOR_PORT', DEFAULT_PORT).strip() or DEFAULT_PORT
    host = os.environ.get('CODEX_MONITOR_HOST', DEFAULT_HOST).strip() or DEFAULT_HOST

    args = [
        'codex_monitor_server',
        '--host', host,
        '--port', port,
        '--auth-token', bridge_token,
        '--allow-prompt-queue',
    ]
    if daemon:
        args.extend([
            '--enable-daemon',
            '--enable-daemon-send',
            '--daemon-host', os.environ.get('CODEX_MONITOR_DAEMON_HOST', '127.0.0.1'),
            '--daemon-port', os.environ.get('CODEX_MONITOR_DAEMON_PORT', '4732'),
            '--daemon-token', daemon,
        ])
    return args


def print_startup_summary(config_dir: Path, args: list[str]) -> None:
    port = args[args.index('--port') + 1]
    token_path = config_dir / BRIDGE_TOKEN_FILE
    daemon_path = config_dir / DAEMON_TOKEN_FILE
    print('Codex Monitor PC Bridge')
    print('')
    print(f'Listener: http://0.0.0.0:{port}')
    print(f'Android Server URL over Tailscale: http://{suggested_host()}:{port}')
    print(f'Bridge token file: {token_path}')
    print(f'Daemon token file (optional): {daemon_path}')
    print('')
    print('Use the bridge token in the Android app. Do not put the daemon token in Android.')
    print('Press Ctrl+C to stop.')
    print('')


class BridgeApp:
    def __init__(self) -> None:
        self.config_dir = app_data_dir()
        self.bridge_token = ensure_bridge_token(self.config_dir)
        self.daemon_token = daemon_token(self.config_dir)
        self.host = os.environ.get('CODEX_MONITOR_HOST', DEFAULT_HOST).strip() or DEFAULT_HOST
        self.port = os.environ.get('CODEX_MONITOR_PORT', DEFAULT_PORT).strip() or DEFAULT_PORT
        self.server: ThreadingHTTPServer | None = None
        self.server_thread: threading.Thread | None = None
        self.stopping = False
        self.status_refresh_running = False

        self.root = Tk()
        self.root.title('Codex Monitor Bridge')
        self.root.geometry('760x560')
        self.root.minsize(680, 500)

        self.listener_status = StringVar(value='Stopped')
        self.daemon_status = StringVar(value='Checking...')
        self.server_url = StringVar(value=self.android_url())
        self.token_path = StringVar(value=str(self.config_dir / BRIDGE_TOKEN_FILE))
        self.daemon_token_path = StringVar(value=str(self.config_dir / DAEMON_TOKEN_FILE))
        self.live_send_enabled = BooleanVar(value=bool(self.daemon_token))

        self.build_ui()
        self.root.protocol('WM_DELETE_WINDOW', self.on_close)
        self.root.after(150, self.start_server)
        self.root.after(500, self.refresh_daemon_status)

    def android_url(self) -> str:
        return f'http://{suggested_host()}:{self.port}'

    def build_ui(self) -> None:
        root = ttk.Frame(self.root, padding=16)
        root.pack(fill=BOTH, expand=True)

        title = ttk.Label(root, text='Codex Monitor Bridge', font=('Segoe UI', 18, 'bold'))
        title.pack(anchor='w')
        subtitle = ttk.Label(root, text='AndroidからPC上のCodexセッションへ接続するためのローカルリスナーです。')
        subtitle.pack(anchor='w', pady=(2, 14))

        status_frame = ttk.LabelFrame(root, text='Status', padding=12)
        status_frame.pack(fill=X)
        self.add_value_row(status_frame, 'Listener', self.listener_status)
        self.add_value_row(status_frame, 'Android Server URL', self.server_url)
        self.add_value_row(status_frame, 'Daemon', self.daemon_status)

        button_row = ttk.Frame(root)
        button_row.pack(fill=X, pady=(10, 14))
        self.start_button = ttk.Button(button_row, text='Start listener', command=self.start_server)
        self.start_button.pack(side=LEFT)
        self.stop_button = ttk.Button(button_row, text='Stop listener', command=self.stop_server)
        self.stop_button.pack(side=LEFT, padx=(8, 0))
        ttk.Button(button_row, text='Copy URL', command=self.copy_url).pack(side=LEFT, padx=(8, 0))
        ttk.Button(button_row, text='Open Android URL', command=lambda: webbrowser.open(self.server_url.get())).pack(side=LEFT, padx=(8, 0))
        ttk.Button(button_row, text='Refresh daemon', command=self.refresh_daemon_status).pack(side=RIGHT)

        settings = ttk.LabelFrame(root, text='Tokens', padding=12)
        settings.pack(fill=X)
        self.add_path_row(settings, 'Bridge token file', self.token_path, self.copy_bridge_token)
        self.add_path_row(settings, 'Daemon token file', self.daemon_token_path, self.open_config_folder)
        helper = ttk.Label(
            settings,
            text='AndroidにはBridge tokenだけを入力します。daemon tokenはPC側にだけ保存してください。',
        )
        helper.pack(anchor='w', pady=(8, 0))

        log_frame = ttk.LabelFrame(root, text='Log', padding=8)
        log_frame.pack(fill=BOTH, expand=True, pady=(14, 0))
        self.log_text = ScrolledText(log_frame, height=10, wrap='word')
        self.log_text.pack(fill=BOTH, expand=True)
        self.log('Ready. Listener starts automatically.')
        self.log(f'Bridge token file: {self.token_path.get()}')

    def add_value_row(self, parent: ttk.Frame, label: str, value: StringVar) -> None:
        row = ttk.Frame(parent)
        row.pack(fill=X, pady=3)
        ttk.Label(row, text=label, width=20).pack(side=LEFT)
        ttk.Label(row, textvariable=value).pack(side=LEFT, fill=X, expand=True)

    def add_path_row(self, parent: ttk.Frame, label: str, value: StringVar, command) -> None:
        row = ttk.Frame(parent)
        row.pack(fill=X, pady=3)
        ttk.Label(row, text=label, width=20).pack(side=LEFT)
        ttk.Entry(row, textvariable=value, state='readonly').pack(side=LEFT, fill=X, expand=True)
        ttk.Button(row, text='Copy token' if 'Bridge' in label else 'Open folder', command=command).pack(side=LEFT, padx=(8, 0))

    def log(self, message: str) -> None:
        stamp = time.strftime('%H:%M:%S')
        self.log_text.insert(END, f'[{stamp}] {message}\n')
        self.log_text.see(END)

    def configure_server_module(self) -> None:
        codex_monitor_server.DAEMON_HOST = os.environ.get('CODEX_MONITOR_DAEMON_HOST', '127.0.0.1')
        codex_monitor_server.DAEMON_PORT = int(os.environ.get('CODEX_MONITOR_DAEMON_PORT', '4732'))
        codex_monitor_server.DAEMON_TOKEN = daemon_token(self.config_dir)
        codex_monitor_server.ENABLE_DAEMON = bool(codex_monitor_server.DAEMON_TOKEN)
        codex_monitor_server.ENABLE_DAEMON_SEND = bool(codex_monitor_server.DAEMON_TOKEN)
        codex_monitor_server.ALLOW_PROMPT_QUEUE = True
        codex_monitor_server.Handler.codex_home = Path(os.environ.get('CODEX_HOME', str(Path.home() / '.codex')))
        codex_monitor_server.Handler.limit = int(os.environ.get('CODEX_MONITOR_LIMIT', '20'))
        codex_monitor_server.Handler.auth_token = self.bridge_token

    def start_server(self) -> None:
        if self.server:
            self.log('Listener is already running.')
            return
        try:
            self.configure_server_module()
            self.server = ThreadingHTTPServer((self.host, int(self.port)), codex_monitor_server.Handler)
        except OSError as exc:
            self.server = None
            self.listener_status.set(f'Error: {exc}')
            self.log(f'Failed to start listener: {exc}')
            messagebox.showerror('Listener error', str(exc))
            return

        self.server_thread = threading.Thread(target=self.server.serve_forever, name='codex-monitor-bridge', daemon=True)
        self.server_thread.start()
        self.listener_status.set(f'Running on {self.host}:{self.port}')
        self.server_url.set(self.android_url())
        self.start_button.state(['disabled'])
        self.stop_button.state(['!disabled'])
        self.log(f'Listener started: http://{self.host}:{self.port}')
        self.log(f'Android Server URL: {self.server_url.get()}')

    def stop_server(self) -> None:
        if not self.server:
            self.listener_status.set('Stopped')
            self.start_button.state(['!disabled'])
            self.stop_button.state(['disabled'])
            return
        server = self.server
        self.server = None
        self.listener_status.set('Stopping...')
        self.log('Stopping listener...')

        def worker() -> None:
            server.shutdown()
            server.server_close()
            self.root.after(0, self.on_server_stopped)

        threading.Thread(target=worker, daemon=True).start()

    def on_server_stopped(self) -> None:
        self.listener_status.set('Stopped')
        self.start_button.state(['!disabled'])
        self.stop_button.state(['disabled'])
        self.log('Listener stopped.')

    def refresh_daemon_status(self) -> None:
        if self.status_refresh_running:
            return
        self.status_refresh_running = True
        self.daemon_status.set('Checking...')

        def worker() -> None:
            self.configure_server_module()
            status = codex_monitor_server.daemon_status()
            self.root.after(0, lambda: self.apply_daemon_status(status))

        threading.Thread(target=worker, daemon=True).start()

    def apply_daemon_status(self, status: dict) -> None:
        self.status_refresh_running = False
        if not status.get('enabled'):
            self.daemon_status.set('Disabled: daemon token not configured')
            self.log('Daemon disabled. Add daemon token on PC to enable live prompt send.')
        elif status.get('connected'):
            workspaces = status.get('workspaces') or []
            connected = sum(1 for item in workspaces if isinstance(item, dict) and item.get('connected'))
            self.daemon_status.set(f'Connected: {connected}/{len(workspaces)} workspaces connected')
            self.log(f'Daemon connected. Workspaces: {connected}/{len(workspaces)} connected.')
        else:
            self.daemon_status.set(f'Error: {status.get("error", "unknown")}'[:140])
            self.log(f'Daemon error: {status.get("error", "unknown")}')
        self.root.after(10000, self.refresh_daemon_status)

    def copy_url(self) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(self.server_url.get())
        self.log('Copied Android Server URL.')

    def copy_bridge_token(self) -> None:
        token = ensure_bridge_token(self.config_dir)
        self.root.clipboard_clear()
        self.root.clipboard_append(token)
        self.log('Copied bridge token. Paste it into the Android app only.')

    def open_config_folder(self) -> None:
        os.startfile(str(self.config_dir))

    def on_close(self) -> None:
        if self.server:
            server = self.server
            self.server = None
            server.shutdown()
            server.server_close()
        self.root.destroy()

    def run(self) -> int:
        self.stop_button.state(['disabled'])
        self.root.mainloop()
        return 0


def main() -> int:
    if len(sys.argv) > 1:
        sys.argv[0] = 'codex_monitor_server'
        codex_monitor_server.main()
        return 0

    if os.environ.get('CODEX_MONITOR_CONSOLE', '').lower() in ('1', 'true', 'yes'):
        config_dir = app_data_dir()
        sys.argv = build_server_args(config_dir)
        print_startup_summary(config_dir, sys.argv)
        codex_monitor_server.main()
        return 0

    return BridgeApp().run()


if __name__ == '__main__':
    raise SystemExit(main())
