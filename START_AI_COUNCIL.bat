@echo off
setlocal
cd /d "%~dp0"
set "PROJECT_ROOT=%~dp0"
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
if exist ".venv\Scripts\python.exe" (
  set "PY=%~dp0.venv\Scripts\python.exe"
) else (
  where py >nul 2>&1
  if errorlevel 1 (
    echo Python 3 was not found.
    pause
    exit /b 1
  )
  set "PY=PYLAUNCHER"
)
if "%PY%"=="PYLAUNCHER" (
  py -3 -c "import PySide6, qdarktheme" >nul 2>&1
  if errorlevel 1 (
    echo Required Python packages are missing.
    echo Run INSTALL_AND_LAUNCH.bat first.
    pause
    exit /b 1
  )
  py -3 main.py
) else (
  "%PY%" -c "import PySide6, qdarktheme" >nul 2>&1
  if errorlevel 1 (
    echo Required Python packages are missing.
    echo Run INSTALL_AND_LAUNCH.bat first.
    pause
    exit /b 1
  )
  "%PY%" main.py
)
set "ERR=%ERRORLEVEL%"
echo.
echo AI Council exited with code %ERR%.
pause
exit /b %ERR%
