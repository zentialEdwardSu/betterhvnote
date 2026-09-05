param([Parameter(Mandatory = $true)][string]$Tag)

$ErrorActionPreference = 'Stop'

function Resolve-ReleaseTag([string]$Value) {
    $match = [regex]::Match(
        $Value,
        '^(?<product>note|notelink)-v(?<major>0|[1-9][0-9]*)\.(?<minor>0|[1-9][0-9]*)\.(?<patch>0|[1-9][0-9]*)$'
    )
    if (-not $match.Success) {
        throw "Invalid release tag '$Value'. Expected note-vX.Y.Z or notelink-vX.Y.Z."
    }

    $major = [long]::Parse($match.Groups['major'].Value)
    $minor = [long]::Parse($match.Groups['minor'].Value)
    $patch = [long]::Parse($match.Groups['patch'].Value)
    if ($minor -gt 999 -or $patch -gt 999) {
        throw 'Minor and patch versions must be between 0 and 999.'
    }

    $versionCodeValue = [decimal]$major * 1000000 + [decimal]$minor * 1000 + [decimal]$patch
    if ($versionCodeValue -lt 1) {
        throw 'Android versionCode must be at least 1; version 0.0.0 cannot be released.'
    }
    if ($versionCodeValue -gt 2100000000) {
        $versionCode = $versionCodeValue.ToString([Globalization.CultureInfo]::InvariantCulture)
        throw "Android versionCode $versionCode exceeds 2100000000."
    }
    $versionCode = [long]$versionCodeValue

    [pscustomobject]@{
        tag = $Value
        product = $match.Groups['product'].Value
        version = "$major.$minor.$patch"
        versionCode = $versionCode
    }
}

Resolve-ReleaseTag $Tag | ConvertTo-Json -Compress
