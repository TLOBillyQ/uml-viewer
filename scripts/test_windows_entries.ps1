# Native Windows regression: real uml.cmd -> PS7 -> official ClojureTools -> JVM.
# Requires PowerShell 7, Java and the ClojureTools module; no Python or Node.
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -or $PSVersionTable.PSVersion.Major -lt 7) {
    throw 'Run this test with PowerShell 7 on native Windows.'
}
Import-Module ClojureTools -ErrorAction Stop
function Native-Path([string]$Path) {
    return [IO.Path]::GetFullPath(([uri]$Path).LocalPath)
}
$repoScripts = Native-Path $PSScriptRoot
$temp = Native-Path (Join-Path $env:LOCALAPPDATA ('Temp/uml native entries ' + [guid]::NewGuid().ToString('N')))
$project = Native-Path (Join-Path $temp 'project with spaces')
$installed = Join-Path $project '.uml-viewer/uml-viewer/scripts'
$originalPath = $env:PATH
$originalModulePath = $env:PSModulePath
function Assert-Equal($Expected, $Actual, [string]$Message) {
    if ($Expected -cne $Actual) { throw "$Message`nExpected: $Expected`nActual: $Actual" }
}
function Run-Entry([string]$Alias) {
    $command = '""' + (Join-Path $project 'uml.cmd') + '" ' + $Alias + ' "src/file with spaces.clj" "literal;$value""'
    $process = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/d', '/s', '/c', $command) -WorkingDirectory $temp `
        -RedirectStandardOutput (Join-Path $temp 'stdout.txt') -RedirectStandardError (Join-Path $temp 'stderr.txt') -Wait -PassThru -NoNewWindow
    $result = @{ Code = $process.ExitCode; Out = Get-Content (Join-Path $temp 'stdout.txt') -Raw; Err = Get-Content (Join-Path $temp 'stderr.txt') -Raw }
    $process.Dispose()
    return $result
}
function Assert-Result($Result, [string[]]$Argv) {
    Assert-Equal 23 $Result.Code ("uml.cmd must retain alias exit status. stdout=$($Result.Out) stderr=$($Result.Err)")
    $raw = [IO.File]::ReadAllBytes((Join-Path $temp 'stdout.txt'))
    $ansi = [regex]'\x1b\[[0-9;]*m'
    $lines = @($ansi.Replace([Text.Encoding]::UTF8.GetString($raw), '') -split '\r?\n' | Where-Object {
        $_ -and $_ -notmatch '^WARNING: Implicit use of clojure.main' -and
        $_ -notmatch '^\s+目录:' -and $_ -notmatch '^\s*Mode\s+LastWriteTime' -and
        $_ -notmatch '^[-\s]*$' -and $_ -notmatch '^d[-a-z]+\s' -and $_ -notmatch '^-a[-a-z]+\s' -and
        $_ -notmatch '^\s+警告:' -and $_ -notmatch '^\s+Mode\s+$' -and $_ -notmatch '^\s+----\s+' -and
        $_ -notmatch '^\s*Length\s+Name\s*$' -and $_ -notmatch '^\s+LastWriteTime\s+$'
    })
    Assert-Equal 'fixture-stdout' $lines[0] 'Alias stdout must reach the caller.'
    Assert-Equal $project ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($lines[1]))) 'Alias cwd must be the project.'
    $received = @($lines | Select-Object -Skip 2 | ForEach-Object { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) })
    Assert-Equal ($Argv | ConvertTo-Json -Compress) ($received | ConvertTo-Json -Compress) 'Alias arguments must remain separate and literal.'
    Assert-Equal 'fixture-stderr' $Result.Err.Trim() 'Alias stderr must reach the caller.'
}
try {
    $null = New-Item -ItemType Directory -Path $installed -Force
    Copy-Item (Join-Path $repoScripts 'uml.cmd'), (Join-Path $repoScripts 'uml.ps1') $project
    Copy-Item (Join-Path $repoScripts 'uml-command.ps1') $installed
    @'
{:paths ["src"]
 :aliases {:crap {:main-opts ["-m" "entry-fixture" "crap"]}
           :mutate {:main-opts ["-m" "entry-fixture" "mutate"]}}}
'@ | Set-Content (Join-Path $project 'deps.edn') -Encoding utf8
    $null = New-Item -ItemType Directory -Path (Join-Path $project 'src')
    @'
(ns entry-fixture)
(defn encoded [s] (.encodeToString (java.util.Base64/getEncoder) (.getBytes s "UTF-8")))
(defn -main [& args]
  (.println System/out "fixture-stdout")
  (.println System/out (encoded (System/getProperty "user.dir")))
  (doseq [arg args] (.println System/out (encoded arg)))
  (.flush System/out)
  (.println System/err "fixture-stderr")
  (.flush System/err)
  (System/exit 23))
'@ | Set-Content (Join-Path $project 'src/entry_fixture.clj') -Encoding utf8
    foreach ($alias in @('crap', 'mutate')) {
        Assert-Result (Run-Entry $alias) @($alias, 'src/file with spaces.clj', 'literal;$value')
        Write-Host "PASS ClojureTools uml.cmd ${alias}: argv, cwd, streams, exit=23"
    }
    # A PATH shim would shadow module auto-loading in uml.cmd's fresh -NoProfile PS7.
    # uml-command.ps1 must import ClojureTools anyway and resolve the module alias.
    $tools = Join-Path $temp 'external tools'
    $null = New-Item -ItemType Directory -Path $tools
    @'
Write-Output 'fixture-stdout'
@((Get-Location).Path) + @($args) | ForEach-Object {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_))
}
[Console]::Error.WriteLine('fixture-stderr')
exit 23
'@ | Set-Content (Join-Path $tools 'clojure.ps1') -Encoding utf8
    $env:PATH = $tools + ';' + $env:PATH
    foreach ($alias in @('crap', 'mutate')) {
        Assert-Result (Run-Entry $alias) @($alias, 'src/file with spaces.clj', 'literal;$value')
        Write-Host "PASS PATH-shim uml.cmd ${alias}: module alias resolved over shim, exit=23"
    }
    # 没有官方模块时，外部 PS1 保留常规的单参数 alias 协议。
    $emptyModules = Join-Path $temp 'empty modules'
    $null = New-Item -ItemType Directory -Path $emptyModules
    $env:PSModulePath = $emptyModules
    foreach ($alias in @('crap', 'mutate')) {
        Assert-Result (Run-Entry $alias) @("-M:$alias", 'src/file with spaces.clj', 'literal;$value')
        Write-Host "PASS ExternalScript uml.cmd ${alias}: original argv, cwd, streams, exit=23"
    }
} finally {
    $env:PSModulePath = $originalModulePath
    $env:PATH = $originalPath
    Remove-Item -LiteralPath $temp -Recurse -Force
}
