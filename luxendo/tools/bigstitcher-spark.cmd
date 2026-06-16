@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0bigstitcher-spark.ps1" %*
exit /b %ERRORLEVEL%
