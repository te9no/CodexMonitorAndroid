import argparse
import json
import secrets
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from datetime import datetime, timedelta
from urllib.parse import unquote
import os

import socket
import itertools
import ssl

DAEMON_HOST = os.environ.get('CODEX_MONITOR_DAEMON_HOST', '127.0.0.1')
DAEMON_PORT = int(os.environ.get('CODEX_MONITOR_DAEMON_PORT', '4732'))
DAEMON_TOKEN = os.environ.get('CODEX_MONITOR_DAEMON_TOKEN', '')
ENABLE_DAEMON = os.environ.get('CODEX_MONITOR_ENABLE_DAEMON', '').lower() in ('1', 'true', 'yes')
ENABLE_DAEMON_SEND = os.environ.get('CODEX_MONITOR_ENABLE_DAEMON_SEND', '').lower() in ('1', 'true', 'yes')
ALLOW_PROMPT_QUEUE = os.environ.get('CODEX_MONITOR_ALLOW_PROMPT_QUEUE', '').lower() in ('1', 'true', 'yes')
_rpc_ids = itertools.count(1)


def daemon_rpc(method, params=None, timeout=5):
    if not ENABLE_DAEMON:
        raise RuntimeError('daemon integration is disabled')
    if not DAEMON_TOKEN:
        raise RuntimeError('CODEX_MONITOR_DAEMON_TOKEN is not set')
    params = params or {}
    with socket.create_connection((DAEMON_HOST, DAEMON_PORT), timeout=timeout) as sock:
        sock.settimeout(timeout)
        auth = {'jsonrpc': '2.0', 'id': 0, 'method': 'auth', 'params': {'token': DAEMON_TOKEN}}
        sock.sendall((json.dumps(auth, separators=(',', ':')) + '\n').encode('utf-8'))
        auth_response = read_rpc_line(sock)
        if auth_response.get('error'):
            raise RuntimeError(auth_response['error'].get('message', 'daemon auth failed'))

        request_id = next(_rpc_ids)
        request = {'jsonrpc': '2.0', 'id': request_id, 'method': method, 'params': params}
        sock.sendall((json.dumps(request, separators=(',', ':')) + '\n').encode('utf-8'))
        response = read_rpc_line(sock)
        if response.get('error'):
            raise RuntimeError(response['error'].get('message', 'daemon rpc failed'))
        return response.get('result')


def read_rpc_line(sock):
    data = bytearray()
    while True:
        chunk = sock.recv(1)
        if not chunk:
            break
        data.extend(chunk)
        if chunk == b'\n':
            break
    if not data:
        return {}
    return json.loads(data.decode('utf-8'))


def daemon_status():
    if not ENABLE_DAEMON:
        return {
            'connected': False,
            'enabled': False,
            'host': f'{DAEMON_HOST}:{DAEMON_PORT}',
            'error': 'daemon integration is disabled',
        }
    try:
        workspaces = daemon_rpc('list_workspaces')
        usage = daemon_rpc('local_usage_snapshot')
        return {
            'connected': True,
            'enabled': True,
            'host': f'{DAEMON_HOST}:{DAEMON_PORT}',
            'workspaces': workspaces,
            'usage': usage,
        }
    except Exception as exc:
        return {
            'connected': False,
            'enabled': True,
            'host': f'{DAEMON_HOST}:{DAEMON_PORT}',
            'error': str(exc),
        }


def workspace_map_from_daemon():
    if not ENABLE_DAEMON:
        return {}
    try:
        result = daemon_rpc('list_workspaces')
    except Exception:
        return {}
    mapping = {}
    for item in result or []:
        path_value = item.get('path') or ''
        if path_value:
            mapping[normalize_path(path_value)] = item
    return mapping


def thread_map_from_daemon(workspaces):
    if not ENABLE_DAEMON:
        return {}
    threads = {}
    for workspace in (workspaces or {}).values():
        if not workspace.get('connected'):
            continue
        workspace_id = workspace.get('id')
        if not workspace_id:
            continue
        try:
            result = daemon_rpc('list_threads', {'workspaceId': workspace_id}, timeout=10)
            payload = unwrap_rpc_payload(result)
            for item in payload.get('data', []) if isinstance(payload, dict) else []:
                thread_id = item.get('id')
                if thread_id:
                    threads[str(thread_id)] = item
        except Exception:
            continue
    return threads


