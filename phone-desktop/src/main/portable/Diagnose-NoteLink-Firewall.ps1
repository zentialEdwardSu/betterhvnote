param([switch]$Elevated)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$report = Join-Path $PSScriptRoot 'NoteLink-firewall-diagnostics.txt'
$netsh = Join-Path $env:SystemRoot 'System32\netsh.exe'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Administrator)) {
    $bootstrap = "& '" + ($PSCommandPath -replace "'", "''") + "' -Elevated"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($bootstrap))
    $process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden `
        -ArgumentList "-NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded" -Wait -PassThru
    exit $process.ExitCode
}

function Add-Line([string]$text) {
    Add-Content -LiteralPath $report -Value $text
}

function Invoke-Netsh([string[]]$arguments, [int]$timeoutMillis = 15000) {
    $stdout = Join-Path $env:TEMP ('notelink-diag-' + [guid]::NewGuid().ToString('N') + '.out')
    $stderr = Join-Path $env:TEMP ('notelink-diag-' + [guid]::NewGuid().ToString('N') + '.err')
    $process = Start-Process -FilePath $netsh -ArgumentList $arguments -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if (-not $process.WaitForExit($timeoutMillis)) {
        try { $process.Kill() } catch { }
        Add-Line ('TIMEOUT after ' + $timeoutMillis + 'ms')
        return
    }
    Add-Line ('exit=' + $process.ExitCode)
    if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout | ForEach-Object { Add-Line $_ } }
    if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr | ForEach-Object { Add-Line $_ } }
    Remove-Item -LiteralPath $stdout, $stderr -ErrorAction SilentlyContinue
}

function Invoke-TimedJob([string]$title, [scriptblock]$block, [int]$timeoutSeconds = 10) {
    Add-Line ''
    Add-Line ('--- ' + $title)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $job = Start-Job -ScriptBlock $block
    if (Wait-Job $job -Timeout $timeoutSeconds) {
        Receive-Job $job 2>&1 | Out-String | ForEach-Object {
            ($_ -split "`r?`n") | Where-Object { $_ -ne '' } | ForEach-Object { Add-Line $_ }
        }
        Add-Line ('elapsed=' + $watch.ElapsedMilliseconds + 'ms')
    } else {
        Stop-Job $job -ErrorAction SilentlyContinue
        Add-Line ('TIMEOUT after ' + $timeoutSeconds + 's')
    }
    Remove-Job $job -Force -ErrorAction SilentlyContinue
}

'=== NoteLink firewall diagnostics ===' | Set-Content -LiteralPath $report -Encoding UTF8
Add-Line ('Time: ' + (Get-Date -Format o))
Add-Line ('User: ' + $env:USERDOMAIN + '\' + $env:USERNAME)
Add-Line ('Admin: ' + (Test-Administrator))
Add-Line ('Script: ' + $PSCommandPath)
try {
    $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    Add-Line ('OS: ' + $os.Caption + ' ' + $os.Version + ' ' + $os.OSArchitecture)
} catch {
    Add-Line ('OS: unavailable (' + $_.Exception.Message + ')')
}

Add-Line ''
Add-Line '--- Firewall policy registry (AllowInboundRules=0 means "Block all incoming connections")'
foreach ($profile in 'DomainProfile', 'StandardProfile', 'PublicProfile') {
    foreach ($base in @(
        'HKLM:\SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters\FirewallPolicy',
        'HKLM:\SOFTWARE\Policies\Microsoft\WindowsFirewall'
    )) {
        $path = Join-Path $base $profile
        $values = Get-ItemProperty -Path $path -ErrorAction SilentlyContinue
        if ($null -ne $values) {
            Add-Line ($profile + ' @ ' + $base)
            Add-Line ('  EnableFirewall=' + $values.EnableFirewall +
                ' AllowInboundRules=' + $values.AllowInboundRules +
                ' DefaultInboundAction=' + $values.DefaultInboundAction)
        }
    }
}

Add-Line ''
Add-Line '--- Services'
Get-Service MpsSvc, BFE -ErrorAction SilentlyContinue | Format-Table -AutoSize |
    Out-String | ForEach-Object { ($_ -split "`r?`n") | Where-Object { $_ -ne '' } | ForEach-Object { Add-Line $_ } }

Invoke-TimedJob 'Get-NetFirewallRule by Name/DisplayName' {
    $rules = @()
    $rules += @(Get-NetFirewallRule -Name 'NoteLink-TCP-39817' -ErrorAction SilentlyContinue)
    $rules += @(Get-NetFirewallRule -DisplayName 'NoteLink-TCP-39817' -ErrorAction SilentlyContinue)
    $rules += @(Get-NetFirewallRule -DisplayName 'NoteLink TCP 39817' -ErrorAction SilentlyContinue)
    'count=' + $rules.Count
    $rules | Select-Object Name, DisplayName, Enabled, Direction, Action, Profile |
        Format-List | Out-String
} 10

Add-Line ''
Add-Line '--- netsh show rule name=NoteLink*'
Invoke-Netsh @('advfirewall', 'firewall', 'show', 'rule', 'name=NoteLink*', 'verbose')

Add-Line ''
Add-Line '--- netsh probe add/delete (port 39818)'
Invoke-Netsh @(
    'advfirewall', 'firewall', 'add', 'rule',
    'name=NoteLink-diagnostics-probe', 'dir=in', 'action=allow',
    'protocol=TCP', 'localport=39818', 'profile=any'
)
Invoke-Netsh @('advfirewall', 'firewall', 'delete', 'rule', 'name=NoteLink-diagnostics-probe')

Add-Line ''
Add-Line '--- Listener 39817'
(netstat -ano | Select-String '39817') | ForEach-Object { Add-Line $_.Line }

Add-Line ''
Add-Line '--- Third-party firewall products'
try {
    Get-CimInstance -Namespace root/SecurityCenter2 -ClassName FirewallProduct -ErrorAction Stop |
        Select-Object displayName, productState, pathToSignedProductExe |
        Format-List | Out-String | ForEach-Object {
            ($_ -split "`r?`n") | Where-Object { $_ -ne '' } | ForEach-Object { Add-Line $_ }
        }
} catch {
    Add-Line ('unavailable: ' + $_.Exception.Message)
}

Add-Line ''
Add-Line '=== end ==='

Write-Host ''
Write-Host 'Diagnostics written to:'
Write-Host $report -ForegroundColor Green
Write-Host ''
Read-Host 'Press Enter to close'
