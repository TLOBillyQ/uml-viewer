@echo off
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  echo get-uml-viewer: PowerShell 7 ^(pwsh^) is required. Install: https://aka.ms/powershell-install 1>&2
  exit /b 1
)
pwsh.exe -NoLogo -NoProfile -File "%~dp0get-uml-viewer.ps1" %*
exit /b %errorlevel%
