# Expose the LAN backend on a free public HTTPS URL (Cloudflare Quick Tunnel, no account needed).
# Run from the project folder after deploy-lan.ps1 has created .env.lan:
#   powershell -ExecutionPolicy Bypass -File .\tunnel-up.ps1
# Stop the tunnel only: docker compose -p capstone-lan -f docker-compose.lan.yml -f docker-compose.tunnel.yml --env-file .env.lan stop cloudflared

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$Project = "capstone-lan"
$files   = @("-f", "docker-compose.lan.yml", "-f", "docker-compose.tunnel.yml", "--env-file", ".env.lan")

if (-not (Test-Path ".env.lan")) {
    Write-Host ".env.lan not found - run .\deploy-lan.ps1 first." -ForegroundColor Red
    exit 1
}

Write-Host "==> Starting backend + Cloudflare tunnel" -ForegroundColor Cyan
& docker compose -p $Project @files up -d
if ($LASTEXITCODE -ne 0) { Write-Host "docker compose failed." -ForegroundColor Red; exit 1 }

Write-Host "==> Waiting for the public URL" -ForegroundColor Cyan
$url = $null
for ($i = 0; $i -lt 30 -and -not $url; $i++) {
    Start-Sleep -Seconds 2
    $logs = (& docker compose -p $Project @files logs cloudflared 2>&1) -join "`n"
    $m = [regex]::Matches($logs, 'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($m.Count -gt 0) { $url = $m[$m.Count - 1].Value }
}
if (-not $url) {
    Write-Host "No URL yet. Check: docker compose -p $Project logs cloudflared" -ForegroundColor Yellow
    exit 1
}

Set-Content -Path "tunnel-url.txt" -Value $url -Encoding ascii

Write-Host ""
Write-Host "Public URL (HTTPS): $url" -ForegroundColor Green
Write-Host "  Swagger        : $url/swagger-ui.html"
Write-Host "  FE API base URL: $url"
Write-Host "Saved to tunnel-url.txt. The URL changes whenever the tunnel container restarts - run this script again to get the new one."
Write-Host "DNS for a new URL can take ~30s to work."
