@echo off
setlocal
cd /d "%~dp0report-desk"
start "" "..\runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -cp "report-desk-desktop.jar;..\lib\*" Desk
