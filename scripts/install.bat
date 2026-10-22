@echo off
setlocal
cd /d "%~dp0\.."
where py >nul 2>nul
if errorlevel 1 (
  echo Python 3.11+ not found. Install it from python.org and enable Add to PATH.
  pause
  exit /b 1
)
py -3.11 -m venv .venv
if errorlevel 1 goto :error
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install -r requirements.txt
.venv\Scripts\playwright.exe install chromium
if not exist .env copy .env.example .env >nul
.venv\Scripts\python.exe -m rubup_bot init
echo.
echo Installation complete. Edit .env, then run scripts\login.bat
pause
exit /b 0
:error
echo Installation failed.
pause
exit /b 1
