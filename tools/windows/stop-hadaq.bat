@echo off
rem Stops everything start-hadaq.bat opened (the windows titled "Hadaq ..."), with their child processes (owner: Omar).
rem Run it before start-hadaq.bat after a git pull, so the API and the agents load the new code.
taskkill /t /f /fi "WINDOWTITLE eq Hadaq*" >/dev/null 2>nul
echo Hadaq stopped.
timeout /t 2 /nobreak >nul
