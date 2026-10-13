param([string]$Project='C:\Users\Lzx_8\AppData\Local\Temp\uml-acc21\fixtures\proj A',[int]$TimeoutSeconds=240)
$deadline=(Get-Date).AddSeconds($TimeoutSeconds)
$file=Join-Path $Project '.uml-viewer/observer-timeline.edn'
while ((Get-Date) -lt $deadline) {
 if(Test-Path $file){$raw=Get-Content $file -Raw; if($raw -match ':mail-seen [1-9][0-9]*' -and $raw -notmatch ':waiting true(?:(?!:t).)*$') {break}}
 Start-Sleep -Milliseconds 500
}
& "$PSScriptRoot/acc21-cmd.ps1" -Project $Project -Op snapshot
