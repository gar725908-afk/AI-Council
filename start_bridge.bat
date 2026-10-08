@echo off
setlocal
cd /d "%~dp0bridge"
py -3 -c "import sys; print(sys.version)"
if errorlevel 1 (
  echo Python 3 not found in PATH.
  pause
  exit /b 1
)
if not exist "%~dp0bridge\.deps_ok" (
  echo Installing bridge dependencies...
  py -3 -m pip install -r "%~dp0bridge\requirements.txt"
  if errorlevel 1 (
    echo Dependency installation failed.
    pause
    exit /b 1
  )
  type nul > "%~dp0bridge\.deps_ok"
)
py -3 "%~dp0bridge\bridge_ui.py"
endlocal
