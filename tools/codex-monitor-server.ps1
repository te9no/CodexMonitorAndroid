param(
    [int]$Port = 8787,
    [int]$Limit = 20,
    [string]$CodexHome = "$env:USERPROFILE\.codex",
    [string]$AuthToken = $env:CODEX_MONITOR_AUTH_TOKEN
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($AuthToken)) {
    throw 'Set -AuthToken or CODEX_MONITOR_AUTH_TOKEN before binding the bridge to 0.0.0.0'
}

function Get-CodexSessions {
    $sessionRoot = Join-Path $CodexHome 'sessions'
    if (-not (Test-Path $sessionRoot)) {
        return @()
    }

    Get-ChildItem $sessionRoot -Recurse -File -Filter '*.jsonl' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First $Limit |
        ForEach-Object {
            $file = $_
            $id = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
            $name = $file.BaseName
            $cwd = ''

            try {
                $firstLine = Get-Content -LiteralPath $file.FullName -First 1
                $first = $firstLine | ConvertFrom-Json
                if ($first.payload.id) { $id = [string]$first.payload.id }
                if ($first.payload.cwd) {
                    $cwd = [string]$first.payload.cwd
                    $leaf = Split-Path $cwd -Leaf
                    if ($leaf) { $name = $leaf }
                }
            } catch {
                # Fall back to filename-derived metadata when a session line cannot be parsed.
            }

            $age = (Get-Date) - $file.LastWriteTime
            $status = if ($age.TotalMinutes -lt 30) { 'RUNNING' } elseif ($age.TotalHours -lt 24) { 'BLOCKED' } else { 'DONE' }

            [pscustomobject]@{
                id = $id
                name = $name
                detail = if ($cwd) { "Codex cwd: $cwd" } else { "Codex session file: $($file.FullName)" }
                status = $status
                updatedAt = $file.LastWriteTime.ToString('yyyy-MM-dd HH:mm')
            }
        }
}

function New-HttpResponse {
    param([int]$StatusCode, [object]$Body)

    $reason = if ($StatusCode -eq 200) { 'OK' } elseif ($StatusCode -eq 401) { 'Unauthorized' } elseif ($StatusCode -eq 404) { 'Not Found' } else { 'Internal Server Error' }
    $json = $Body | ConvertTo-Json -Depth 6 -Compress
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $headers = "HTTP/1.1 $StatusCode $reason`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($headers)

    $bytes = [byte[]]::new($headerBytes.Length + $bodyBytes.Length)
    [Array]::Copy($headerBytes, 0, $bytes, 0, $headerBytes.Length)
    [Array]::Copy($bodyBytes, 0, $bytes, $headerBytes.Length, $bodyBytes.Length)
    return $bytes
}

function Test-BridgeAuth {
    param([string[]]$Headers)

    foreach ($header in $Headers) {
        if ($header -match '^Authorization:\s*Bearer\s+(.+)$') {
            return [string]$Matches[1] -ceq $AuthToken
        }
        if ($header -match '^X-Codex-Monitor-Token:\s*(.+)$') {
            return [string]$Matches[1] -ceq $AuthToken
        }
    }

    return $false
}

$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, $Port)
$listener.Start()
Write-Host "Codex Monitor server listening on http://0.0.0.0:$Port"
Write-Host "HTTP bridge auth: enabled"
Write-Host "Android URL over Tailscale: http://<pc-tailscale-ip-or-name>:$Port"

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            $stream = $client.GetStream()
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
            $requestLine = $reader.ReadLine()
            $headers = @()
            while ($line = $reader.ReadLine()) {
                $headers += $line
            }

            $path = '/'
            if ($requestLine -match '^[A-Z]+\s+([^\s]+)') {
                $path = $Matches[1].Split('?')[0]
            }

            if ($path -eq '/health') {
                $response = New-HttpResponse 200 @{ ok = $true }
            } elseif (-not (Test-BridgeAuth $headers)) {
                $response = New-HttpResponse 401 @{ error = 'unauthorized' }
            } elseif ($path -eq '/sessions') {
                $response = New-HttpResponse 200 @{ sessions = @(Get-CodexSessions) }
            } else {
                $response = New-HttpResponse 404 @{ error = 'not found' }
            }

            $stream.Write($response, 0, $response.Length)
        } catch {
            $response = New-HttpResponse 500 @{ error = $_.Exception.Message }
            $stream.Write($response, 0, $response.Length)
        } finally {
            $client.Close()
        }
    }
} finally {
    $listener.Stop()
}
