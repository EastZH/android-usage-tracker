@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo   手机用量同步 — 待命监听
echo   ----------------------------------------
echo   保持这个窗口开着，然后在手机上点「导出」。
echo   关掉窗口即停止。
echo.
python watch.py
pause
