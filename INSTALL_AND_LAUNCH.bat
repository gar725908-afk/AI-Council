@echo off
setlocal
cd /d "%~dp0"
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
where py >nul 2>&1
if errorlevel 1 (
  echo Python 3 was not found.
  echo Install Python 3.11+ and run this file again.
  pause
  exit /b 1
)
if not exist ".venv\Scripts\python.exe" (
  echo Creating local virtual environment...
  py -3 -m venv ".venv"
  if errorlevel 1 (
    echo Failed to create virtual environment.
    pause
    exit /b 1
  )
)
echo Updating pip...
".venv\Scripts\python.exe" -m pip install --upgrade pip
if errorlevel 1 goto :fail
echo Installing AI Council dependencies...
".venv\Scripts\python.exe" -m pip install -r "requirements.txt"
if errorlevel 1 goto :fail
echo.
echo Installation complete.
echo Starting AI Council...
".venv\Scripts\python.exe" "main.py"
set "ERR=%ERRORLEVEL%"
pause
exit /b %ERR%
:fail
echo.
echo Installation failed.
pause
exit /b 1
