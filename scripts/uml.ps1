# Thin project entry. The installed checkout owns the command logic.
if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Error 'PowerShell 7 is required: https://aka.ms/powershell-install'; exit 1
}
& "$PSScriptRoot/.uml-viewer/uml-viewer/scripts/uml-command.ps1" $PSScriptRoot @args
exit $LASTEXITCODE
