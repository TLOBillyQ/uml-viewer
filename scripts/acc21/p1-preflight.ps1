# #21 P1 preflight refusal driver. Runs the public wrapper with a
# process-local PATH variant in this child process only, and records evidence.
# usage: pwsh -File p1-preflight.ps1 -Mode missing|incompatible -Project <dir> -Evidence <dir>
param(
    [Parameter(Mandatory)][ValidateSet('missing', 'incompatible')][string]$Mode,
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$Evidence
)
$ErrorActionPreference = 'Stop'
$herdrDir = 'C:\Users\Lzx_8\.herdr\packages\standalone\releases\0.9.3-x86_64-pc-windows-msvc'
$sessionsBefore = (& "$herdrDir\herdr.exe" session list 2>&1 | Out-String)
$companionBefore = Test-Path -LiteralPath (Join-Path $Project '.uml-viewer\companion.edn')
if ($Mode -eq 'missing') {
    $env:PATH = (($env:PATH -split ';' | Where-Object { $_ -and ($_ -ne $herdrDir) }) -join ';')
} else {
    $fake = Join-Path $Evidence 'fake-herdr'
    New-Item -ItemType Directory -Force $fake | Out-Null
    Copy-Item 'C:\Windows\System32\findstr.exe' (Join-Path $fake 'herdr.exe') -Force
    $env:PATH = $fake + ';' + (($env:PATH -split ';' | Where-Object { $_ -and ($_ -ne $herdrDir) }) -join ';')
}
Set-Location -LiteralPath $Project
$output = & pwsh -NoProfile -File .\uml.ps1 --backend herdr 2>&1 | Out-String
$code = $LASTEXITCODE
$companionAfter = Test-Path -LiteralPath (Join-Path $Project '.uml-viewer\companion.edn')
$sessionsAfter = (& "$herdrDir\herdr.exe" session list 2>&1 | Out-String)
$report = @"
# P1 preflight refusal: $Mode Herdr
- mode: $Mode
- exit: $code
- output: $($output.Trim())
- companion.edn before: $companionBefore after: $companionAfter
- herdr sessions unchanged: $($sessionsBefore -eq $sessionsAfter)
- sessions before:
$sessionsBefore
- sessions after:
$sessionsAfter
"@
Set-Content -LiteralPath (Join-Path $Evidence "p1-$Mode-herdr.md") -Encoding utf8 -Value $report
Write-Output $report
