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
        # Re-launch elevated through -EncodedCommand so paths with spaces or non-ASCII names survive.
        $bootstrap = "& '" + ($PSCommandPath -replace "'", "''") + "' -Elevated"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap))
        $process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded" -Wait -PassThru
        exit $process.ExitCode
    }

    # netsh is used instead of New-NetFirewallRule: the PowerShell firewall module can block for minutes
    # on some systems (slow MpsSvc, security software), while netsh returns immediately.
    $netsh = Join-Path $env:SystemRoot 'System32\netsh.exe'
    & $netsh advfirewall firewall delete rule name=NoteLink-TCP-39817 | Out-Null
    & $netsh advfirewall firewall delete rule name="NoteLink TCP 39817" | Out-Null
    $result = & $netsh advfirewall firewall add rule name=NoteLink-TCP-39817 dir=in action=allow `
        protocol=TCP localport=39817 profile=any 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ((($result | Out-String).Trim()) + ' (netsh exit ' + $LASTEXITCODE + ')')
    }
    Write-Host 'NoteLink TCP 39817 inbound firewall access is enabled.'
    exit 0
} catch {
    Write-Host ('Failed to configure the NoteLink firewall rule: ' + $_.Exception.Message)
    if (-not $Elevated) { Read-Host 'Press Enter to close' }
    exit 1
} finally {
    if ($Elevated) { Read-Host 'Press Enter to close' }
}
