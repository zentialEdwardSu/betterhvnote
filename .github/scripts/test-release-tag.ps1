$ErrorActionPreference = 'Stop'

function Resolve-ForTest([string]$Tag) {
    & "$PSScriptRoot/resolve-release-tag.ps1" -Tag $Tag | ConvertFrom-Json
}

function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -ne $Actual) { throw "$Message Expected '$Expected', got '$Actual'." }
}

function Assert-Rejected([string]$Tag) {
    try {
        Resolve-ForTest $Tag | Out-Null
        throw "Expected '$Tag' to be rejected."
    } catch {
        if ($_.Exception.Message -like "Expected '$Tag' to be rejected.*") { throw }
    }
}

$note = Resolve-ForTest 'note-v1.2.3'
Assert-Equal 'note' $note.product 'Wrong product.'
Assert-Equal '1.2.3' $note.version 'Wrong version.'
Assert-Equal 1002003 $note.versionCode 'Wrong version code.'

$noteLink = Resolve-ForTest 'notelink-v0.2.3'
Assert-Equal 'notelink' $noteLink.product 'Wrong product.'
Assert-Equal 2003 $noteLink.versionCode 'Wrong NoteLink version code.'

@(
    'v1.2.3',
    'desktop-v1.2.3',
    'note-v1.2',
    'note-v01.2.3',
    'note-v0.0.0',
    'note-v1.1000.0',
    'note-v1.2.1000',
    'note-v1.2.3-rc.1',
    'note-v2101.0.0'
) | ForEach-Object { Assert-Rejected $_ }

Write-Output 'Release tag parser tests passed.'
