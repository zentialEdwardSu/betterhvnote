param([switch]$Elevated)

$ErrorActionPreference = 'Stop'
$ruleName = 'NoteLink TCP 39817'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isAdministrator = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdministrator) {
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"{0}"' -f $PSCommandPath),
        '-Elevated'
    )
    $process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $arguments -Wait -PassThru
    exit $process.ExitCode
}

Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule `
    -DisplayName $ruleName `
    -Description 'Allows encrypted NoteLink transfers from paired BetterHvNote devices.' `
    -Enabled True `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort 39817 `
    -Profile Any | Out-Null

Write-Host 'NoteLink TCP 39817 inbound firewall access is enabled.' -ForegroundColor Green
if ($Elevated) { Read-Host 'Press Enter to close' }
