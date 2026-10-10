# PowerShell 7 installer. Run from the project being examined.
if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Error 'PowerShell 7 is required: https://aka.ms/powershell-install'; exit 1
}
$ErrorActionPreference = 'Stop'
function Fetch-Repo($Directory, $Url, $Ref) {
    if (-not (Test-Path "$Directory/.git")) {
        if (Test-Path $Directory) { throw "Refusing to replace non-git directory: $Directory" }
        & git init -q $Directory
        if ($LASTEXITCODE -ne 0) { throw "git init failed: $Directory" }
    }
    & git -C $Directory fetch --depth 1 $Url $Ref
    if ($LASTEXITCODE -ne 0) { throw "git fetch failed: $Directory" }
    & git -C $Directory checkout -q FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw "git checkout failed: $Directory" }
}
function Setting($Name, $Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($value) { return $value }; return $Default
}
try {
    if ($args.Count -eq 1 -and $args[0] -in @('-h', '--help')) {
        Write-Output 'usage: get-uml-viewer [--install-only]'; exit 0
    }
    if ($args.Count -gt 1 -or ($args.Count -eq 1 -and $args[0] -ne '--install-only')) {
        throw 'usage: get-uml-viewer [--install-only]'
    }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw 'git is required. Install Git: https://git-scm.com/downloads'
    }
    $root = (Get-Location).Path
    New-Item -ItemType Directory -Force '.uml-viewer' | Out-Null
    Fetch-Repo '.uml-viewer/uml-viewer' (Setting 'UML_VIEWER_REPO_URL' 'https://github.com/TLOBillyQ/uml-viewer.git') (Setting 'UML_VIEWER_REF' 'lua')
    if (-not (Test-Path 'deps.edn')) {
        foreach ($tool in @('crapper', 'mutator')) {
            try {
                Fetch-Repo ".uml-viewer/$tool" (Setting "$($tool.ToUpper())_REPO_URL" "https://github.com/TLOBillyQ/$tool.git") (Setting "$($tool.ToUpper())_REF" 'lua')
            } catch { Write-Warning "Could not fetch ${tool}: $_" }
        }
    }
    $ignore = if (Test-Path '.gitignore') { [string](Get-Content '.gitignore' -Raw) } else { '' }
    $ignore = [regex]::Replace($ignore, '(?ms)^# BEGIN UML-VIEWER\r?\n.*?^# END UML-VIEWER\r?\n?', '')
    [IO.File]::WriteAllText((Join-Path $root '.gitignore'), $ignore.TrimEnd() + "`n# BEGIN UML-VIEWER`n.uml-viewer/`numl-viewer-log.txt`n# END UML-VIEWER`n")
    foreach ($name in @('uml', 'uml.ps1')) {
        Copy-Item ".uml-viewer/uml-viewer/scripts/$name" (Join-Path $root $name) -Force
    }
    # Earlier installers also wrote a uml.cmd shim. Windows now runs uml.ps1
    # through pwsh directly; remove a stale shim only when its content is the
    # one this installer generated, so a user's own uml.cmd is never touched.
    $stale = Join-Path $root 'uml.cmd'
    if ((Test-Path -LiteralPath $stale) -and
        ((Get-Content -LiteralPath $stale -Raw) -match '-NoProfile -File "%~dp0uml\.ps1"')) {
        Remove-Item -LiteralPath $stale -Force
    }
    if (-not $IsWindows) {
        & chmod +x (Join-Path $root 'uml')
        if ($LASTEXITCODE -ne 0) { throw 'Could not make ./uml executable' }
    }
    Write-Output 'Installed uml and uml.ps1. Run ./uml (Windows: pwsh -File .\uml.ps1) to start.'
    if ($args.Count -eq 0) { & (Join-Path $root 'uml.ps1'); exit $LASTEXITCODE }
    exit 0
} catch { [Console]::Error.WriteLine("get-uml-viewer: $_"); exit 1 }
