# #21 native acceptance shim. Put this directory first on the process PATH so
# the public uml wrapper resolves `clojure` here. Delegates every invocation to
# the official clojure.exe unchanged, except the viewer main, which gets the
# read-only observer injected via -i (recording only; never replaces start!,
# recovery, restart or view state). Every invocation is logged with argv, cwd
# and exit code as evidence. Requires env:
#   UML_ACC21_OBSERVER  absolute path of observer.clj
#   UML_ACC21_SHIM_LOG  directory for invocation logs
$real = 'C:\Users\Lzx_8\AppData\Local\Apps\clojure\clojure.exe'
$argv = @($args)
$injected = $false
if ($env:UML_ACC21_OBSERVER -and ($argv -contains 'uml-viewer.main.uml-viewer')) {
    $out = @()
    for ($i = 0; $i -lt $argv.Count; $i++) {
        if (-not $injected -and $argv[$i] -eq '-m' -and $i + 1 -lt $argv.Count -and
            $argv[$i + 1] -eq 'uml-viewer.main.uml-viewer') {
            $out += '-i'
            $out += $env:UML_ACC21_OBSERVER
            $injected = $true
        }
        $out += $argv[$i]
    }
    $argv = $out
}
& $real @argv
$code = $LASTEXITCODE
if ($env:UML_ACC21_SHIM_LOG) {
    $entry = @{
        t = (Get-Date).ToString('o')
        pid = $PID
        cwd = (Get-Location).Path
        injected = $injected
        exit = $code
        argv = $argv
    }
    $name = 'invoke-' + (Get-Date).ToString('HHmmssfff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.json'
    try {
        ($entry | ConvertTo-Json -Compress -Depth 6) |
            Set-Content -LiteralPath (Join-Path $env:UML_ACC21_SHIM_LOG $name) -Encoding utf8
    } catch {}
}
exit $code
