# Start/recover the current backend without relying on Docker Desktop's cached Compose project.
# Run: .\start-backend.ps1   (add -Build after changing Java source)
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)][int]$Port = 8081,
    [switch]$Build
)

$ErrorActionPreference = "Stop"
$composeFile = Join-Path $PSScriptRoot "docker-compose.yml"
$envFile = Join-Path $PSScriptRoot ".env.lan"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw ".env.lan is missing. Restore the project's local configuration before starting."
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not ready. Start Docker Desktop and retry." }

$previousPort = $env:APP_PORT
try {
    $env:APP_PORT = [string]$Port
    # 'start' cannot create missing containers; 'up' recovers them and reuses existing volumes.
    $composeArgs = @("compose", "-p", "capstone-project-progress-tracking-system-be", "-f", $composeFile,
        "--env-file", $envFile, "up", "-d", "--wait", "--wait-timeout", "120")
    if ($Build) { $composeArgs += "--build" }
    $composeArgs += @("postgres", "app")
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) { throw "Backend startup failed. Check the Docker Compose output above." }
    Write-Host "Backend ready (Compose project: capstone-project-progress-tracking-system-be)." -ForegroundColor Green
    Write-Host "Swagger: http://localhost:$Port/swagger-ui/index.html"
} finally {
    $env:APP_PORT = $previousPort
}
