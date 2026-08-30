param([Parameter(Mandatory = $true)][string]$MsiPath)

$ErrorActionPreference = 'Stop'
$resolvedMsi = (Resolve-Path -LiteralPath $MsiPath).Path
$logDirectory = Join-Path (Split-Path $resolvedMsi -Parent) 'verification'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$installLog = Join-Path $logDirectory 'install.log'
$uninstallLog = Join-Path $logDirectory 'uninstall.log'

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($resolvedMsi, 0))
$view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @('SELECT `Value` FROM `Property` WHERE `Property`=''ProductCode'''))
$view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
$record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
$productCode = $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @(1))
$view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null) | Out-Null
if (-not $productCode) { throw 'MSI ProductCode is missing.' }

$installed = $false
try {
    $install = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', $resolvedMsi, '/qn', '/norestart', '/l*v', $installLog) -Wait -PassThru -WindowStyle Hidden
    if ($install.ExitCode -notin @(0, 3010)) { throw "MSI install failed with exit code $($install.ExitCode)." }
    $installed = $true

    $rule = Get-NetFirewallRule -DisplayName 'NoteLink TCP 39817' -ErrorAction Stop
    $port = $rule | Get-NetFirewallPortFilter
    if ($rule.Direction -ne 'Inbound' -or $rule.Action -ne 'Allow' -or $port.Protocol -ne 'TCP' -or $port.LocalPort -ne '39817') {
        throw 'Installed firewall rule does not allow inbound TCP 39817.'
    }
    Write-Output "MSI install verified: $productCode"
} finally {
    if ($installed) {
        $uninstall = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/x', $productCode, '/qn', '/norestart', '/l*v', $uninstallLog) -Wait -PassThru -WindowStyle Hidden
        if ($uninstall.ExitCode -notin @(0, 3010)) { throw "MSI uninstall failed with exit code $($uninstall.ExitCode)." }
        if (Get-NetFirewallRule -DisplayName 'NoteLink TCP 39817' -ErrorAction SilentlyContinue) {
            throw 'Firewall rule remains after MSI uninstall.'
        }
        Write-Output 'MSI uninstall and firewall cleanup verified.'
    }
}
