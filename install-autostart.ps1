# Register a Windows scheduled task that runs start-all.ps1 each time you sign in.
# Run ONCE in PowerShell as Administrator:
#   powershell -ExecutionPolicy Bypass -File .\install-autostart.ps1
# Remove: Unregister-ScheduledTask -TaskName "Capstone Autostart" -Confirm:$false

$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Please run this in PowerShell opened with 'Run as Administrator'." -ForegroundColor Red
    exit 1
}

$script = Join-Path $PSScriptRoot "start-all.ps1"
if (-not (Test-Path $script)) { Write-Host "start-all.ps1 not found next to this script." -ForegroundColor Red; exit 1 }
$action  = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`"" `
    -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
$trigger.Delay = "PT1M"   # give Docker Desktop a head start
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Highest

Register-ScheduledTask -TaskName "Capstone Autostart" -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal -Force | Out-Null

if (-not (Get-ScheduledTask -TaskName "Capstone Autostart" -ErrorAction SilentlyContinue)) {
    Write-Host "Task was not created." -ForegroundColor Red
    exit 1
}

Write-Host "Scheduled task 'Capstone Autostart' registered. It runs start-all.ps1 1 minute after you sign in." -ForegroundColor Green
Write-Host "Log: $(Join-Path $PSScriptRoot 'start-all.log')   Public URL: <FE folder>\fe-url.txt"
Write-Host "Test now: Start-ScheduledTask -TaskName 'Capstone Autostart'"
