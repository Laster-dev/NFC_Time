@echo off
title NFC_Time_NEO - Native AOT Backend Server
echo ===================================================
echo   NFC_Time_NEO 独立后端服务 (Native AOT Release)
echo ===================================================
echo.

set SCRIPT_DIR=%~dp0

if exist "%SCRIPT_DIR%server\publish_aot\NfcServer.exe" (
    echo [*] 正在启动 Native AOT 原生高性能服务 (端口: 5000)...
    cd /d "%SCRIPT_DIR%server\publish_aot"
    NfcServer.exe
) else (
    echo [*] 未检测到 publish_aot，使用 dotnet 启动...
    cd /d "%SCRIPT_DIR%server"
    dotnet run -c Release --no-launch-profile
)

pause
