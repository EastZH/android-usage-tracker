@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo   手机用量同步
echo   ----------------------------------------
echo   双击即可，手机上不需要任何操作。
echo.
python sync.py %*
echo.
pause
