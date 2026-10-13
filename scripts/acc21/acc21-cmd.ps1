# #21 observer command helper. Writes .uml-viewer/observer-cmd.edn for the
# in-JVM observer and waits for its result file. usage:
#   pwsh -File acc21-cmd.ps1 -Project <dir> -Op snapshot
#   pwsh -File acc21-cmd.ps1 -Project <dir> -Op click -Args @{x=1300;y=50}
param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][string]$Op,
    [hashtable]$Args = @{},
    [int]$TimeoutS = 30
)
$ErrorActionPreference = 'Stop'
$id = [guid]::NewGuid().ToString('N').Substring(0, 12)
$cmd = @{}
foreach ($k in $Args.Keys) { $cmd[$k] = $Args[$k] }
$cmd['op'] = $Op
# The helper owns `id`; it names both the command and its result file.
$cmd['id'] = $id
$dir = Join-Path $Project '.uml-viewer'
$cmdFile = Join-Path $dir 'observer-cmd.edn'
$resultFile = Join-Path $dir "observer-result-$id.edn"
# EDN-encode the command map (booleans/numbers/strings/keywords only)
function To-Edn($v) {
    if ($null -eq $v) { return 'nil' }
    if ($v -is [bool]) { return $v.ToString().ToLower() }
    if ($v -is [int] -or $v -is [long] -or $v -is [double]) { return $v.ToString() }
    $s = [string]$v
    if ($s -match '^[A-Za-z0-9_.?*-]+$') { return $s }
    return '"' + ($s -replace '\\', '\\' -replace '"', '\"') + '"'
}
$pairs = ($cmd.GetEnumerator() | ForEach-Object {
    # The id must stay a quoted string: bare tokens starting with a digit are
    # not valid EDN symbols and silently fail to parse.
    if ($_.Key -eq 'id') { ':id "' + $_.Value + '"' } else { ':' + $_.Key + ' ' + (To-Edn $_.Value) }
}) -join ' '
Set-Content -LiteralPath $cmdFile -Encoding utf8 -Value ('{' + $pairs + '}')
$deadline = (Get-Date).AddSeconds($TimeoutS)
while (-not (Test-Path -LiteralPath $resultFile)) {
    if ((Get-Date) -gt $deadline) { Write-Error "observer result timeout for $Op"; exit 1 }
    Start-Sleep -Milliseconds 200
}
Get-Content -LiteralPath $resultFile -Raw
