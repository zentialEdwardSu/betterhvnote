param([switch]$Elevated)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$ruleName = 'NoteLink-TCP-39817'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

try {
    if (-not (Test-Administrator)) {
        $bootstrap = "& '" + ($PSCommandPath -replace "'", "''") + "' -Elevated"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap))
        $process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded" -Wait -PassThru
        exit $process.ExitCode
    }

    $netsh = Join-Path $env:SystemRoot 'System32\netsh.exe'
    & $netsh advfirewall firewall delete rule name=NoteLink-TCP-39817 | Out-Null
    & $netsh advfirewall firewall delete rule name="NoteLink TCP 39817" | Out-Null
    Write-Host 'The NoteLink firewall rule was removed.'
    exit 0
} catch {
    Write-Host ('Failed to remove the NoteLink firewall rule: ' + $_.Exception.Message)
    if (-not $Elevated) { Read-Host 'Press Enter to close' }
    exit 1
} finally {
    if ($Elevated) { Read-Host 'Press Enter to close' }
}
