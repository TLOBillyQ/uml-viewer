# #21 wait helper. Polls a file or a predicate until satisfied. usage:
#   pwsh -File acc21-wait.ps1 -Project <dir> -For display [-TimeoutS 300]
#   -For display       observer timeline shows :waiting consumed (mail-seen > 0 or not waiting)
#   -For jvm-exit      recorded viewer JVM process is gone
#   -For controller-phase -Phase backoff|running|failed|cancelled|normal
#   -For file -Path <file>
param(
    [Parameter(Mandatory)][string]$Project,
    [Parameter(Mandatory)][ValidateSet('display', 'jvm-exit', 'controller-phase', 'file', 'agent-queue-empty')][string]$For,
    [string]$Phase,
    [string]$Path,
    [int]$TimeoutS = 300
)
$ErrorActionPreference = 'Stop'
$deadline = (Get-Date).AddSeconds($TimeoutS)
$dir = Join-Path $Project '.uml-viewer'
function Test-Display {
    $timeline = Join-Path $dir 'observer-timeline.edn'
    if (-not (Test-Path -LiteralPath $timeline)) { return $false }
    $tail = Get-Content -LiteralPath $timeline -Raw
    # consumed display: an event with :waiting absent or false and a real path
    return ($tail -match ':waiting false') -or (($tail -notmatch ':waiting true') -and ($tail -match ':mail-seen [1-9]'))
}
function Test-JvmExit {
    $record = Join-Path $dir 'viewer-jvm.json'
    if (-not (Test-Path -LiteralPath $record)) { return $false }
    $jvm = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
    $p = Get-Process -Id $jvm.pid -ErrorAction SilentlyContinue
    return ($null -eq $p)
}
function Test-Phase {
    $journal = Join-Path $dir 'recovery.edn'
    if (-not (Test-Path -LiteralPath $journal)) { return $false }
    return ((Get-Content -LiteralPath $journal -Raw) -match (':phase :' + $Phase + '([\\s}])'))
}
function Test-AgentQueueEmpty {
    $box = Join-Path $dir 'to-agent.edn'
    if (-not (Test-Path -LiteralPath $box)) { return $false }
    return ((Get-Content -LiteralPath $box -Raw) -match ':queue\s*\[\s*\]')
}
while ($true) {
    $ok = switch ($For) {
        'display' { Test-Display }
        'jvm-exit' { Test-JvmExit }
        'controller-phase' { Test-Phase }
        'file' { Test-Path -LiteralPath $Path }
        'agent-queue-empty' { Test-AgentQueueEmpty }
    }
    if ($ok) { Write-Output "satisfied: $For $Phase"; exit 0 }
    if ((Get-Date) -gt $deadline) { Write-Error "timeout waiting for $For $Phase"; exit 1 }
    Start-Sleep -Milliseconds 250
}