def unwrap_rpc_payload(result):
    payload = result
    while isinstance(payload, dict) and isinstance(payload.get('result'), dict):
        payload = payload['result']
    return payload


def normalize_path(value):
    return str(value).replace('/', '\\').rstrip('\\').lower()



def read_jsonl(path: Path):
    try:
        with path.open('r', encoding='utf-8', errors='replace') as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line)
                except Exception:
                    continue
    except Exception:
        return


def message_text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict):
                text = item.get('text') or item.get('input_text') or item.get('output_text')
                if text:
                    parts.append(str(text))
        return '\n'.join(parts)
    return ''


def summarize_event(event):
    payload = event.get('payload') or {}
    event_type = event.get('type')
    timestamp = event.get('timestamp', '')

    if event_type == 'response_item':
        payload_type = payload.get('type')
        if payload_type == 'message':
            role = payload.get('role', 'message')
            text = message_text(payload.get('content'))
            if text:
                return {'time': timestamp, 'role': role, 'text': trim(text, 1200)}
        if payload_type == 'function_call':
            name = payload.get('name', 'tool')
            args = payload.get('arguments', '')
            return {'time': timestamp, 'role': 'tool_call', 'text': trim(f'{name} {args}', 1200)}
        if payload_type == 'function_call_output':
            output = payload.get('output', '')
            if output:
                return {'time': timestamp, 'role': 'tool_output', 'text': trim(output, 1200)}

    if event_type == 'event_msg':
        payload_type = payload.get('type', 'event')
        text = payload.get('message') or payload.get('error') or ''
        if text:
            return {'time': timestamp, 'role': payload_type, 'text': trim(str(text), 1200)}

    return None


def trim(value, limit):
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=False)
    value = value.replace('\\r\\n', '\\n').strip()
    if len(value) <= limit:
        return value
    return value[:limit - 1] + '…'


def read_first_meta(path: Path):
    for event in read_jsonl(path):
        if event.get('type') == 'session_meta':
            return event.get('payload') or {}
    return {}


def session_status(updated: datetime, approval_pending: bool, daemon_thread=None):
    if approval_pending:
        return 'BLOCKED'
    if daemon_thread:
        status = daemon_thread.get('status') or {}
        status_type = str(status.get('type') or '').lower()
        if status_type in ('working', 'running', 'streaming', 'live'):
            return 'RUNNING'
        if status_type in ('waiting', 'blocked', 'needs_approval', 'requires_response'):
            return 'BLOCKED'
        return 'DONE'
    age = datetime.now() - updated
    if age < timedelta(hours=24):
        return 'DONE'
    return 'DONE'


def approval_pending(messages):
    if not messages:
        return False
    recent = '\n'.join(m.get('text', '') for m in messages[-12:]).lower()
    approval_markers = [
        'approval',
        'approve',
        'allow the action',
        'do you want to allow',
        'permission',
        'sandbox_permissions',
        'require_escalated',
        'request_user_input',
        'waiting for approval',
    ]
    resolution_markers = ['approved', 'denied', 'rejected', 'proceeding', 'user approved']
    return any(marker in recent for marker in approval_markers) and not any(marker in recent for marker in resolution_markers)


