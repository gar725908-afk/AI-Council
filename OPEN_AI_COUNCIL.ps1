$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$existingWindow = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -in @('python.exe','pythonw.exe') -and $_.CommandLine -like "*$projectRoot*" -and $_.CommandLine -match 'main\.py' } |
    ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue } |
    Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like 'AI Council*' } |
    Select-Object -First 1
if ($existingWindow) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class CouncilShortcutWindow {
    [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr handle, int command);
}
"@
    [CouncilShortcutWindow]::ShowWindowAsync($existingWindow.MainWindowHandle, 9) | Out-Null
    $activateShell = New-Object -ComObject WScript.Shell
    $activateShell.AppActivate($existingWindow.Id) | Out-Null
    exit 0
}
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$pythonw = Join-Path $projectRoot '.venv\Scripts\pythonw.exe'
$python = Join-Path $projectRoot '.venv\Scripts\python.exe'
$main = Join-Path $projectRoot 'main.py'
if (Test-Path $pythonw) {
    Start-Process -FilePath $pythonw -ArgumentList ('"' + $main + '"') -WorkingDirectory $projectRoot
} elseif (Test-Path $python) {
    Start-Process -FilePath $python -ArgumentList ('"' + $main + '"') -WorkingDirectory $projectRoot
} else {
    Start-Process -FilePath 'py.exe' -ArgumentList '-3', ('"' + $main + '"') -WorkingDirectory $projectRoot
}
