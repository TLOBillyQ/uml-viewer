# Shared installed command runtime. Root is supplied by the thin project entry.
if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Error 'PowerShell 7 is required: https://aka.ms/powershell-install'; exit 1
}
$ErrorActionPreference = 'Stop'
$root = $args[0]
$commandArgs = @($args | Select-Object -Skip 1)
function Clojure-Command {
    $command = Get-Command clojure -ErrorAction SilentlyContinue
    if ($IsWindows -and (-not $command -or $command.CommandType -ne 'Application')) {
        # Importing the official module makes its clojure alias shadow any
        # PATH .ps1 shim (alias precedence), so split-form detection below
        # sees the real Invoke-Clojure entry, not the forwarding script.
        Import-Module ClojureTools -ErrorAction SilentlyContinue
        $command = Get-Command clojure -ErrorAction SilentlyContinue
    }
    if (-not $command) { throw 'Clojure CLI is required. Install: https://clojure.org/guides/install_clojure' }
    if ($IsWindows -and $command.CommandType -eq 'Application' -and
        [IO.Path]::GetExtension($command.Source).ToLowerInvariant() -in @('.cmd', '.bat')) {
        throw 'Clojure .cmd/.bat wrappers cannot safely launch the background viewer. Put clojure.exe from the Windows Clojure CLI installer on PATH (https://clojure.org/guides/install_clojure), or use the ClojureTools exported clojure command / a .ps1 entry. Remove the batch wrapper shadowing that entry.'
    }
    return $command
}
try {
    Set-Location -LiteralPath $root
    $toolRoot = (Resolve-Path -LiteralPath '.uml-viewer/uml-viewer').Path.Replace('\', '/')
    $escapedRoot = $toolRoot.Replace('"', '\"')
    $deps = '{:deps {uml-viewer/uml-viewer {:local/root "' + $escapedRoot + '"} quil/quil {:mvn/version "4.3.1563"}}}'
    if ($commandArgs.Count -gt 0 -and $commandArgs[0] -in @('-h', '--help')) {
        Write-Output 'usage: uml [--restart | ir | crap | mutate] [args]'; exit 0
    }
    if ($commandArgs.Count -gt 0 -and $commandArgs[0] -eq 'ir') {
        $name = Split-Path $root -Leaf
        $policy = if (Test-Path -LiteralPath "examples/$name.policy.edn") {
            "examples/$name.policy.edn"
        } else {
            $first = Get-ChildItem 'examples/*.policy.edn' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -First 1
            if ($first) { $first.FullName } elseif (Test-Path 'policy.edn') { 'policy.edn' }
        }
        if (-not $policy) { throw "no policy. Write examples/$name.policy.edn." }
        $cli = Clojure-Command
        $forward = @($commandArgs | Select-Object -Skip 1)
        & $cli -Sdeps $deps -M -m uml-viewer.main.ir-generator $policy @forward
        exit $LASTEXITCODE
    }
    if ($commandArgs.Count -gt 0 -and $commandArgs[0] -in @('crap', 'mutate')) {
        $alias = $commandArgs[0]
        $forward = @($commandArgs | Select-Object -Skip 1)
        if (Test-Path 'deps.edn') {
            $cli = Clojure-Command
            # Official ClojureTools treats '-M:<alias>' as a source file; its
            # protocol requires the literal '-M:' plus the alias.
            if ($cli.ModuleName -eq 'ClojureTools' -or $cli.Definition -eq 'Invoke-Clojure') {
                & $cli '-M:' $alias @forward
            } else {
                & $cli "-M:$alias" @forward
            }
            exit $LASTEXITCODE
        }
        $tool = if ($alias -eq 'crap') { 'crapper' } else { 'mutator' }
        $directory = Join-Path $root ".uml-viewer/$tool"
        if ($IsWindows) {
            $python = Join-Path $directory '.venv/Scripts/python.exe'
            if (-not (Test-Path -LiteralPath $python)) {
                throw "$python is missing. Install Python 3.11+ (https://www.python.org/downloads/), re-run get-uml-viewer if the checkout is missing, then run: py -3 -m venv `"$directory/.venv`"; & `"$python`" -m pip install -e `"$directory`". The installer does not install dependencies."
            }
            & $python -m $tool @forward
        } else {
            $entry = Join-Path $directory $tool
            if (-not (Test-Path -LiteralPath $entry)) {
                throw "$entry is missing. Re-run get-uml-viewer (set $($tool.ToUpper())_REPO_URL if the clone failed). Install Python 3.11+: https://www.python.org/downloads/"
            }
            & $entry @forward
        }
        exit $LASTEXITCODE
    }
    $cli = Clojure-Command
    if ($IsWindows) {
        $mux = Get-Command psmux.exe -ErrorAction SilentlyContinue
        if (-not $mux) { throw 'psmux v3.3.8 is required: https://github.com/psmux/psmux/releases/tag/v3.3.8' }
        $version = (& $mux -V) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $version -notmatch '^tmux 3\.3\.8\r?\npsmux 3\.3\.8(?: \([^\r\n]+\))?$') {
            throw 'Native Windows requires the fixed psmux v3.3.8 baseline; other versions are pending verification.'
        }
    } elseif (-not (Get-Command tmux -ErrorAction SilentlyContinue)) {
        throw 'tmux is required for the companion. Install tmux with your system package manager.'
    }
    if ($commandArgs.Count -gt 0 -and $commandArgs[0] -eq '--restart') {
        # The launcher owns and waits for its CLI/JVM child. Waiting for this
        # exact process therefore waits for the old JVM without signalling it.
        $record = Join-Path $root '.uml-viewer/viewer-process.json'
        if (Test-Path -LiteralPath $record) {
            $owner = Get-Content -LiteralPath $record -Raw | ConvertFrom-Json
            if (-not $owner.pid -or -not $owner.started) { throw 'Invalid viewer process record; cannot safely restart.' }
            $old = Get-Process -Id $owner.pid -ErrorAction SilentlyContinue
            if ($old) {
                if ($old.StartTime.ToUniversalTime().Ticks.ToString() -ne $owner.started) {
                    throw 'Viewer process identity changed; cannot safely restart.'
                }
                Write-Output 'Waiting for the old viewer to exit after :quit-for-restart...'
                if (-not $old.WaitForExit(30000)) {
                    throw 'Old viewer is still running. Send :quit-for-restart, wait for its exit, then retry ./uml --restart.'
                }
            }
        } else {
            throw 'No viewer process record; wait for the old JVM to exit and launch through ./uml before restarting.'
        }
        $name = Split-Path $root -Leaf
        $diagram = if (Test-Path -LiteralPath "examples/$name.edn") { "examples/$name.edn" } else {
            $first = Get-ChildItem 'examples/*.edn' -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notlike '*.policy.edn' } | Sort-Object Name | Select-Object -First 1
            if ($first) { $first.FullName }
        }
        $commandArgs = @('--restart')
        if ($diagram) { $commandArgs += $diagram }
    }
    # 参数写入数据文件；后台 PS7 进程直接用 ArgumentList 启动 JVM，不经 shell 再解析。
    $directory = Join-Path $root '.uml-viewer'
    $null = New-Item -ItemType Directory -Force -Path $directory
    $manifest = Join-Path $directory ('launch-' + [guid]::NewGuid().ToString('N') + '.json')
    $log = Join-Path $root 'uml-viewer-log.txt'
    $psCli = $cli.CommandType -ne 'Application'
    if ($psCli -and $cli.CommandType -ne 'ExternalScript' -and -not $cli.ModuleName) {
        throw 'Background Clojure CLI must be an executable, a .ps1 entry, or an exported module command.'
    }
    @{ executable = $(if ($cli.CommandType -eq 'ExternalScript' -or -not $psCli) { $cli.Source } else { $cli.Name });
       powershell = $psCli; module = $cli.ModuleName; cwd = $root; log = $log;
       argv = @('-Sdeps', $deps, '-M', '-m', 'uml-viewer.main.uml-viewer') + $commandArgs } |
        ConvertTo-Json -Depth 4 -Compress | Set-Content -LiteralPath $manifest -Encoding utf8
    $info = [Diagnostics.ProcessStartInfo]::new((Get-Process -Id $PID).Path)
    $info.UseShellExecute = $false
    $info.WorkingDirectory = $root
    # Give the detached launcher its own pipes, so callers can capture the
    # wrapper's output without waiting for the viewer's entire lifetime.
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($arg in @('-NoProfile', '-File', "$PSScriptRoot/uml-launch.ps1", $manifest)) {
        $info.ArgumentList.Add($arg)
    }
    $process = [Diagnostics.Process]::Start($info)
    $process.StandardInput.Close()
    Write-Output "UML viewer starting (launcher pid $($process.Id)). Log: $log"
    exit 0
} catch { [Console]::Error.WriteLine("uml: $_"); exit 1 }