def session_from_file(file: Path, include_messages=True, workspaces=None, threads=None):
    stat = file.stat()
    updated = datetime.fromtimestamp(stat.st_mtime)
    meta = read_first_meta(file)
    session_id = meta.get('id') or file.stem
    cwd = meta.get('cwd') or ''
    name = Path(cwd).name if cwd else file.stem
    daemon_workspace = None
    daemon_thread = None
    if cwd and workspaces:
        daemon_workspace = workspaces.get(normalize_path(cwd))
    if threads:
        daemon_thread = threads.get(str(session_id))

    messages = []
    if include_messages:
        for event in read_jsonl(file):
            item = summarize_event(event)
            if item:
                messages.append(item)
        messages = messages[-24:]

    pending = approval_pending(messages)
    last_text = messages[-1]['text'] if messages else ''
    detail = last_text or (f'Codex cwd: {cwd}' if cwd else f'Codex session file: {file}')

    return {
        'id': str(session_id),
        'name': name,
        'detail': trim(detail, 500),
        'status': session_status(updated, pending, daemon_thread),
        'updatedAt': updated.strftime('%Y-%m-%d %H:%M'),
        'cwd': cwd,
        'approvalPending': pending,
        'messages': messages,
        'daemonWorkspace': daemon_workspace,
        'daemonConnected': bool(daemon_workspace and daemon_workspace.get('connected')),
        'daemonThread': daemon_thread,
    }


def session_files(codex_home: Path):
    root = codex_home / 'sessions'
    if not root.exists():
        return []
    return sorted(root.rglob('*.jsonl'), key=lambda p: p.stat().st_mtime, reverse=True)


def codex_sessions(codex_home: Path, limit: int):
    workspaces = workspace_map_from_daemon()
    threads = thread_map_from_daemon(workspaces)
    return [session_from_file(path, workspaces=workspaces, threads=threads) for path in session_files(codex_home)[:limit]]


def find_session_file(codex_home: Path, session_id: str):
    for file in session_files(codex_home):
        meta = read_first_meta(file)
        if str(meta.get('id') or file.stem) == session_id:
            return file
    return None


def prompt_queue_path(codex_home: Path):
    path = codex_home / 'monitor_prompts.jsonl'
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def queue_prompt(codex_home: Path, item):
    item = dict(item)
    item.setdefault('timestamp', datetime.now().isoformat(timespec='seconds'))
    item.setdefault('status', 'queued')
    item.setdefault('daemon', daemon_execution_disabled())
    path = prompt_queue_path(codex_home)
    if path.exists() and path.stat().st_size > 0:
        with path.open('rb+') as handle:
            handle.seek(-1, os.SEEK_END)
            if handle.read(1) != b'\n':
                handle.write(b'\n')
    with path.open('a', encoding='utf-8', newline='\n') as handle:
        handle.write(json.dumps(item, ensure_ascii=False) + '\n')
    return item


def daemon_execution_disabled():
    return {
        'sent': False,
        'skipped': True,
        'reason': 'daemon execution is disabled; mobile requests are queued only',
    }


def try_send_prompt_via_daemon(session_id: str, file: Path, prompt: str):
    if not ENABLE_DAEMON_SEND:
        return daemon_execution_disabled()
    if not prompt.strip():
        return {'sent': False, 'error': 'prompt is required'}

    meta = read_first_meta(file)
    cwd = meta.get('cwd') or ''
    workspace = workspace_map_from_daemon().get(normalize_path(cwd)) if cwd else None
    if not workspace:
        return {'sent': False, 'error': 'daemon workspace not found'}
    if not workspace.get('connected'):
        return {'sent': False, 'workspaceId': workspace.get('id'), 'error': 'daemon workspace not connected'}

    workspace_id = workspace.get('id')
    threads = thread_map_from_daemon({normalize_path(cwd): workspace})
    thread = threads.get(str(session_id))
    if not thread:
        return {
            'sent': False,
            'workspaceId': workspace_id,
            'error': 'daemon thread not found; open the target session in Codex Monitor first',
        }

    params = {
        'workspaceId': workspace_id,
        'threadId': str(thread.get('id') or session_id),
        'message': prompt,
        'text': prompt,
    }
    try:
        result = daemon_rpc('send_user_message', params, timeout=10)
        nested_error = extract_nested_error(result)
        if nested_error:
            return {'sent': False, 'workspaceId': workspace_id, 'threadId': params['threadId'], 'error': nested_error, 'result': result}
        return {'sent': True, 'workspaceId': workspace_id, 'threadId': params['threadId'], 'result': result}
    except Exception as exc:
        return {'sent': False, 'workspaceId': workspace_id, 'threadId': params['threadId'], 'error': str(exc)}


