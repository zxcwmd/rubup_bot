@echo off
setlocal
cd /d "%~dp0\.."
if not exist .venv\Scripts\python.exe (
  echo Run scripts\install.bat first.
  pause
  exit /b 1
)
.venv\Scripts\python.exe -m rubup_bot login
pause
