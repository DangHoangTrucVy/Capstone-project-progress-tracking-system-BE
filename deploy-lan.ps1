# Deploy the backend on this PC so other machines on the same LAN/Wi-Fi can use http://<PC-IP>:8080
# Run from the project folder in PowerShell (as Administrator the first time, to open the firewall):
#   powershell -ExecutionPolicy Bypass -File .\deploy-lan.ps1
# Options: -Port 8080  -Ip 192.168.1.10 (override auto-detect)  -NoBuild

param(
    [int]$Port = 8080,
    [string]$Ip = "",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$ComposeFile = "docker-compose.lan.yml"
$EnvFile     = ".env.lan"
$Project     = "capstone-lan"

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# 1. Docker
Write-Step "Checking Docker"
try { docker info *> $null } catch { }
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker is not running. Start Docker Desktop and run this script again." -ForegroundColor Red
    exit 1
}

# 2. LAN IP (interface that has the default gateway)
Write-Step "Detecting LAN IP"
if (-not $Ip) {
    $cfg = Get-NetIPConfiguration |
        Where-Object { $_.IPv4DefaultGateway -ne $null -and $_.NetAdapter.Status -eq "Up" } |
        Select-Object -First 1
    if ($cfg) { $Ip = ($cfg.IPv4Address | Select-Object -First 1).IPAddress }
}
if (-not $Ip) {
    Write-Host "Could not detect the LAN IP. Re-run with -Ip <your-ip> (see 'ipconfig')." -ForegroundColor Red
    exit 1
}
Write-Host "LAN IP: $Ip"

# 3. .env.lan (secrets generated once, CORS refreshed every run in case the IP changed)
Write-Step "Preparing $EnvFile"
function New-Hex([int]$bytes) {
    $b = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    return -join ($b | ForEach-Object { $_.ToString("x2") })
}

$envMap = [ordered]@{}
if (Test-Path $EnvFile) {
    foreach ($line in Get-Content $EnvFile) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $k, $v = $line -split '=', 2
        $envMap[$k.Trim()] = $v
    }
}
$defaults = [ordered]@{
    POSTGRES_DB            = "capstone_tracking"
    POSTGRES_USER          = "capstone_user"
    POSTGRES_PASSWORD      = (New-Hex 24)
    JWT_SECRET             = (New-Hex 48)
    SPRING_PROFILES_ACTIVE = "dev"
    PASSWORD_LOGIN_ENABLED = "true"
    ALLOWED_EMAIL_DOMAIN   = "fpt.edu.vn"
    GOOGLE_CLIENT_IDS      = ""
    MAIL_ENABLED           = "false"
    EXTRA_CORS_ORIGINS     = ""
}
foreach ($k in $defaults.Keys) { if (-not $envMap.Contains($k)) { $envMap[$k] = $defaults[$k] } }

$origins = @(
    "http://localhost:3000", "http://localhost:5173",
    "http://127.0.0.1:3000", "http://127.0.0.1:5173",
    "http://${Ip}:3000", "http://${Ip}:5173"
)
if ($envMap["EXTRA_CORS_ORIGINS"]) { $origins += ($envMap["EXTRA_CORS_ORIGINS"] -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
$envMap["CORS_ALLOWED_ORIGINS"] = ($origins | Select-Object -Unique) -join ","
$envMap["APP_PORT"] = "$Port"
$envMap["LAN_IP"]   = $Ip

$lines = @("# Generated/updated by deploy-lan.ps1. Never commit this file. Do not use '$' in values.",
           "# Add FE origins on other machines to EXTRA_CORS_ORIGINS (comma-separated, no trailing slash).")
foreach ($k in $envMap.Keys) { $lines += "$k=$($envMap[$k])" }
# UTF-8 without BOM so docker compose parses the first key correctly
[System.IO.File]::WriteAllLines((Join-Path $PSScriptRoot $EnvFile), $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "CORS: $($envMap['CORS_ALLOWED_ORIGINS'])"

# 4. Windows Firewall
Write-Step "Opening Windows Firewall port $Port"
$ruleName = "Capstone Backend $Port"
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
    Write-Host "Firewall rule already exists."
} elseif ($isAdmin) {
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any | Out-Null
    Write-Host "Firewall rule created."
} else {
    Write-Host "Not running as Administrator - firewall NOT opened. Other machines will be blocked until you run (as Admin):" -ForegroundColor Yellow
    Write-Host "  New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any" -ForegroundColor Yellow
}

# 5. Start
Write-Step "Starting containers (first build downloads Maven deps, may take several minutes)"
$upArgs = @("compose", "-p", $Project, "-f", $ComposeFile, "--env-file", $EnvFile, "up", "-d")
if (-not $NoBuild) { $upArgs += "--build" }
& docker @upArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker compose failed. If port $Port is busy (e.g. the dev docker-compose.yml), stop it or use -Port 8082." -ForegroundColor Red
    exit 1
}

# 6. Wait until the API answers
Write-Step "Waiting for the backend to be ready"
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://localhost:$Port/v3/api-docs"
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 5
}
if (-not $ready) {
    Write-Host "Backend not ready after 5 minutes. Check logs:" -ForegroundColor Yellow
    Write-Host "  docker compose -p $Project logs -f app"
    exit 1
}

Write-Host ""
Write-Host "Backend is up." -ForegroundColor Green
Write-Host "  On this PC     : http://localhost:$Port/swagger-ui.html"
Write-Host "  From the LAN   : http://${Ip}:$Port/swagger-ui.html"
Write-Host "  FE API base URL: http://${Ip}:$Port"
Write-Host ""
Write-Host "Logs : docker compose -p $Project logs -f app"
Write-Host "Stop : docker compose -p $Project -f $ComposeFile --env-file $EnvFile down   (add -v to wipe the database)"
