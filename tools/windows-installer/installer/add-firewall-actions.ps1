param(
    [Parameter(Mandatory = $true)][string]$MsiDirectory,
    [Parameter(Mandatory = $true)][string]$ActionExecutable
)

$ErrorActionPreference = 'Stop'
$msi = Get-ChildItem -LiteralPath $MsiDirectory -Filter '*.msi' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $msi) { throw "No MSI was found in $MsiDirectory" }
if (-not (Test-Path -LiteralPath $ActionExecutable)) { throw "Firewall action was not built: $ActionExecutable" }

$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.GetType().InvokeMember(
    'OpenDatabase', 'InvokeMethod', $null, $installer, @($msi.FullName, 1)
)

function Insert-Record([string]$sql, [object[]]$values) {
    $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @($sql))
    $record = $installer.GetType().InvokeMember('CreateRecord', 'InvokeMethod', $null, $installer, @($values.Count))
    for ($index = 0; $index -lt $values.Count; $index++) {
        $record.GetType().InvokeMember(
            'StringData', 'SetProperty', $null, $record, @(($index + 1), ([string]$values[$index]))
        ) | Out-Null
    }
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, @($record)) | Out-Null
    $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null) | Out-Null
}

function Execute-Sql([string]$sql) {
    $view = $database.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $database, @($sql))
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
    $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null) | Out-Null
}

Execute-Sql 'DELETE FROM `InstallExecuteSequence` WHERE `Action`=''AddNoteLinkFirewall'' OR `Action`=''RemoveNoteLinkFirewall'''
Execute-Sql 'DELETE FROM `CustomAction` WHERE `Action`=''AddNoteLinkFirewall'' OR `Action`=''RemoveNoteLinkFirewall'''
Execute-Sql 'DELETE FROM `Binary` WHERE `Name`=''NoteLinkFirewallAction'''

$binaryView = $database.GetType().InvokeMember(
    'OpenView', 'InvokeMethod', $null, $database,
    @('INSERT INTO `Binary` (`Name`, `Data`) VALUES (?, ?)')
)
$binaryRecord = $installer.GetType().InvokeMember('CreateRecord', 'InvokeMethod', $null, $installer, @(2))
$binaryRecord.GetType().InvokeMember('StringData', 'SetProperty', $null, $binaryRecord, @(1, 'NoteLinkFirewallAction')) | Out-Null
$binaryRecord.GetType().InvokeMember('SetStream', 'InvokeMethod', $null, $binaryRecord, @(2, $ActionExecutable)) | Out-Null
$binaryView.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $binaryView, @($binaryRecord)) | Out-Null
$binaryView.GetType().InvokeMember('Close', 'InvokeMethod', $null, $binaryView, $null) | Out-Null

# 3074 = executable from Binary table + deferred execution + no impersonation.
Insert-Record 'INSERT INTO `CustomAction` (`Action`, `Type`, `Source`, `Target`) VALUES (?, ?, ?, ?)' @(
    'AddNoteLinkFirewall', 3074, 'NoteLinkFirewallAction', 'add'
)
Insert-Record 'INSERT INTO `CustomAction` (`Action`, `Type`, `Source`, `Target`) VALUES (?, ?, ?, ?)' @(
    'RemoveNoteLinkFirewall', 3074, 'NoteLinkFirewallAction', 'remove'
)
Insert-Record 'INSERT INTO `InstallExecuteSequence` (`Action`, `Condition`, `Sequence`) VALUES (?, ?, ?)' @(
    'AddNoteLinkFirewall', 'NOT Installed', 4050
)
Insert-Record 'INSERT INTO `InstallExecuteSequence` (`Action`, `Condition`, `Sequence`) VALUES (?, ?, ?)' @(
    'RemoveNoteLinkFirewall', 'REMOVE~="ALL"', 3450
)

$database.GetType().InvokeMember('Commit', 'InvokeMethod', $null, $database, $null) | Out-Null
Write-Output "Embedded NoteLink TCP 39817 install/uninstall firewall actions in $($msi.FullName)"
