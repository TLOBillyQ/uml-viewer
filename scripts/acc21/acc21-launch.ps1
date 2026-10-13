# #21 native acceptance launcher. Invokes the PUBLIC installed wrapper
# (uml.ps1) with the acc21 shim first on PATH so the viewer JVM carries the
# read-only observer. All lifecycle actions go through the wrapper unchanged.
# usage: pwsh -File acc21-launch.ps1 -Project <dir> [-Restart] [-ExtraArg <arg>]
param(
    [Parameter(Mandatory)][string]$Project,
    [switch]$Restart,
    [string]$ShimDir = 'C:\Users\Lzx_8\Desktop\dev\github\uml-viewer\.claude\worktrees\agent-ticket21\scripts\acc21',
    [string]$Evidence = 'C:\Users\Lzx_8\AppData\Local\Temp\uml-acc21\evidence'
)
$ErrorActionPreference = 'Stop'
$env:UML_ACC21_OBSERVER = Join-Path $ShimDir 'observer.clj'
$env:UML_ACC21_SHIM_LOG = Join-Path $Evidence 'shim-log'
New-Item -ItemType Directory -Force $env:UML_ACC21_SHIM_LOG | Out-Null
$env:PATH = $ShimDir + ';' + $env:PATH
Set-Location -LiteralPath $Project
$argv = @('--backend', 'herdr')
if ($Restart) { $argv += '--restart' }
$t0 = Get-Date
$output = & pwsh -NoProfile -File .\uml.ps1 @argv 2>&1 | Out-String
$code = $LASTEXITCODE
$record = @{
    t = $t0.ToString('o')
    project = $Project
    restart = [bool]$Restart
    exit = $code
    output = $output.Trim()
}
$name = 'launch-' + $(if ($Restart) { 'restart' } else { 'fresh' }) + '-' + $t0.ToString('HHmmss') + '.json'
($record | ConvertTo-Json -Compress -Depth 4) |
    Set-Content -LiteralPath (Join-Path $Evidence $name) -Encoding utf8
Write-Output ($record | ConvertTo-Json -Depth 4)
exit $code
