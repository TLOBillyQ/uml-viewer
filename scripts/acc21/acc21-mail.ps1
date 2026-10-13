# #21 mailbox helper. Writes one command onto a project mail queue following
# the documented {:next-id n :queue [cmd …]} protocol (id from :next-id), or
# reads a queue. usage:
#   pwsh -File acc21-mail.ps1 -Project <dir> -Box to-viewer -Op quit-for-restart
#   pwsh -File acc21-mail.ps1 -Project <dir> -Box to-viewer -Op display -Extra @{path='...'}
#   pwsh -File acc21-mail.ps1 -Project <dir> -Box to-agent -Read
param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][ValidateSet('to-viewer', 'to-agent')][string]$Box,
    [string]$Op,
    [hashtable]$Extra = @{},
    [switch]$Read
)
$ErrorActionPreference = 'Stop'
$file = Join-Path $Project ".uml-viewer\$Box.edn"
function Read-Box {
    if (Test-Path -LiteralPath $file) {
        $raw = Get-Content -LiteralPath $file -Raw
        return $raw
    }
    return '{:next-id 1 :queue []}'
}
if ($Read) { Write-Output (Read-Box); exit 0 }
if (-not $Op) { throw 'Op is required unless -Read' }
# Parse current envelope minimally: next-id and whether queue is empty.
$current = Read-Box
$nextId = 1
$m = [regex]::Match($current, ':next-id\s+(\d+)')
if ($m.Success) { $nextId = [int]$m.Groups[1].Value }
$queueMatch = [regex]::Match($current, '(?s):queue\s*\[(.*)\]\s*}')
$queueBody = ''
if ($queueMatch.Success) { $queueBody = $queueMatch.Groups[1].Value.Trim() }
function To-Edn($v) {
    if ($null -eq $v) { return 'nil' }
    if ($v -is [bool]) { return $v.ToString().ToLower() }
    if ($v -is [int] -or $v -is [long] -or $v -is [double]) { return $v.ToString() }
    $s = [string]$v
    if ($s.StartsWith(':')) { return $s }
    if ($s -match '^[A-Za-z0-9_.?*-]+$') { return $s }
    return '"' + ($s -replace '\\', '\\' -replace '"', '\"') + '"'
}
$cmd = '{:id ' + $nextId + ' :op :' + $Op
foreach ($k in $Extra.Keys) { $cmd += ' :' + $k + ' ' + (To-Edn $Extra[$k]) }
$cmd += '}'
$newQueue = if ($queueBody) { $queueBody + ' ' + $cmd } else { $cmd }
$envelope = '{:next-id ' + ($nextId + 1) + ' :queue [' + $newQueue + ']}'
$tmp = $file + '.tmp'
Set-Content -LiteralPath $tmp -Encoding utf8 -Value $envelope -NoNewline
Move-Item -LiteralPath $tmp -Destination $file -Force
Write-Output $cmd
