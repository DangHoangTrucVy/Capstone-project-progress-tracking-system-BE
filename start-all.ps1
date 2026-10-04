# Start backend + frontend after Windows boots/signs in, and print the public FE URL.
# Normally run automatically by the scheduled task from install-autostart.ps1.
# Manual: powershell -ExecutionPolicy Bypass -File .\start-all.ps1
# Requires: .\deploy-lan.ps1 (BE) and .\deploy-fe.ps1 (FE) have each been run once.

param(
    [string]$FeDir = ""   # default: sibling folder whose name ends with "-FE"
)

$ErrorActionPreference = "Continue"
$BeDir = $PSScriptRoot
if (-not $FeDir) {
    $FeDir = Get-ChildItem -Path (Split-Path $BeDir -Parent) -Directory |
        Where-Object { $_.Name -like "*-FE" } | Select-Object -First 1 -ExpandProperty FullName
}
$log = Join-Path $BeDir "start-all.log"
function Log($m) { $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $m; Write-Host $line; Add-Content -Path $log -Value $line }

Log "=== start-all (BE: $BeDir, FE: $FeDir)"

# 1. Docker Desktop
docker info *> $null
if ($LASTEXITCODE -ne 0) {
    $dd = "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
    if (Test-Path $dd) { Log "Starting Docker Desktop"; Start-Process $dd }
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 5
        docker info *> $null
        if ($LASTEXITCODE -eq 0) { break }
    }
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { Log "ERROR: Docker not ready after 5 minutes"; exit 1 }
Log "Docker ready"

# 2. Backend (LAN stack; the BE-only tunnel is not needed since the FE tunnel proxies /api)
Set-Location $BeDir
if (-not (Test-Path ".env.lan")) { Log "ERROR: .env.lan missing - run .\deploy-lan.ps1 once"; exit 1 }
docker compose -p capstone-lan -f docker-compose.lan.yml --env-file .env.lan up -d --remove-orphans 2>&1 | ForEach-Object { Log $_ }

# 3. Frontend + tunnel
if (-not $FeDir -or -not (Test-Path (Join-Path $FeDir "docker-compose.yml"))) { Log "ERROR: FE folder not found (use -FeDir)"; exit 1 }
Set-Location $FeDir
docker compose -p capstone-fe up -d 2>&1 | ForEach-Object { Log $_ }

# 4. Public URL (changes on every tunnel restart)
$url = $null
for ($i = 0; $i -lt 45 -and -not $url; $i++) {
    Start-Sleep -Seconds 2
    $logs = (docker compose -p capstone-fe logs cloudflared 2>&1) -join "`n"
    $m = [regex]::Matches($logs, 'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($m.Count -gt 0) { $url = $m[$m.Count - 1].Value }
}
if ($url) {
    Set-Content -Path (Join-Path $FeDir "fe-url.txt") -Value $url -Encoding ascii
    Log "Public URL: $url"
} else {
    Log "WARN: public URL not found yet (docker compose -p capstone-fe logs cloudflared)"
}
Log "Done"
