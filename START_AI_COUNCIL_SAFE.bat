@echo off
setlocal
cd /d "%~dp0"
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
set "QTWEBENGINE_CHROMIUM_FLAGS=--disable-gpu"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" main.py
) else (
  py -3 main.py
)
set "ERR=%ERRORLEVEL%"
echo.
echo AI Council exited with code %ERR%.
pause
exit /b %ERR%