def extract_nested_error(result):
    if isinstance(result, dict):
        if isinstance(result.get('error'), dict):
            return result['error'].get('message') or json.dumps(result['error'], ensure_ascii=False)
        inner = result.get('result')
        while isinstance(inner, dict):
            if isinstance(inner.get('error'), dict):
                return inner['error'].get('message') or json.dumps(inner['error'], ensure_ascii=False)
            inner = inner.get('result')
    return None


class Handler(BaseHTTPRequestHandler):
    codex_home = Path.home() / '.codex'
    limit = 20
    auth_token = ''

    def require_auth(self):
        if not self.auth_token:
            return True
        header = self.headers.get('Authorization', '')
        token = ''
        if header.startswith('Bearer '):
            token = header[len('Bearer '):].strip()
        if not token:
            token = self.headers.get('X-Codex-Monitor-Token', '').strip()
        if secrets.compare_digest(token, self.auth_token):
            return True
        self.write_json(401, {'error': 'unauthorized'})
        return False

    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path == '/health':
            self.write_json(200, {'ok': True})
            return
        if not self.require_auth():
            return
        if path == '/daemon':
            self.write_json(200, {'daemon': daemon_status()})
            return
        if path == '/sessions':
            self.write_json(200, {'sessions': codex_sessions(self.codex_home, self.limit)})
            return
        if path.startswith('/sessions/'):
            session_id = unquote(path.split('/')[2])
            file = find_session_file(self.codex_home, session_id)
            if not file:
                self.write_json(404, {'error': 'session not found'})
                return
            self.write_json(200, {'session': session_from_file(file, workspaces=workspace_map_from_daemon())})
            return
        self.write_json(404, {'error': 'not found'})

    def do_POST(self):
        if not self.require_auth():
            return
        path = self.path.split('?', 1)[0]
        length = int(self.headers.get('Content-Length', '0'))
        body = self.rfile.read(length).decode('utf-8') if length else '{}'
        try:
            payload = json.loads(body)
        except Exception:
            self.write_json(400, {'error': 'invalid json'})
            return

        if path == '/sessions':
            if not ALLOW_PROMPT_QUEUE:
                self.write_json(409, {
                    'ok': False,
                    'error': 'prompt submission is disabled on the PC bridge',
                })
                return
            prompt = str(payload.get('prompt') or payload.get('detail') or payload.get('name') or '').strip()
            if not prompt:
                self.write_json(400, {'error': 'prompt is required'})
                return
            item = queue_prompt(self.codex_home, {
                'name': str(payload.get('name') or '').strip(),
                'detail': str(payload.get('detail') or '').strip(),
                'prompt': prompt,
                'source': 'android',
            })
            self.write_json(202, {'ok': True, 'created': False, 'queued': True, 'item': item})
            return

        if not (path.startswith('/sessions/') and path.endswith('/prompt')):
            self.write_json(404, {'error': 'not found'})
            return

        if not (ALLOW_PROMPT_QUEUE or ENABLE_DAEMON_SEND):
            self.write_json(409, {
                'ok': False,
                'error': 'prompt submission is disabled on the PC bridge',
            })
            return

        session_id = unquote(path.split('/')[2])

        prompt = str(payload.get('prompt') or '').strip()
        if not prompt:
            self.write_json(400, {'error': 'prompt is required'})
            return

        file = find_session_file(self.codex_home, session_id)
        if not file:
            self.write_json(404, {'error': 'session not found'})
            return

        daemon_result = try_send_prompt_via_daemon(session_id, file, prompt)
        if daemon_result.get('sent'):
            self.write_json(200, {
                'ok': True,
                'sent': True,
                'queued': False,
                'daemon': daemon_result,
            })
            return

        if not ALLOW_PROMPT_QUEUE:
            self.write_json(502, {
                'ok': False,
                'sent': False,
                'queued': False,
                'daemon': daemon_result,
                'error': daemon_result.get('error') or 'daemon send failed',
            })
            return

        item = queue_prompt(self.codex_home, {
            'sessionId': session_id,
            'sessionFile': str(file),
            'prompt': prompt,
            'source': 'android',
            'daemon': daemon_result,
        })

        self.write_json(202, {
            'ok': True,
            'sent': False,
            'queued': True,
            'item': item,
        })

    def log_message(self, fmt, *args):
        print('%s - %s' % (self.address_string(), fmt % args), flush=True)

    def write_json(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(data)


def main():
    global DAEMON_HOST, DAEMON_PORT, DAEMON_TOKEN, ENABLE_DAEMON, ENABLE_DAEMON_SEND, ALLOW_PROMPT_QUEUE
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8787)
    parser.add_argument('--limit', type=int, default=20)
    parser.add_argument('--codex-home', default=os.path.join(str(Path.home()), '.codex'))
    parser.add_argument('--daemon-host', default=DAEMON_HOST)
    parser.add_argument('--daemon-port', type=int, default=DAEMON_PORT)
    parser.add_argument('--daemon-token', default=DAEMON_TOKEN)
    parser.add_argument('--enable-daemon', action='store_true', default=ENABLE_DAEMON)
    parser.add_argument('--enable-daemon-send', action='store_true', default=ENABLE_DAEMON_SEND)
    parser.add_argument('--auth-token', default=os.environ.get('CODEX_MONITOR_AUTH_TOKEN', ''))
    parser.add_argument('--allow-no-auth', action='store_true')
    parser.add_argument('--allow-prompt-queue', action='store_true', default=ALLOW_PROMPT_QUEUE)
    parser.add_argument('--tls-cert', default=os.environ.get('CODEX_MONITOR_TLS_CERT', ''))
    parser.add_argument('--tls-key', default=os.environ.get('CODEX_MONITOR_TLS_KEY', ''))
    args = parser.parse_args()

    if args.host not in ('127.0.0.1', 'localhost', '::1') and not args.auth_token and not args.allow_no_auth:
        parser.error('--auth-token, CODEX_MONITOR_AUTH_TOKEN, or --allow-no-auth is required when binding beyond localhost')
    if args.allow_prompt_queue and not args.auth_token:
        parser.error('--allow-prompt-queue requires --auth-token or CODEX_MONITOR_AUTH_TOKEN')
    if args.enable_daemon_send and not args.enable_daemon:
        parser.error('--enable-daemon-send requires --enable-daemon')
    if args.enable_daemon_send and not args.auth_token:
        parser.error('--enable-daemon-send requires --auth-token or CODEX_MONITOR_AUTH_TOKEN')
    if bool(args.tls_cert) != bool(args.tls_key):
        parser.error('--tls-cert and --tls-key must be provided together')

    DAEMON_HOST = args.daemon_host
    DAEMON_PORT = args.daemon_port
    DAEMON_TOKEN = args.daemon_token
    ENABLE_DAEMON = args.enable_daemon
    ENABLE_DAEMON_SEND = args.enable_daemon_send
    ALLOW_PROMPT_QUEUE = args.allow_prompt_queue

    Handler.codex_home = Path(args.codex_home)
    Handler.limit = args.limit
    Handler.auth_token = args.auth_token
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    scheme = 'https' if args.tls_cert else 'http'
    if args.tls_cert:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(args.tls_cert, args.tls_key)
        server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f'Codex Monitor server listening on {scheme}://{args.host}:{args.port}', flush=True)
    auth_state = 'enabled' if Handler.auth_token else 'disabled'
    print(f'HTTP bridge auth: {auth_state}', flush=True)
    daemon_state = 'enabled' if ENABLE_DAEMON else 'disabled'
    print(f'Codex Monitor daemon integration: {daemon_state}', flush=True)
    daemon_send_state = 'enabled' if ENABLE_DAEMON_SEND else 'disabled'
    print(f'Codex Monitor daemon prompt send: {daemon_send_state}', flush=True)
    queue_state = 'enabled' if ALLOW_PROMPT_QUEUE else 'disabled'
    print(f'Prompt queue submission: {queue_state}', flush=True)
    print(f'Android over Tailscale: {scheme}://<pc-tailscale-ip-or-name>:{args.port}', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
