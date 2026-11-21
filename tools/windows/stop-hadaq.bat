@echo off
rem Stops everything start-hadaq.bat opened (the cmd windows titled "Hadaq ..."), with their child processes (owner: Omar).
rem Only cmd.exe windows are matched: a browser tab titled "Hadaq" is left alone.
rem Run it before start-hadaq.bat after a git pull, so the API and the agents load the new code.
taskkill /t /f /fi "IMAGENAME eq cmd.exe" /fi "WINDOWTITLE eq Hadaq*" >nul 2>nul
echo Hadaq stopped.
timeout /t 2 /nobreak >nul
