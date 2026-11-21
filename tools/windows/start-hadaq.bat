@echo off
rem Starts the Hadaq backend on Windows without make or Docker (owner: Omar).
rem Double-click it. The first run creates .venv and installs the Python packages (a few minutes).
rem Then it opens a window each for the API (:8000), the agents (:8100, mock mode unless .env says otherwise),
rem the sensor simulator and the web app (:5173), skipping any that already run, and opens the browser.
setlocal
cd /d "%~dp0..\.."

set "PY=python"
where py >nul 2>nul && set "PY=py -3"
%PY% -c "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)" 2>nul
if errorlevel 1 (
  echo Python 3.11 or newer is needed. Install it from https://www.python.org/downloads/ ^(tick "Add python.exe to PATH"^) and run this again.
  pause
  exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
  echo Creating the Python environment in .venv ...
  %PY% -m venv .venv || goto :fail
)
".venv\Scripts\python.exe" -c "import hadaq_api, hadaq_agents, uvicorn, paho.mqtt" 2>nul
if errorlevel 1 (
  echo Installing the Python packages into .venv ^(first run only, a few minutes^) ...
  ".venv\Scripts\python.exe" -m pip install -U pip || goto :fail
  ".venv\Scripts\python.exe" -m pip install -e packages/hadaq_schemas -e services/simulation -e services/api -e services/agents -e services/vision pytest || goto :fail
)

rem Settings from .env (HADAQ_LLM, ANTHROPIC_API_KEY, ...), the same file the Makefile reads.
if exist ".env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do if not "%%B"=="" set "%%A=%%B"
)
if not defined MQTT_ENABLED set "MQTT_ENABLED=false"
set "SIM_ARGS=--include-real"
if /i "%MQTT_ENABLED%"=="false" set "SIM_ARGS=--include-real --transport http"

rem Each part starts only if it is not already running, so running this twice is safe.
netstat -ano | findstr /r /c:":8000 .*LISTENING" >nul || (
  start "Hadaq API :8000" cmd /k ".venv\Scripts\python.exe -m uvicorn hadaq_api.main:app --port 8000"
  timeout /t 5 /nobreak >nul
)
netstat -ano | findstr /r /c:":8100 .*LISTENING" >nul || start "Hadaq agents :8100" cmd /k ".venv\Scripts\python.exe -m uvicorn hadaq_agents.server:app --port 8100"
tasklist /v /fi "WINDOWTITLE eq Hadaq sensors*" | findstr /i "cmd.exe" >nul || start "Hadaq sensors" cmd /k ".venv\Scripts\python.exe devices\sensor-sim\sensor_sim.py %SIM_ARGS%"
netstat -ano | findstr /r /c:":5173 .*LISTENING" >nul || (
  if not exist "apps\web\node_modules" (
    echo Installing the web app packages ^(first run only^) ...
    pushd apps\web && call npm install & popd
  )
  start "Hadaq web :5173" cmd /k "cd apps\web && npm run dev"
)

echo.
echo Hadaq is running: web http://localhost:5173, API :8000, agents :8100, sensors. Close those windows to stop it.
timeout /t 5 /nobreak >nul
start "" http://localhost:5173
exit /b 0

:fail
echo.
echo Setup failed. Read the error above, fix it, delete the .venv folder and run this again.
pause
exit /b 1
