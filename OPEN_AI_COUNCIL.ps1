$ErrorActionPreference = 'Stop'
$projectRoot = 'C:\AI-Council'
$existingWindow = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -in @('python.exe','pythonw.exe') -and $_.CommandLine -like '*C:\AI-Council*' -and $_.CommandLine -match 'main\.py' } |
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
Start-Process -FilePath "$projectRoot\.venv\Scripts\pythonw.exe" -ArgumentList '"C:\AI-Council\main.py"' -WorkingDirectory $projectRoot
