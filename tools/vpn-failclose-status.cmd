@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0vpn-failclose.ps1" -Action Status
echo.
pause
