param([Parameter(Mandatory)][string]$Project,[Parameter(Mandatory)][string]$Evidence,[int]$ObserveSeconds=18)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $Evidence | Out-Null
$stateFile=Join-Path $Project '.uml-viewer/recovery.edn'
$state=Get-Content $stateFile -Raw
$m=[regex]::Match($state, ':child \{:pid (\d+), :start "([^"]+)"\}')
if (-not $m.Success) { throw 'No recorded child identity' }
$childId=[int]$m.Groups[1].Value
$expected=[DateTimeOffset]::Parse($m.Groups[2].Value)
$process=Get-Process -Id $childId -ErrorAction Stop
if ($process.ProcessName -ne 'claude') { throw 'Not Claude' }
$observed=$process.StartTime.ToUniversalTime()
if ([math]::Abs(($observed-$expected.UtcDateTime).TotalMilliseconds) -gt 1) { throw 'PID/start mismatch' }
@{pid=$childId;expected=$expected.ToString('o');observed=$observed.ToString('o');t=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content (Join-Path $Evidence 'kill-validation.json')
$process=Get-Process -Id $childId -ErrorAction Stop
if ($process.StartTime.ToUniversalTime() -ne $observed) { throw 'PID changed immediately before kill' }
$t0=Get-Date
Stop-Process -Id $childId -Force -Confirm:$false
$t0.ToString('o') | Set-Content (Join-Path $Evidence 'kill-time.txt')
$last='';$events=@()
while (((Get-Date)-$t0).TotalSeconds -lt $ObserveSeconds) {
 try { $text=Get-Content $stateFile -Raw -ErrorAction Stop } catch { Start-Sleep -Milliseconds 40;continue }
 if ($text -ne $last) { $last=$text;$events+=@{ms=[int]((Get-Date)-$t0).TotalMilliseconds;state=$text};$events | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $Evidence 'timeline.json') }
 Start-Sleep -Milliseconds 80
}
$events | ForEach-Object { '{0}ms {1}' -f $_.ms,$_.state }
