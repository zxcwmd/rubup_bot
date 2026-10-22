@echo off
setlocal EnableExtensions
cd /d "%~dp0\.."
set "PYTHON_CMD="

rem Prefer the Python launcher, but accept any installed Python 3.11+.
where py >nul 2>nul
if not errorlevel 1 (
  py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)" >nul 2>nul
  if not errorlevel 1 set "PYTHON_CMD=py -3"
)

rem Fallback for Python installed without the launcher.
if not defined PYTHON_CMD (
  where python >nul 2>nul
  if not errorlevel 1 (
    python -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)" >nul 2>nul
    if not errorlevel 1 set "PYTHON_CMD=python"
  )
)

if not defined PYTHON_CMD (
  echo Python 3.11 or newer was not found.
  echo.
  echo Install 64-bit Python 3.12 from https://www.python.org/downloads/
  echo During installation enable "Add Python to PATH" and the Python launcher.
  echo.
  echo Or install it with this command, then reopen this installer:
  echo winget install -e --id Python.Python.3.12
  echo.
  echo If Python is already installed, check it with: py -0p
  pause
  exit /b 1
)

%PYTHON_CMD% -c "import sys; print('Using Python ' + sys.version.split()[0])"
if errorlevel 1 goto :error
%PYTHON_CMD% -m venv .venv
if errorlevel 1 goto :error
.venv\Scripts\python.exe -m pip install --upgrade pip
if errorlevel 1 goto :error
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto :error
.venv\Scripts\playwright.exe install chromium
if errorlevel 1 goto :error
if not exist .env copy .env.example .env >nul
.venv\Scripts\python.exe -m rubup_bot init
if errorlevel 1 goto :error
echo.
echo Installation complete. Edit .env, then run scripts\login.bat
pause
exit /b 0
:error
echo.
echo Installation failed. Read the error above, fix it, and run this file again.
pause
exit /b 1
