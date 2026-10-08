@echo off
py -3 "%~dp0bridge\bridge_client.py" off >nul 2>&1
for /f "tokens=2" %%P in ('tasklist /fi "imagename eq python.exe" /fo table ^| findstr bridge_server.py') do taskkill /PID %%P /F >nul 2>&1
for /f "tokens=2" %%P in ('tasklist /fi "imagename eq pythonw.exe" /fo table ^| findstr bridge_ui.py') do taskkill /PID %%P /F >nul 2>&1
exit /b 0
