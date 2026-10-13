param([string]$Fixture='C:\Users\Lzx_8\AppData\Local\Temp\uml-acc21\fixtures\proj A',[string]$Evidence='C:\Users\Lzx_8\AppData\Local\Temp\uml-acc21\evidence\negative-identities')
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force $Evidence | Out-Null
$testRoot=Join-Path $env:TEMP ('uml-acc21-negative-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $testRoot | Out-Null
New-Item -ItemType Directory (Join-Path $testRoot '.uml-viewer') | Out-Null
New-Item -ItemType Junction -Path (Join-Path $testRoot '.uml-viewer/uml-viewer') -Target (Join-Path $Fixture '.uml-viewer/uml-viewer') | Out-Null
$entry=Join-Path $Fixture '.uml-viewer/uml-viewer/scripts/uml-command.ps1'
$results=@()
foreach ($case in @('missing-launcher','missing-jvm','incomplete-owner','missing-session')) {
 $dir=Join-Path $testRoot '.uml-viewer'
 foreach ($name in @('viewer-process.json','viewer-jvm.json','companion.edn')) { $file=Join-Path $dir $name;if(Test-Path $file){Remove-Item $file -Confirm:$false} }
 $owner=Get-Content (Join-Path $Fixture '.uml-viewer/companion.edn') -Raw
 $encodedRoot=$testRoot.Replace('\','\\')
 $owner=$owner -replace ':cwd "[^"]+"',(':cwd "'+$encodedRoot+'"')
 if($case -in @('missing-launcher','missing-jvm')){$owner | Set-Content (Join-Path $dir 'companion.edn')}
 $launcher='{"pid":2147483647,"started":"1"}'
$jvm='{"pid":2147483646,"started":"2020-01-01T00:00:00.0000000Z"}'
if($case -in @('missing-jvm','missing-session')){$launcher | Set-Content (Join-Path $dir 'viewer-process.json')}
if($case -eq 'missing-session'){$jvm | Set-Content (Join-Path $dir 'viewer-jvm.json')}
 if($case -eq 'incomplete-owner'){'{:backend :herdr :session "uml-viewer-acc21-absent"}' | Set-Content (Join-Path $dir 'companion.edn')}
 if($case -eq 'missing-session'){
  $raw=$owner
  $raw=$raw -replace ':session "[^"]+"',':session "uml-viewer-acc21-absent"'
  $raw | Set-Content (Join-Path $dir 'companion.edn')
 }
 $before=@(Get-ChildItem $dir -Filter 'launch-*.json').Count
 $output=& pwsh -NoProfile -File $entry $testRoot --backend herdr --restart 2>&1 | Out-String
 $code=$LASTEXITCODE
 $after=@(Get-ChildItem $dir -Filter 'launch-*.json').Count
 $results+=@{case=$case;exit=$code;output=$output;createdLaunches=$after-$before;t=(Get-Date).ToString('o')}
 $results | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $Evidence 'results.json')
 if($case -eq 'missing-session') {
  $logFile=Join-Path $testRoot 'uml-viewer-log.txt'
  $deadline=(Get-Date).AddSeconds(45)
  do { $log=if(Test-Path $logFile){Get-Content $logFile -Raw}else{''};if($log -match 'Herdr endpoint is not'){break};Start-Sleep -Milliseconds 200 } while((Get-Date)-lt $deadline)
  $log | Set-Content (Join-Path $Evidence 'missing-session-jvm-error.txt')
  if($log -notmatch 'Herdr endpoint is not'){throw 'Missing session did not fail explicitly in JVM'}
 } elseif($code -eq 0 -or $after -ne $before){throw "Negative refusal failed: $case"}
}
$results | ConvertTo-Json -Depth 5
