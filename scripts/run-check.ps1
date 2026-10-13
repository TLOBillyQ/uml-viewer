$CommandArgs = $args
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try { & clj @CommandArgs; exit $LASTEXITCODE } finally { Pop-Location }
