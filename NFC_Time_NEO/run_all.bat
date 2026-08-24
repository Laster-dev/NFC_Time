@echo off
title NFC_Time_NEO - All-in-One Launcher
echo ===================================================
echo   🌟 NFC_Time_NEO 全系统一键启动
echo ===================================================
echo.

set SCRIPT_DIR=%~dp0

echo [1/3] 正在启动后端服务 (http://localhost:5000)...
start "NfcServer" "%SCRIPT_DIR%start_server.bat"

timeout /t 2 /nobreak >nul

echo [2/3] 正在启动智能语音播报服务...
start "NfcVoice" "%SCRIPT_DIR%start_voice.bat"

echo [3/3] 正在打开实时时光大屏网页...
start http://localhost:5000

echo.
echo ===================================================
echo   ✅ NFC_Time_NEO 全套系统已就绪！
echo ===================================================
echo.
