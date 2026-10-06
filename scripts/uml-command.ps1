# Shared installed command runtime. Root is supplied by the thin project entry.
if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Error 'PowerShell 7 is required: https://aka.ms/powershell-install'; exit 1
}
$ErrorActionPreference = 'Stop'
$root = $args[0]
$commandArgs = @($args | Select-Object -Skip 1)
function Clojure-Command {
    $command = Get-Command clojure -ErrorAction SilentlyContinue
    if (-not $command -and $IsWindows) {
        Import-Module ClojureTools -ErrorAction SilentlyContinue
        $command = Get-Command clojure -ErrorAction SilentlyContinue
    }
    if (-not $command) { throw 'Clojure CLI is required. Install: https://clojure.org/guides/install_clojure' }
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
            & $cli "-M:$alias" @forward
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
    if ($IsWindows) { throw 'Native Windows viewer startup is pending the startup/companion ticket. Use uml ir, crap or mutate; installation is available with --install-only.' }
    $null = Clojure-Command
    if (-not (Get-Command zsh -ErrorAction SilentlyContinue)) { throw 'Existing Unix viewer startup requires zsh. Install zsh with your system package manager.' }
    & zsh "$PSScriptRoot/uml-launch-unix" $root $deps @commandArgs
    exit $LASTEXITCODE
} catch { [Console]::Error.WriteLine("uml: $_"); exit 1 }
