param(
    [Parameter(Mandatory = $true)][string]$ProjectDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) {
    throw 'Visual Studio Installer (vswhere.exe) was not found.'
}
$visualStudio = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $visualStudio) {
    throw 'Visual Studio with the x64 C++ toolchain is required.'
}
$developerShell = Join-Path $visualStudio 'Common7\Tools\VsDevCmd.bat'
$ninja = Join-Path $visualStudio 'Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe'
if (-not (Test-Path -LiteralPath $ninja)) {
    throw 'The Visual Studio Ninja generator was not found.'
}

$source = Join-Path $ProjectDirectory 'src\main\cpp'
$build = Join-Path $ProjectDirectory 'build\native-x64'
$command = '"{0}" -arch=x64 -host_arch=x64 && cmake -S "{1}" -B "{2}" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM="{3}" && cmake --build "{2}" --config Release' -f $developerShell, $source, $build, $ninja
& cmd.exe /d /s /c $command
if ($LASTEXITCODE -ne 0) { throw "Native build failed with exit code $LASTEXITCODE." }

$destination = Join-Path $OutputDirectory 'win32-x86-64'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Copy-Item -LiteralPath (Join-Path $build 'notelink_windows.dll') -Destination (Join-Path $destination 'notelink_windows.dll') -Force
