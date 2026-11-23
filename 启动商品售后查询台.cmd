@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" -Launch && exit /b 0 || (pause & exit /b 1)
