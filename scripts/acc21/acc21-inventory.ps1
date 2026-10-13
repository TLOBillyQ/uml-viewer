# #21 process/session inventory snapshot for evidence. usage:
#   pwsh -File acc21-inventory.ps1 -Label <name> [-Evidence <dir>]
param(
    [Parameter(Mandatory)][string]$Label,
    [string]$Evidence = 'C:\Users\Lzx_8\AppData\Local\Temp\uml-acc21\evidence'
)
$ErrorActionPreference = 'Stop'
$procs = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match 'herdr|java|claude|pwsh|WindowsTerminal|OpenConsole|node' } |
    ForEach-Object {
        [ordered]@{
            pid = $_.ProcessId
            ppid = $_.ParentProcessId
            name = $_.Name
            started = $_.CreationDate.ToString('o')
            cmd = $_.CommandLine
        }
    }
$sessions = (& herdr session list 2>&1 | Out-String)
$record = [ordered]@{
    label = $Label
    t = (Get-Date).ToString('o')
    sessions = $sessions
    processes = @($procs)
}
$file = Join-Path $Evidence ("inventory-" + $Label + '.json')
($record | ConvertTo-Json -Depth 5) | Set-Content -LiteralPath $file -Encoding utf8
Write-Output "inventory-$Label.json written ($($procs.Count) processes)"
